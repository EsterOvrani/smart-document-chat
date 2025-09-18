package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * שירות לניהול החלפת שיחות פעילות
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionSwitchingService {

    private final ChatSessionService chatSessionService;
    private final CacheService cacheService;
    private final QdrantVectorService qdrantVectorService;

    /**
     * החלפה לשיחה אחרת עם עדכון מצב
     */
    public SessionSwitchResult switchToSession(User user, Long targetSessionId, Long currentSessionId) {
        validateUser(user);

        try {
            // שמירת מצב השיחה הנוכחית
            if (currentSessionId != null && currentSessionId > 0) {
                Optional<ChatSession> currentSessionOpt = chatSessionService.findById(currentSessionId);
                if (currentSessionOpt.isPresent() &&
                        chatSessionService.isSessionOwnedByUser(currentSessionId, user)) {

                    ChatSession currentSession = currentSessionOpt.get();
                    saveCurrentSessionState(currentSession);
                    log.debug("שמר מצב שיחה נוכחית: {}", currentSessionId);
                }
            }

            // מעבר לשיחה החדשה
            Optional<ChatSession> targetSessionOpt = chatSessionService.findById(targetSessionId);

            if (targetSessionOpt.isEmpty()) {
                return SessionSwitchResult.error("שיחה לא נמצאה");
            }

            ChatSession targetSession = targetSessionOpt.get();

            // בדיקת הרשאות
            if (!chatSessionService.isSessionOwnedByUser(targetSessionId, user)) {
                return SessionSwitchResult.error("אין הרשאה לשיחה זו");
            }

            if (!targetSession.getActive()) {
                return SessionSwitchResult.error("שיחה לא פעילה");
            }

            // טעינת מצב השיחה החדשה
            SessionState newSessionState = loadSessionState(targetSession);

            // עדכון פעילות השיחה
            chatSessionService.updateLastActivity(targetSessionId);
            chatSessionService.setActiveSession(user, targetSession);

            log.info("משתמש {} עבר לשיחה {}: '{}'",
                    user.getUsername(), targetSessionId, targetSession.getDisplayTitle());

            return SessionSwitchResult.success(targetSession, newSessionState);

        } catch (Exception e) {
            log.error("שגיאה בהחלפת שיחה למשתמש {} (מ-{} ל-{})",
                    user.getId(), currentSessionId, targetSessionId, e);
            return SessionSwitchResult.error("שגיאה בהחלפת השיחה: " + e.getMessage());
        }
    }

    /**
     * קבלת רשימת שיחות זמינות להחלפה
     */
    public List<SessionSwitchOption> getAvailableSessions(User user, Long currentSessionId) {
        validateUser(user);

        List<ChatSession> allSessions = chatSessionService.getUserSessions(user);

        return allSessions.stream()
                .filter(session -> !session.getId().equals(currentSessionId))
                .map(this::createSwitchOption)
                .sorted((a, b) -> b.lastActivity.compareTo(a.lastActivity))
                .toList();
    }

    /**
     * החלפה לשיחה הקודמת (במקרה של ביטול)
     */
    public SessionSwitchResult switchToPreviousSession(User user) {
        validateUser(user);

        String previousSessionKey = "previous_session:" + user.getId();
        Long previousSessionId = (Long) cacheService.get(previousSessionKey);

        if (previousSessionId == null) {
            // אם אין שיחה קודמת, עבור לשיחה האחרונה
            Optional<ChatSession> lastSession = chatSessionService.getLastSession(user);
            if (lastSession.isPresent()) {
                return switchToSession(user, lastSession.get().getId(), null);
            }
            return SessionSwitchResult.error("לא נמצאה שיחה קודמת");
        }

        return switchToSession(user, previousSessionId, null);
    }

    /**
     * יצירת שיחה חדשה והחלפה אליה
     */
    public SessionSwitchResult createAndSwitchToNewSession(User user, String title,
                                                           String description, Long currentSessionId) {
        validateUser(user);

        try {
            // שמירת השיחה הנוכחית כקודמת
            if (currentSessionId != null) {
                storePreviousSession(user, currentSessionId);
            }

            // יצירת השיחה החדשה
            ChatSession newSession = chatSessionService.createSession(user, title, description);

            // מעבר לשיחה החדשה
            SessionState newSessionState = loadSessionState(newSession);
            chatSessionService.setActiveSession(user, newSession);

            log.info("משתמש {} יצר והעבר לשיחה חדשה {}: '{}'",
                    user.getUsername(), newSession.getId(), newSession.getDisplayTitle());

            return SessionSwitchResult.success(newSession, newSessionState);

        } catch (Exception e) {
            log.error("שגיאה ביצירת והחלפה לשיחה חדשה למשתמש {}", user.getId(), e);
            return SessionSwitchResult.error("שגיאה ביצירת שיחה חדשה: " + e.getMessage());
        }
    }

    /**
     * החלפה מהירה לשיחות אחרונות (קיצורי מקלדת)
     */
    public List<SessionSwitchOption> getRecentSessions(User user, int limit) {
        validateUser(user);

        List<ChatSession> recentSessions = chatSessionService.getUserSessions(user);

        return recentSessions.stream()
                .limit(limit)
                .map(this::createSwitchOption)
                .toList();
    }

    /**
     * שמירת מצב שיחה נוכחית
     */
    private void saveCurrentSessionState(ChatSession session) {
        String stateKey = "session_state:" + session.getId();

        SessionState state = new SessionState(
                session.getId(),
                session.getDisplayTitle(),
                LocalDateTime.now(),
                session.getDocumentCount(),
                session.getMessageCount()
        );

        cacheService.set(stateKey, state, java.time.Duration.ofHours(2));
        log.debug("שמר מצב שיחה: {}", session.getId());
    }

    /**
     * טעינת מצב שיחה
     */
    private SessionState loadSessionState(ChatSession session) {
        String stateKey = "session_state:" + session.getId();

        SessionState cachedState = (SessionState) cacheService.get(stateKey);

        if (cachedState != null) {
            log.debug("טען מצב שיחה מcache: {}", session.getId());
            return cachedState;
        }

        // יצירת מצב חדש
        SessionState newState = new SessionState(
                session.getId(),
                session.getDisplayTitle(),
                session.getLastActivityAt() != null ? session.getLastActivityAt() : session.getCreatedAt(),
                session.getDocumentCount(),
                session.getMessageCount()
        );

        // שמירה בcache
        cacheService.set(stateKey, newState, java.time.Duration.ofHours(2));

        return newState;
    }

    /**
     * שמירת שיחה קודמת
     */
    private void storePreviousSession(User user, Long sessionId) {
        String previousSessionKey = "previous_session:" + user.getId();
        cacheService.set(previousSessionKey, sessionId, java.time.Duration.ofHours(1));
    }

    /**
     * יצירת אפשרות החלפה
     */
    private SessionSwitchOption createSwitchOption(ChatSession session) {
        return new SessionSwitchOption(
                session.getId(),
                session.getDisplayTitle(),
                session.getDescription(),
                session.getLastActivityAt() != null ? session.getLastActivityAt() : session.getCreatedAt(),
                session.getDocumentCount(),
                session.getMessageCount(),
                qdrantVectorService.hasEmbeddingStoreForSession(
                        session.getId(), session.getUser().getId())
        );
    }

    private void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("משתמש לא תקין");
        }

        if (!user.getActive()) {
            throw new SecurityException("משתמש לא פעיל");
        }
    }

    // Inner classes

    public static class SessionSwitchResult {
        public final boolean success;
        public final String error;
        public final ChatSession session;
        public final SessionState sessionState;

        private SessionSwitchResult(boolean success, String error, ChatSession session, SessionState sessionState) {
            this.success = success;
            this.error = error;
            this.session = session;
            this.sessionState = sessionState;
        }

        public static SessionSwitchResult success(ChatSession session, SessionState sessionState) {
            return new SessionSwitchResult(true, null, session, sessionState);
        }

        public static SessionSwitchResult error(String error) {
            return new SessionSwitchResult(false, error, null, null);
        }
    }

    public static class SessionSwitchOption {
        public final Long sessionId;
        public final String title;
        public final String description;
        public final LocalDateTime lastActivity;
        public final int documentCount;
        public final int messageCount;
        public final boolean hasVectorData;

        public SessionSwitchOption(Long sessionId, String title, String description,
                                   LocalDateTime lastActivity, int documentCount, int messageCount,
                                   boolean hasVectorData) {
            this.sessionId = sessionId;
            this.title = title;
            this.description = description;
            this.lastActivity = lastActivity;
            this.documentCount = documentCount;
            this.messageCount = messageCount;
            this.hasVectorData = hasVectorData;
        }
    }

    public static class SessionState {
        public final Long sessionId;
        public final String title;
        public final LocalDateTime lastAccess;
        public final int documentCount;
        public final int messageCount;

        public SessionState(Long sessionId, String title, LocalDateTime lastAccess,
                            int documentCount, int messageCount) {
            this.sessionId = sessionId;
            this.title = title;
            this.lastAccess = lastAccess;
            this.documentCount = documentCount;
            this.messageCount = messageCount;
        }
    }
}