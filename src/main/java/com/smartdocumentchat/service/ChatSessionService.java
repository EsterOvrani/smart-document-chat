package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;
    private final CacheService cacheService;

    /**
     * יצירת שיחה חדשה
     */
    public ChatSession createSession(User user, String title, String description) {
        ChatSession session = new ChatSession();
        session.setUser(user);
        session.setTitle(title != null ? title : "שיחה חדשה");
        session.setDescription(description);
        session.setActive(true);
        session.setLastActivityAt(LocalDateTime.now());

        ChatSession savedSession = chatSessionRepository.save(session);

        // Cache the new session
        cacheSessionData(savedSession);

        // Invalidate user sessions list cache
        invalidateUserSessionsCache(user.getId());

        log.info("שיחה חדשה נוצרה: {} עבור משתמש: {}", savedSession.getId(), user.getUsername());
        return savedSession;
    }

    /**
     * קבלת שיחה לפי ID עם caching
     */
    public Optional<ChatSession> findById(Long sessionId) {
        // Try to get from cache first
        String cacheKey = "session:" + sessionId;
        ChatSession cachedSession = (ChatSession) cacheService.getSessionData(cacheKey);

        if (cachedSession != null) {
            log.debug("Session {} retrieved from cache", sessionId);
            return Optional.of(cachedSession);
        }

        // If not in cache, get from database
        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(sessionId);

        if (sessionOpt.isPresent()) {
            // Cache the session
            cacheSessionData(sessionOpt.get());
            log.debug("Session {} retrieved from database and cached", sessionId);
        }

        return sessionOpt;
    }

    /**
     * קבלת כל השיחות של משתמש עם caching
     */
    public List<ChatSession> getUserSessions(User user) {
        String cacheKey = "user_sessions:" + user.getId();

        @SuppressWarnings("unchecked")
        List<ChatSession> cachedSessions = (List<ChatSession>) cacheService.get(cacheKey);

        if (cachedSessions != null) {
            log.debug("User {} sessions retrieved from cache", user.getId());
            return cachedSessions;
        }

        // Get from database
        List<ChatSession> sessions = chatSessionRepository.findByUserAndActiveTrueOrderByUpdatedAtDesc(user);

        // Cache the result
        cacheService.set(cacheKey, sessions, java.time.Duration.ofMinutes(15));
        log.debug("User {} sessions retrieved from database and cached", user.getId());

        return sessions;
    }

    /**
     * קבלת השיחה האחרונה של משתמש
     */
    public Optional<ChatSession> getLastSession(User user) {
        String cacheKey = "last_session:" + user.getId();
        ChatSession cachedSession = (ChatSession) cacheService.get(cacheKey);

        if (cachedSession != null) {
            log.debug("Last session for user {} retrieved from cache", user.getId());
            return Optional.of(cachedSession);
        }

        Optional<ChatSession> sessionOpt = chatSessionRepository.findFirstByUserAndActiveTrueOrderByUpdatedAtDesc(user);

        if (sessionOpt.isPresent()) {
            // Cache for shorter time as this changes frequently
            cacheService.set(cacheKey, sessionOpt.get(), java.time.Duration.ofMinutes(5));
            log.debug("Last session for user {} retrieved from database and cached", user.getId());
        }

        return sessionOpt;
    }

    /**
     * יצירה או קבלה של שיחה ברירת מחדל למשתמש
     */
    public ChatSession getOrCreateDefaultSession(User user) {
        // נסה למצוא שיחה קיימת
        Optional<ChatSession> existingSession = getLastSession(user);

        if (existingSession.isPresent()) {
            ChatSession session = existingSession.get();
            // עדכן זמן פעילות אחרונה
            session.setLastActivityAt(LocalDateTime.now());
            ChatSession updatedSession = chatSessionRepository.save(session);

            // Update cache
            cacheSessionData(updatedSession);

            return updatedSession;
        }

        // צור שיחה חדשה
        return createSession(user, "שיחה ראשונה", "שיחת ברירת מחדל");
    }

    /**
     * עדכון פעילות אחרונה של שיחה
     */
    public void updateLastActivity(Long sessionId) {
        Optional<ChatSession> sessionOpt = findById(sessionId);
        if (sessionOpt.isPresent()) {
            ChatSession session = sessionOpt.get();
            session.setLastActivityAt(LocalDateTime.now());
            ChatSession updatedSession = chatSessionRepository.save(session);

            // Update cache
            cacheSessionData(updatedSession);

            // Update last session cache for user
            String lastSessionCacheKey = "last_session:" + session.getUser().getId();
            cacheService.set(lastSessionCacheKey, updatedSession, java.time.Duration.ofMinutes(5));
        }
    }

    /**
     * עדכון כותרת ותיאור שיחה
     */
    public ChatSession updateSession(Long sessionId, String title, String description, User user) {
        Optional<ChatSession> sessionOpt = findById(sessionId);

        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("שיחה לא נמצאה");
        }

        ChatSession session = sessionOpt.get();

        // בדיקת הרשאות
        if (!session.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("אין הרשאה לעדכן שיחה זו");
        }

        session.setTitle(title);
        session.setDescription(description);

        ChatSession updatedSession = chatSessionRepository.save(session);

        // Update cache
        cacheSessionData(updatedSession);

        // Invalidate user sessions cache as the list has changed
        invalidateUserSessionsCache(user.getId());

        return updatedSession;
    }

    /**
     * מחיקת שיחה (soft delete)
     */
    public boolean deleteSession(Long sessionId, User user) {
        Optional<ChatSession> sessionOpt = findById(sessionId);

        if (sessionOpt.isEmpty()) {
            return false;
        }

        ChatSession session = sessionOpt.get();

        // בדיקת הרשאות
        if (!session.getUser().getId().equals(user.getId())) {
            log.warn("משתמש {} מנסה למחוק שיחה {} של משתמש אחר",
                    user.getId(), sessionId);
            return false;
        }

        session.setActive(false);
        ChatSession deletedSession = chatSessionRepository.save(session);

        // Remove from cache
        cacheService.invalidateSession("session:" + sessionId);

        // Invalidate related caches
        invalidateUserSessionsCache(user.getId());
        cacheService.delete("last_session:" + user.getId());

        log.info("שיחה {} נמחקה בהצלחה", sessionId);
        return true;
    }

    /**
     * ספירת שיחות פעילות של משתמש
     */
    public long countUserSessions(User user) {
        String cacheKey = "user_sessions_count:" + user.getId();
        Object cachedCount = cacheService.get(cacheKey);

        if (cachedCount instanceof Number) {
            log.debug("Session count for user {} retrieved from cache", user.getId());
            return ((Number) cachedCount).longValue();
        }

        long count = chatSessionRepository.countByUserAndActiveTrue(user);

        // Cache for 10 minutes
        cacheService.set(cacheKey, count, java.time.Duration.ofMinutes(10));
        log.debug("Session count for user {} retrieved from database and cached", user.getId());

        return count;
    }

    /**
     * בדיקה אם השיחה שייכת למשתמש
     */
    public boolean isSessionOwnedByUser(Long sessionId, User user) {
        Optional<ChatSession> sessionOpt = findById(sessionId);
        return sessionOpt.isPresent() &&
                sessionOpt.get().getUser().getId().equals(user.getId()) &&
                sessionOpt.get().getActive();
    }

    /**
     * חיפוש שיחות לפי כותרת (לא cached כרגע)
     */
    public List<ChatSession> searchSessions(User user, String searchTerm) {
        return chatSessionRepository.findByUserAndTitleContaining(user, searchTerm);
    }

    // Private helper methods

    /**
     * Cache session data
     */
    private void cacheSessionData(ChatSession session) {
        String cacheKey = "session:" + session.getId();
        cacheService.cacheSessionData(cacheKey, session);
    }

    /**
     * Invalidate user sessions cache
     */
    private void invalidateUserSessionsCache(Long userId) {
        cacheService.delete("user_sessions:" + userId);
        cacheService.delete("user_sessions_count:" + userId);
        log.debug("Invalidated user sessions cache for user {}", userId);
    }
    /**
     * קביעת שיחה פעילה עבור משתמש
     */
    public void setActiveSession(User user, ChatSession session) {
        String cacheKey = "active_session:" + user.getId();
        cacheService.set(cacheKey, session, java.time.Duration.ofHours(2));
        log.debug("Set active session {} for user {}", session.getId(), user.getId());
    }

    /**
     * קבלת השיחה הפעילה של משתמש
     */
    public Optional<ChatSession> getActiveSession(User user) {
        String cacheKey = "active_session:" + user.getId();
        ChatSession activeSession = (ChatSession) cacheService.get(cacheKey);

        if (activeSession != null) {
            log.debug("Active session {} retrieved from cache for user {}",
                    activeSession.getId(), user.getId());
            return Optional.of(activeSession);
        }

        return Optional.empty();
    }

    /**
     * ניקוי שיחה פעילה
     */
    public void clearActiveSession(User user) {
        String cacheKey = "active_session:" + user.getId();
        cacheService.delete(cacheKey);
        log.debug("Cleared active session for user {}", user.getId());
    }
}