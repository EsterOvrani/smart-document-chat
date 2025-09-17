package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
     * יצירת שיחה חדשה עם validation משופר
     */
    @Transactional
    public ChatSession createSession(User user, String title, String description) {
        validateUser(user);
        validateTitle(title);

        ChatSession session = new ChatSession();
        session.setUser(user);
        session.setTitle(title.trim());
        session.setDescription(description != null ? description.trim() : null);
        session.setActive(true);
        session.setLastActivityAt(LocalDateTime.now());

        ChatSession savedSession = chatSessionRepository.save(session);

        // Cache the new session
        cacheSessionData(savedSession);

        // Invalidate user sessions list cache
        invalidateUserSessionsCache(user.getId());

        log.info("שיחה חדשה נוצרה: {} ('{}') עבור משתמש: {}",
                savedSession.getId(), savedSession.getTitle(), user.getUsername());
        return savedSession;
    }

    /**
     * קבלת שיחה לפי ID עם caching משופר
     */
    public Optional<ChatSession> findById(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            log.warn("ניסיון קבלת שיחה עם ID לא תקין: {}", sessionId);
            return Optional.empty();
        }

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
            ChatSession session = sessionOpt.get();

            // Only cache active sessions
            if (session.getActive()) {
                cacheSessionData(session);
                log.debug("Session {} retrieved from database and cached", sessionId);
            }
        }

        return sessionOpt;
    }

    /**
     * קבלת כל השיחות הפעילות של משתמש עם caching
     */
    public List<ChatSession> getUserSessions(User user) {
        validateUser(user);

        String cacheKey = "user_sessions:" + user.getId();

        @SuppressWarnings("unchecked")
        List<ChatSession> cachedSessions = (List<ChatSession>) cacheService.get(cacheKey);

        if (cachedSessions != null) {
            log.debug("User {} sessions retrieved from cache", user.getId());
            return cachedSessions;
        }

        // Get from database - only active sessions
        List<ChatSession> sessions = chatSessionRepository.findByUserAndActiveTrueOrderByUpdatedAtDesc(user);

        // Cache the result for 15 minutes
        cacheService.set(cacheKey, sessions, java.time.Duration.ofMinutes(15));
        log.debug("User {} sessions retrieved from database and cached ({} sessions)",
                user.getId(), sessions.size());

        return sessions;
    }

    /**
     * קבלת כל השיחות (כולל לא פעילות) עם סינון
     */
    public List<ChatSession> getAllUserSessions(User user, boolean includeInactive) {
        validateUser(user);

        if (!includeInactive) {
            return getUserSessions(user); // Use cached active sessions
        }

        String cacheKey = "user_all_sessions:" + user.getId();

        @SuppressWarnings("unchecked")
        List<ChatSession> cachedSessions = (List<ChatSession>) cacheService.get(cacheKey);

        if (cachedSessions != null) {
            log.debug("User {} all sessions retrieved from cache", user.getId());
            return cachedSessions;
        }

        // Get all sessions from database
        List<ChatSession> sessions = chatSessionRepository.findByUserOrderByUpdatedAtDesc(user);

        // Cache for shorter time since this includes inactive sessions
        cacheService.set(cacheKey, sessions, java.time.Duration.ofMinutes(10));
        log.debug("User {} all sessions retrieved from database and cached ({} sessions)",
                user.getId(), sessions.size());

        return sessions;
    }

    /**
     * קבלת השיחה האחרונה של משתמש עם אופטימיזציה
     */
    public Optional<ChatSession> getLastSession(User user) {
        validateUser(user);

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
     * עדכון פרטי שיחה עם validation מלא
     */
    @Transactional
    public ChatSession updateSession(Long sessionId, String title, String description, User user) {
        validateUser(user);
        validateTitle(title);

        Optional<ChatSession> sessionOpt = findById(sessionId);

        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("שיחה לא נמצאה");
        }

        ChatSession session = sessionOpt.get();

        // בדיקת הרשאות
        if (!session.getUser().getId().equals(user.getId())) {
            throw new SecurityException("אין הרשאה לעדכן שיחה זו");
        }

        if (!session.getActive()) {
            throw new IllegalArgumentException("לא ניתן לעדכן שיחה לא פעילה");
        }

        String oldTitle = session.getTitle();
        session.setTitle(title.trim());
        session.setDescription(description != null ? description.trim() : null);

        ChatSession updatedSession = chatSessionRepository.save(session);

        // Update cache
        cacheSessionData(updatedSession);

        // Invalidate user sessions cache as the list has changed
        invalidateUserSessionsCache(user.getId());

        log.info("שיחה {} עודכנה: '{}' -> '{}' עבור משתמש {}",
                sessionId, oldTitle, title, user.getUsername());
        return updatedSession;
    }

    /**
     * מחיקת שיחה (soft delete) עם ניקיון cache
     */
    @Transactional
    public boolean deleteSession(Long sessionId, User user) {
        validateUser(user);

        Optional<ChatSession> sessionOpt = findById(sessionId);

        if (sessionOpt.isEmpty()) {
            log.warn("ניסיון מחיקת שיחה לא קיימת: {} עבור משתמש {}", sessionId, user.getId());
            return false;
        }

        ChatSession session = sessionOpt.get();

        // בדיקת הרשאות
        if (!session.getUser().getId().equals(user.getId())) {
            log.warn("משתמש {} מנסה למחוק שיחה {} של משתמש אחר",
                    user.getId(), sessionId);
            return false;
        }

        if (!session.getActive()) {
            log.info("שיחה {} כבר לא פעילה", sessionId);
            return true; // Already deleted
        }

        String sessionTitle = session.getTitle();
        session.setActive(false);
        ChatSession deletedSession = chatSessionRepository.save(session);

        // Remove from cache
        cacheService.invalidateSession("session:" + sessionId);

        // Invalidate related caches
        invalidateUserSessionsCache(user.getId());
        cacheService.delete("last_session:" + user.getId());

        log.info("שיחה {} ('{}') נמחקה בהצלחה עבור משתמש {}",
                sessionId, sessionTitle, user.getUsername());
        return true;
    }

    /**
     * עדכון זמן פעילות אחרונה עם batch updates
     */
    @Transactional
    public void updateLastActivity(Long sessionId) {
        if (sessionId == null || sessionId <= 0) {
            return;
        }

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

            log.debug("עודכן זמן פעילות אחרונה לשיחה {}", sessionId);
        }
    }

    /**
     * ספירת שיחות פעילות של משתמש עם caching
     */
    public long countUserSessions(User user) {
        validateUser(user);

        String cacheKey = "user_sessions_count:" + user.getId();
        Object cachedCount = cacheService.get(cacheKey);

        if (cachedCount instanceof Number) {
            log.debug("Session count for user {} retrieved from cache", user.getId());
            return ((Number) cachedCount).longValue();
        }

        long count = chatSessionRepository.countByUserAndActiveTrue(user);

        // Cache for 10 minutes
        cacheService.set(cacheKey, count, java.time.Duration.ofMinutes(10));
        log.debug("Session count for user {} retrieved from database and cached: {}", user.getId(), count);

        return count;
    }

    /**
     * בדיקה אם השיחה שייכת למשתמש עם validation מלא
     */
    public boolean isSessionOwnedByUser(Long sessionId, User user) {
        if (sessionId == null || user == null) {
            return false;
        }

        Optional<ChatSession> sessionOpt = findById(sessionId);
        return sessionOpt.isPresent() &&
                sessionOpt.get().getUser().getId().equals(user.getId()) &&
                sessionOpt.get().getActive() &&
                user.getActive();
    }

    /**
     * חיפוש שיחות לפי כותרת עם caching
     */
    public List<ChatSession> searchSessions(User user, String searchTerm) {
        validateUser(user);

        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return List.of();
        }

        String normalizedSearchTerm = searchTerm.trim().toLowerCase();
        String cacheKey = "search_sessions:" + user.getId() + ":" + normalizedSearchTerm.hashCode();

        @SuppressWarnings("unchecked")
        List<ChatSession> cachedResults = (List<ChatSession>) cacheService.get(cacheKey);

        if (cachedResults != null) {
            log.debug("Search results for user {} retrieved from cache", user.getId());
            return cachedResults;
        }

        List<ChatSession> searchResults = chatSessionRepository.findByUserAndTitleContaining(user, normalizedSearchTerm);

        // Only return active sessions
        searchResults = searchResults.stream()
                .filter(ChatSession::getActive)
                .toList();

        // Cache search results for shorter time
        cacheService.set(cacheKey, searchResults, java.time.Duration.ofMinutes(5));
        log.debug("Search results for user {} retrieved from database and cached: {} results",
                user.getId(), searchResults.size());

        return searchResults;
    }

    /**
     * קביעת שיחה כפעילה למשתמש עם שמירת השיחה הקודמת
     */
    public void setActiveSession(User user, ChatSession session) {
        validateUser(user);

        if (session == null || !isSessionOwnedByUser(session.getId(), user)) {
            throw new IllegalArgumentException("שיחה לא תקינה או שאין הרשאה");
        }

        String cacheKey = "active_session:" + user.getId();

        // שמירת השיחה הנוכחית כקודמת לפני החלפה
        ChatSession currentActive = (ChatSession) cacheService.get(cacheKey);
        if (currentActive != null && !currentActive.getId().equals(session.getId())) {
            String previousSessionKey = "previous_session:" + user.getId();
            cacheService.set(previousSessionKey, currentActive.getId(), java.time.Duration.ofHours(1));
        }

        // קביעת השיחה החדשה כפעילה
        cacheService.set(cacheKey, session, java.time.Duration.ofHours(2));

        // עדכון פעילות השיחה
        updateLastActivity(session.getId());

        log.debug("Set active session {} for user {}", session.getId(), user.getId());
    }


    /**
     * קבלת השיחה הפעילה של משתמש
     */
    public Optional<ChatSession> getActiveSession(User user) {
        validateUser(user);

        String cacheKey = "active_session:" + user.getId();
        ChatSession activeSession = (ChatSession) cacheService.get(cacheKey);

        if (activeSession != null) {
            // בדיקה שהשיחה עדיין תקינה
            if (isSessionOwnedByUser(activeSession.getId(), user)) {
                log.debug("Active session {} retrieved from cache for user {}",
                        activeSession.getId(), user.getId());
                return Optional.of(activeSession);
            } else {
                // הסרת שיחה לא תקינה מהcache
                cacheService.delete(cacheKey);
                log.warn("Removed invalid active session from cache for user {}", user.getId());
            }
        }

        // אם אין שיחה פעילה, נסה להחזיר את השיחה האחרונה
        Optional<ChatSession> lastSession = getLastSession(user);
        if (lastSession.isPresent()) {
            setActiveSession(user, lastSession.get());
            return lastSession;
        }

        return Optional.empty();
    }

    /**
     * ניקוי שיחה פעילה
     */
    public void clearActiveSession(User user) {
        validateUser(user);

        String cacheKey = "active_session:" + user.getId();
        cacheService.delete(cacheKey);
        log.debug("Cleared active session for user {}", user.getId());
    }

    /**
     * יצירה או קבלה של שיחה ברירת מחדל למשתמש (משופרת)
     */
    @Transactional
    public ChatSession getOrCreateDefaultSession(User user) {
        validateUser(user);

        // נסה למצוא שיחה פעילה קיימת
        Optional<ChatSession> activeSession = getActiveSession(user);
        if (activeSession.isPresent()) {
            updateLastActivity(activeSession.get().getId());
            return activeSession.get();
        }

        // נסה למצוא את השיחה האחרונה
        Optional<ChatSession> lastSession = getLastSession(user);
        if (lastSession.isPresent()) {
            ChatSession session = lastSession.get();
            setActiveSession(user, session);
            updateLastActivity(session.getId());
            return session;
        }

        // צור שיחה חדשה
        String defaultTitle = "שיחה ראשונה של " + user.getFullName();
        return createSession(user, defaultTitle, "שיחת ברירת מחדל");
    }

    /**
     * ארכוב שיחות ישנות (לתחזוקה)
     */
    @Transactional
    public int archiveOldSessions(User user, int daysOld) {
        validateUser(user);

        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysOld);
        List<ChatSession> oldSessions = chatSessionRepository.findInactiveSessionsBefore(user, cutoffDate);

        int archivedCount = 0;
        for (ChatSession session : oldSessions) {
            if (session.getActive()) {
                session.setActive(false);
                chatSessionRepository.save(session);
                archivedCount++;
            }
        }

        if (archivedCount > 0) {
            // Invalidate caches
            invalidateUserSessionsCache(user.getId());
            log.info("ארוכבו {} שיחות ישנות עבור משתמש {}", archivedCount, user.getUsername());
        }

        return archivedCount;
    }

    /**
     * קבלת השיחה הקודמת של משתמש
     */
    public Optional<ChatSession> getPreviousSession(User user) {
        validateUser(user);

        String previousSessionKey = "previous_session:" + user.getId();
        Long previousSessionId = (Long) cacheService.get(previousSessionKey);

        if (previousSessionId != null) {
            Optional<ChatSession> sessionOpt = findById(previousSessionId);
            if (sessionOpt.isPresent() && isSessionOwnedByUser(previousSessionId, user)) {
                return sessionOpt;
            }
        }

        return Optional.empty();
    }

    // Private helper methods

    private void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("משתמש לא תקין");
        }

        if (!user.getActive()) {
            throw new SecurityException("משתמש לא פעיל");
        }
    }

    private void validateTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("כותרת שיחה היא שדה חובה");
        }

        if (title.trim().length() > 200) {
            throw new IllegalArgumentException("כותרת שיחה ארוכה מדי (מקסימום 200 תווים)");
        }
    }

    private void cacheSessionData(ChatSession session) {
        String cacheKey = "session:" + session.getId();
        cacheService.cacheSessionData(cacheKey, session);
    }

    private void invalidateUserSessionsCache(Long userId) {
        cacheService.delete("user_sessions:" + userId);
        cacheService.delete("user_all_sessions:" + userId);
        cacheService.delete("user_sessions_count:" + userId);
        cacheService.delete("last_session:" + userId);
        log.debug("Invalidated user sessions cache for user {}", userId);
    }
}