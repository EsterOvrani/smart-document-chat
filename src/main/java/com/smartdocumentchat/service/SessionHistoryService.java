package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * שירות לניהול היסטוריה ומטאדטה של שיחות - גרסה מפושטת
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionHistoryService {

    private final ChatSessionRepository chatSessionRepository;
    private final CacheService cacheService;
    private final PdfProcessingService pdfProcessingService;

    /**
     * עדכון מטאדטה של שיחה
     */
    @Transactional
    public ChatSession updateSessionMetadata(Long sessionId, String title, String description,
                                           Map<String, Object> additionalMetadata, User user) {
        validateUser(user);

        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("שיחה לא נמצאה");
        }

        ChatSession session = sessionOpt.get();

        if (!session.getUser().getId().equals(user.getId())) {
            throw new SecurityException("אין הרשאה לעדכן שיחה זו");
        }

        if (!session.getActive()) {
            throw new IllegalArgumentException("לא ניתן לעדכן שיחה לא פעילה");
        }

        if (title != null && !title.trim().isEmpty()) {
            session.setTitle(title.trim());
        }

        if (description != null) {
            session.setDescription(description.trim().isEmpty() ? null : description.trim());
        }

        ChatSession updatedSession = chatSessionRepository.save(session);

        if (additionalMetadata != null && !additionalMetadata.isEmpty()) {
            String metadataKey = "session_metadata:" + sessionId;
            cacheService.set(metadataKey, additionalMetadata, java.time.Duration.ofDays(7));
        }

        invalidateSessionCaches(sessionId, user.getId());

        log.info("מטאדטה של שיחה {} עודכנה עבור משתמש {}", sessionId, user.getUsername());
        return updatedSession;
    }

    /**
     * קבלת היסטוריית פעילות של שיחה
     */
    public SessionActivityHistory getSessionActivityHistory(Long sessionId, User user) {
        validateUser(user);

        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("שיחה לא נמצאה");
        }

        ChatSession session = sessionOpt.get();

        if (!session.getUser().getId().equals(user.getId())) {
            throw new SecurityException("אין הרשאה לצפות בשיחה זו");
        }

        String cacheKey = "session_activity:" + sessionId;
        SessionActivityHistory cachedHistory = (SessionActivityHistory) cacheService.get(cacheKey);

        if (cachedHistory != null) {
            log.debug("Session activity history for session {} retrieved from cache", sessionId);
            return cachedHistory;
        }

        // חישוב נתוני פעילות ללא ChronoUnit
        LocalDateTime createdAt = session.getCreatedAt();
        LocalDateTime lastActivity = session.getLastActivityAt() != null ?
                session.getLastActivityAt() : session.getCreatedAt();

        long totalDaysActive = calculateDaysBetween(createdAt, LocalDateTime.now());
        long daysSinceLastActivity = calculateDaysBetween(lastActivity, LocalDateTime.now());
        long totalMessages = session.getMessageCount();
        long totalDocuments = session.getDocumentCount();

        PdfProcessingService.SessionDocumentStats docStats =
                pdfProcessingService.getSessionDocumentStats(session);

        SessionActivityHistory history = new SessionActivityHistory(
                sessionId,
                session.getDisplayTitle(),
                createdAt,
                lastActivity,
                session.getUpdatedAt() != null ? session.getUpdatedAt() : createdAt,
                totalDaysActive,
                daysSinceLastActivity,
                totalMessages,
                totalDocuments,
                docStats.completedDocuments,
                docStats.failedDocuments,
                docStats.totalCharacters,
                docStats.totalChunks,
                calculateActivityScore(session, docStats),
                getActivityLevel(daysSinceLastActivity)
        );

        cacheService.set(cacheKey, history, java.time.Duration.ofHours(2));
        log.debug("Session activity history for session {} calculated and cached", sessionId);

        return history;
    }

    /**
     * קבלת היסטוריה של כל השיחות של משתמש
     */
    public List<SessionSummary> getUserSessionsHistory(User user, int limit, String sortBy) {
        validateUser(user);

        String cacheKey = "user_sessions_history:" + user.getId() + "_" + limit + "_" + sortBy;

        @SuppressWarnings("unchecked")
        List<SessionSummary> cachedHistory = (List<SessionSummary>) cacheService.get(cacheKey);

        if (cachedHistory != null) {
            log.debug("User sessions history for user {} retrieved from cache", user.getId());
            return cachedHistory;
        }

        List<ChatSession> allSessions = chatSessionRepository.findByUserOrderByUpdatedAtDesc(user);

        List<SessionSummary> sessionSummaries = allSessions.stream()
                .map(session -> createSessionSummary(session, user))
                .collect(Collectors.toList());

        sessionSummaries = sortSessionSummaries(sessionSummaries, sortBy);

        if (limit > 0 && sessionSummaries.size() > limit) {
            sessionSummaries = sessionSummaries.subList(0, limit);
        }

        cacheService.set(cacheKey, sessionSummaries, java.time.Duration.ofMinutes(30));
        log.debug("User sessions history for user {} calculated and cached ({} sessions)",
                user.getId(), sessionSummaries.size());

        return sessionSummaries;
    }

    /**
     * חיפוש בהיסטוריית השיחות
     */
    public List<SessionSummary> searchSessionsHistory(User user, String searchTerm,
                                                     LocalDateTime fromDate, LocalDateTime toDate) {
        validateUser(user);

        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedSearchTerm = searchTerm.trim().toLowerCase();

        List<ChatSession> allSessions;

        if (fromDate != null && toDate != null) {
            allSessions = chatSessionRepository.findByUserAndCreatedAtBetweenOrderByCreatedAtDesc(
                    user, fromDate, toDate);
        } else {
            allSessions = chatSessionRepository.findByUserOrderByUpdatedAtDesc(user);
        }

        List<SessionSummary> searchResults = allSessions.stream()
                .filter(session -> sessionMatchesSearchTerm(session, normalizedSearchTerm))
                .map(session -> createSessionSummary(session, user))
                .sorted((s1, s2) -> s2.lastActivity.compareTo(s1.lastActivity))
                .collect(Collectors.toList());

        return searchResults;
    }

    /**
     * קבלת סטטיסטיקות כלליות לכל השיחות של משתמש
     */
    public UserSessionsStats getUserSessionsStats(User user) {
        validateUser(user);

        String cacheKey = "user_sessions_stats:" + user.getId();
        UserSessionsStats cachedStats = (UserSessionsStats) cacheService.get(cacheKey);

        if (cachedStats != null) {
            log.debug("User sessions stats for user {} retrieved from cache", user.getId());
            return cachedStats;
        }

        List<ChatSession> allSessions = chatSessionRepository.findByUserOrderByUpdatedAtDesc(user);

        long totalSessions = allSessions.size();
        long activeSessions = allSessions.stream().filter(ChatSession::getActive).count();
        long inactiveSessions = totalSessions - activeSessions;

        long totalDocuments = allSessions.stream()
                .mapToLong(ChatSession::getDocumentCount)
                .sum();

        long totalMessages = allSessions.stream()
                .mapToLong(ChatSession::getMessageCount)
                .sum();

        Optional<LocalDateTime> lastActivity = allSessions.stream()
                .map(ChatSession::getLastActivityAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo);

        Optional<LocalDateTime> firstSession = allSessions.stream()
                .map(ChatSession::getCreatedAt)
                .min(LocalDateTime::compareTo);

        long totalActiveDays = 0;
        if (firstSession.isPresent()) {
            totalActiveDays = calculateDaysBetween(firstSession.get(), LocalDateTime.now());
        }

        double avgDocumentsPerSession = totalSessions > 0 ?
                (double) totalDocuments / totalSessions : 0.0;
        double avgMessagesPerSession = totalSessions > 0 ?
                (double) totalMessages / totalSessions : 0.0;

        UserSessionsStats stats = new UserSessionsStats(
                totalSessions,
                activeSessions,
                inactiveSessions,
                totalDocuments,
                totalMessages,
                avgDocumentsPerSession,
                avgMessagesPerSession,
                totalActiveDays,
                lastActivity.orElse(null),
                firstSession.orElse(null)
        );

        cacheService.set(cacheKey, stats, java.time.Duration.ofHours(1));
        log.debug("User sessions stats for user {} calculated and cached", user.getId());

        return stats;
    }

    // Helper methods - ללא ChronoUnit

    /**
     * חישוב ימים בין שני תאריכים ללא ChronoUnit
     */
    private long calculateDaysBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }

        // פתרון פשוט - ממירים למילישניות וחולקים
        long startMillis = start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endMillis = end.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();

        long diffMillis = endMillis - startMillis;
        long diffDays = diffMillis / (24 * 60 * 60 * 1000);

        return Math.max(0, diffDays);
    }

    private SessionSummary createSessionSummary(ChatSession session, User user) {
        PdfProcessingService.SessionDocumentStats docStats =
                pdfProcessingService.getSessionDocumentStats(session);

        return new SessionSummary(
                session.getId(),
                session.getDisplayTitle(),
                session.getDescription(),
                session.getCreatedAt(),
                session.getLastActivityAt() != null ? session.getLastActivityAt() : session.getCreatedAt(),
                session.getUpdatedAt(),
                session.getActive(),
                session.getMessageCount(),
                session.getDocumentCount(),
                (int) docStats.completedDocuments,
                (int) docStats.failedDocuments,
                docStats.totalCharacters,
                docStats.totalChunks,
                calculateActivityScore(session, docStats)
        );
    }

    private int calculateActivityScore(ChatSession session, PdfProcessingService.SessionDocumentStats docStats) {
        int score = 0;

        score += Math.min(session.getMessageCount() * 2, 50);
        score += Math.min(session.getDocumentCount() * 10, 30);

        LocalDateTime lastActivity = session.getLastActivityAt() != null ?
                session.getLastActivityAt() : session.getCreatedAt();
        long daysSinceActivity = calculateDaysBetween(lastActivity, LocalDateTime.now());

        if (daysSinceActivity == 0) {
            score += 20;
        } else if (daysSinceActivity <= 7) {
            score += 10;
        } else if (daysSinceActivity <= 30) {
            score += 5;
        }

        return Math.min(score, 100);
    }

    private String getActivityLevel(long daysSinceLastActivity) {
        if (daysSinceLastActivity == 0) {
            return "פעיל היום";
        } else if (daysSinceLastActivity <= 7) {
            return "פעיל השבוע";
        } else if (daysSinceLastActivity <= 30) {
            return "פעיל החודש";
        } else if (daysSinceLastActivity <= 90) {
            return "פעיל ברבעון";
        } else {
            return "לא פעיל";
        }
    }

    private boolean sessionMatchesSearchTerm(ChatSession session, String searchTerm) {
        if (session.getTitle() != null &&
            session.getTitle().toLowerCase().contains(searchTerm)) {
            return true;
        }

        if (session.getDescription() != null &&
            session.getDescription().toLowerCase().contains(searchTerm)) {
            return true;
        }

        return false;
    }

    private List<SessionSummary> sortSessionSummaries(List<SessionSummary> summaries, String sortBy) {
        if ("created".equalsIgnoreCase(sortBy)) {
            return summaries.stream()
                    .sorted((s1, s2) -> s2.createdAt.compareTo(s1.createdAt))
                    .collect(Collectors.toList());
        } else if ("title".equalsIgnoreCase(sortBy)) {
            return summaries.stream()
                    .sorted((s1, s2) -> s1.title.compareToIgnoreCase(s2.title))
                    .collect(Collectors.toList());
        } else if ("activity".equalsIgnoreCase(sortBy)) {
            return summaries.stream()
                    .sorted((s1, s2) -> Integer.compare(s2.activityScore, s1.activityScore))
                    .collect(Collectors.toList());
        } else if ("documents".equalsIgnoreCase(sortBy)) {
            return summaries.stream()
                    .sorted((s1, s2) -> Integer.compare(s2.totalDocuments, s1.totalDocuments))
                    .collect(Collectors.toList());
        } else if ("messages".equalsIgnoreCase(sortBy)) {
            return summaries.stream()
                    .sorted((s1, s2) -> Integer.compare(s2.totalMessages, s1.totalMessages))
                    .collect(Collectors.toList());
        } else { // "updated" - default
            return summaries.stream()
                    .sorted((s1, s2) -> {
                        LocalDateTime updated1 = s1.updatedAt != null ? s1.updatedAt : s1.createdAt;
                        LocalDateTime updated2 = s2.updatedAt != null ? s2.updatedAt : s2.createdAt;
                        return updated2.compareTo(updated1);
                    })
                    .collect(Collectors.toList());
        }
    }

    private void invalidateSessionCaches(Long sessionId, Long userId) {
        cacheService.delete("session_activity:" + sessionId);
        cacheService.delete("user_sessions_history:" + userId);
        cacheService.delete("user_sessions_stats:" + userId);
        log.debug("Invalidated session history caches for session {} and user {}", sessionId, userId);
    }

    private void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("משתמש לא תקין");
        }

        if (!user.getActive()) {
            throw new SecurityException("משתמש לא פעיל");
        }
    }

    // Inner classes for response objects

    public static class SessionActivityHistory {
        public final Long sessionId;
        public final String title;
        public final LocalDateTime createdAt;
        public final LocalDateTime lastActivity;
        public final LocalDateTime updatedAt;
        public final long totalDaysActive;
        public final long daysSinceLastActivity;
        public final long totalMessages;
        public final long totalDocuments;
        public final long completedDocuments;
        public final long failedDocuments;
        public final int totalCharacters;
        public final int totalChunks;
        public final int activityScore;
        public final String activityLevel;

        public SessionActivityHistory(Long sessionId, String title, LocalDateTime createdAt,
                                    LocalDateTime lastActivity, LocalDateTime updatedAt,
                                    long totalDaysActive, long daysSinceLastActivity,
                                    long totalMessages, long totalDocuments,
                                    long completedDocuments, long failedDocuments,
                                    int totalCharacters, int totalChunks,
                                    int activityScore, String activityLevel) {
            this.sessionId = sessionId;
            this.title = title;
            this.createdAt = createdAt;
            this.lastActivity = lastActivity;
            this.updatedAt = updatedAt;
            this.totalDaysActive = totalDaysActive;
            this.daysSinceLastActivity = daysSinceLastActivity;
            this.totalMessages = totalMessages;
            this.totalDocuments = totalDocuments;
            this.completedDocuments = completedDocuments;
            this.failedDocuments = failedDocuments;
            this.totalCharacters = totalCharacters;
            this.totalChunks = totalChunks;
            this.activityScore = activityScore;
            this.activityLevel = activityLevel;
        }
    }

    public static class SessionSummary {
        public final Long sessionId;
        public final String title;
        public final String description;
        public final LocalDateTime createdAt;
        public final LocalDateTime lastActivity;
        public final LocalDateTime updatedAt;
        public final boolean active;
        public final int totalMessages;
        public final int totalDocuments;
        public final int completedDocuments;
        public final int failedDocuments;
        public final int totalCharacters;
        public final int totalChunks;
        public final int activityScore;

        public SessionSummary(Long sessionId, String title, String description,
                            LocalDateTime createdAt, LocalDateTime lastActivity,
                            LocalDateTime updatedAt, boolean active,
                            int totalMessages, int totalDocuments,
                            int completedDocuments, int failedDocuments,
                            int totalCharacters, int totalChunks, int activityScore) {
            this.sessionId = sessionId;
            this.title = title;
            this.description = description;
            this.createdAt = createdAt;
            this.lastActivity = lastActivity;
            this.updatedAt = updatedAt;
            this.active = active;
            this.totalMessages = totalMessages;
            this.totalDocuments = totalDocuments;
            this.completedDocuments = completedDocuments;
            this.failedDocuments = failedDocuments;
            this.totalCharacters = totalCharacters;
            this.totalChunks = totalChunks;
            this.activityScore = activityScore;
        }
    }

    public static class UserSessionsStats {
        public final long totalSessions;
        public final long activeSessions;
        public final long inactiveSessions;
        public final long totalDocuments;
        public final long totalMessages;
        public final double avgDocumentsPerSession;
        public final double avgMessagesPerSession;
        public final long totalActiveDays;
        public final LocalDateTime lastActivity;
        public final LocalDateTime firstSession;

        public UserSessionsStats(long totalSessions, long activeSessions, long inactiveSessions,
                               long totalDocuments, long totalMessages,
                               double avgDocumentsPerSession, double avgMessagesPerSession,
                               long totalActiveDays, LocalDateTime lastActivity,
                               LocalDateTime firstSession) {
            this.totalSessions = totalSessions;
            this.activeSessions = activeSessions;
            this.inactiveSessions = inactiveSessions;
            this.totalDocuments = totalDocuments;
            this.totalMessages = totalMessages;
            this.avgDocumentsPerSession = avgDocumentsPerSession;
            this.avgMessagesPerSession = avgMessagesPerSession;
            this.totalActiveDays = totalActiveDays;
            this.lastActivity = lastActivity;
            this.firstSession = firstSession;
        }
    }
}