package com.smartdocumentchat.util;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.Document;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.CacheService;
import com.smartdocumentchat.service.ChatSessionService;
import com.smartdocumentchat.service.PdfProcessingService;
import com.smartdocumentchat.service.QdrantVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * שירות עזר לניהול collections של שיחות ב-Qdrant
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionCollectionManager {

    private final QdrantVectorService qdrantVectorService;
    private final PdfProcessingService pdfProcessingService;
    private final ChatSessionService chatSessionService;
    private final CacheService cacheService;

    /**
     * אתחול collection לשיחה חדשה
     */
    public void initializeSessionCollection(ChatSession chatSession) {
        try {
            String collectionName = qdrantVectorService.generateSessionCollectionName(
                    chatSession.getId(), chatSession.getUser().getId());

            log.info("Initializing collection: {} for session: {} (user: {})",
                    collectionName, chatSession.getId(), chatSession.getUser().getId());

            // יצירת embedding store לשיחה (יווצר באופן lazy)
            qdrantVectorService.getEmbeddingStoreForSession(chatSession);

            log.info("Collection initialized successfully for session: {}", chatSession.getId());

        } catch (Exception e) {
            log.error("Failed to initialize collection for session: {}", chatSession.getId(), e);
            throw new RuntimeException("Failed to initialize session collection", e);
        }
    }

    /**
     * ניקוי collection של שיחה שנמחקה
     */
    public void cleanupSessionCollection(ChatSession chatSession) {
        try {
            Long sessionId = chatSession.getId();
            Long userId = chatSession.getUser().getId();

            String collectionName = qdrantVectorService.generateSessionCollectionName(sessionId, userId);

            log.info("Cleaning up collection: {} for deleted session: {} (user: {})",
                    collectionName, sessionId, userId);

            // הסרה מהcache
            qdrantVectorService.removeEmbeddingStoreForSession(sessionId, userId);

            // ניקוי cache קשור
            String cacheKey = "session_docs:" + sessionId + "_user:" + userId;
            cacheService.delete(cacheKey);

            log.info("Collection cleanup completed for session: {}", sessionId);

        } catch (Exception e) {
            log.error("Failed to cleanup collection for session: {}", chatSession.getId(), e);
            // לא זורקים exception כי זה cleanup
        }
    }

    /**
     * מעבר של מסמכים בין collections (כשמעבירים מסמך בין שיחות)
     */
    public boolean transferDocumentBetweenSessions(Document document,
                                                   ChatSession fromSession,
                                                   ChatSession toSession) {
        try {
            log.info("Transferring document: {} from session: {} to session: {}",
                    document.getId(), fromSession.getId(), toSession.getId());

            // זה יידרש להכמת LangChain4j מתקדמת יותר לmigration של vectors
            // לעת עתה נבצע רק עדכון metadata ונניח שהמסמך יעובד מחדש

            String oldCollectionName = qdrantVectorService.generateSessionCollectionName(
                    fromSession.getId(), fromSession.getUser().getId());
            String newCollectionName = qdrantVectorService.generateSessionCollectionName(
                    toSession.getId(), toSession.getUser().getId());

            log.info("Document transfer would move from collection: {} to collection: {}",
                    oldCollectionName, newCollectionName);

            // עדכון בentity
            document.setChatSession(toSession);
            document.setVectorCollectionName(newCollectionName);

            // ניקוי cache עבור שתי השיחות
            invalidateSessionCache(fromSession.getId(), fromSession.getUser().getId());
            invalidateSessionCache(toSession.getId(), toSession.getUser().getId());

            log.info("Document transfer completed successfully");
            return true;

        } catch (Exception e) {
            log.error("Failed to transfer document between sessions", e);
            return false;
        }
    }

    /**
     * קבלת סטטיסטיקות collection עבור שיחה
     */
    public SessionCollectionStats getSessionCollectionStats(ChatSession chatSession) {
        try {
            String collectionName = qdrantVectorService.generateSessionCollectionName(
                    chatSession.getId(), chatSession.getUser().getId());

            boolean hasEmbeddingStore = qdrantVectorService.hasEmbeddingStoreForSession(
                    chatSession.getId(), chatSession.getUser().getId());

            List<Document> documents = pdfProcessingService.getDocumentsBySession(chatSession);

            long totalDocuments = documents.size();
            long processedDocuments = documents.stream().filter(Document::isProcessed).count();
            long failedDocuments = documents.stream().filter(Document::hasFailed).count();
            long processingDocuments = documents.stream().filter(Document::isProcessing).count();

            int totalCharacters = documents.stream()
                    .filter(Document::isProcessed)
                    .mapToInt(doc -> doc.getCharacterCount() != null ? doc.getCharacterCount() : 0)
                    .sum();

            int totalChunks = documents.stream()
                    .filter(Document::isProcessed)
                    .mapToInt(doc -> doc.getChunkCount() != null ? doc.getChunkCount() : 0)
                    .sum();

            return new SessionCollectionStats(
                    collectionName,
                    hasEmbeddingStore,
                    totalDocuments,
                    processedDocuments,
                    failedDocuments,
                    processingDocuments,
                    totalCharacters,
                    totalChunks,
                    chatSession.getLastActivityAt()
            );

        } catch (Exception e) {
            log.error("Failed to get collection stats for session: {}", chatSession.getId(), e);
            return new SessionCollectionStats(
                    "unknown", false, 0, 0, 0, 0, 0, 0, null
            );
        }
    }

    /**
     * קבלת סטטיסטיקות collections עבור כל השיחות של משתמש
     */
    public UserCollectionsStats getUserCollectionsStats(User user) {
        try {
            List<ChatSession> userSessions = chatSessionService.getUserSessions(user);

            int totalSessions = userSessions.size();
            int activeCollections = 0;
            int totalDocuments = 0;
            int totalProcessedDocuments = 0;
            int totalFailedDocuments = 0;

            Map<String, SessionCollectionStats> sessionStats = userSessions.stream()
                    .collect(Collectors.toMap(
                            session -> session.getId().toString(),
                            this::getSessionCollectionStats
                    ));

            for (SessionCollectionStats stats : sessionStats.values()) {
                if (stats.hasEmbeddingStore) {
                    activeCollections++;
                }
                totalDocuments += stats.totalDocuments;
                totalProcessedDocuments += stats.processedDocuments;
                totalFailedDocuments += stats.failedDocuments;
            }

            return new UserCollectionsStats(
                    user.getId(),
                    totalSessions,
                    activeCollections,
                    totalDocuments,
                    totalProcessedDocuments,
                    totalFailedDocuments,
                    sessionStats
            );

        } catch (Exception e) {
            log.error("Failed to get user collections stats for user: {}", user.getId(), e);
            return new UserCollectionsStats(
                    user.getId(), 0, 0, 0, 0, 0, Map.of()
            );
        }
    }

    /**
     * ניקוי collections ישנים (תחזוקה)
     */
    public int cleanupOldCollections(int daysOld) {
        try {
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysOld);
            int cleanedCount = 0;

            // קבלת כל השמות של collections פעילים
            var activeCollections = qdrantVectorService.getActiveCollectionNames();

            for (String collectionName : activeCollections) {
                // ניתוח שם הcollection לקבלת session ID
                if (collectionName.startsWith("session_")) {
                    try {
                        String[] parts = collectionName.split("_");
                        if (parts.length >= 4) { // session_X_user_Y
                            Long sessionId = Long.parseLong(parts[1]);
                            Long userId = Long.parseLong(parts[3]);

                            // בדיקה אם השיחה ישנה
                            var sessionOpt = chatSessionService.findById(sessionId);
                            if (sessionOpt.isEmpty() ||
                                    !sessionOpt.get().getActive() ||
                                    (sessionOpt.get().getLastActivityAt() != null &&
                                            sessionOpt.get().getLastActivityAt().isBefore(cutoffDate))) {

                                // ניקוי הcollection
                                qdrantVectorService.removeEmbeddingStoreForSession(sessionId, userId);
                                cleanedCount++;

                                log.info("Cleaned up old collection: {}", collectionName);
                            }
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Invalid collection name format: {}", collectionName);
                    }
                }
            }

            log.info("Cleanup completed: {} old collections removed", cleanedCount);
            return cleanedCount;

        } catch (Exception e) {
            log.error("Failed to cleanup old collections", e);
            return 0;
        }
    }

    /**
     * בדיקת תקינות collection
     */
    public boolean validateSessionCollection(ChatSession chatSession) {
        try {
            String expectedCollectionName = qdrantVectorService.generateSessionCollectionName(
                    chatSession.getId(), chatSession.getUser().getId());

            boolean hasEmbeddingStore = qdrantVectorService.hasEmbeddingStoreForSession(
                    chatSession.getId(), chatSession.getUser().getId());

            List<Document> documents = pdfProcessingService.getDocumentsBySession(chatSession);
            boolean hasProcessedDocuments = documents.stream().anyMatch(Document::isProcessed);

            // Collection תקין אם יש לו embedding store או שיש מסמכים מעובדים
            boolean isValid = !hasProcessedDocuments || hasEmbeddingStore;

            if (!isValid) {
                log.warn("Invalid collection state for session: {} - has processed documents but no embedding store",
                        chatSession.getId());
            }

            return isValid;

        } catch (Exception e) {
            log.error("Failed to validate session collection: {}", chatSession.getId(), e);
            return false;
        }
    }

    // Helper methods

    private void invalidateSessionCache(Long sessionId, Long userId) {
        String cacheKey = "session_docs:" + sessionId + "_user:" + userId;
        cacheService.delete(cacheKey);
        log.debug("Invalidated session cache for session: {} and user: {}", sessionId, userId);
    }

    // Inner classes for statistics

    public static class SessionCollectionStats {
        public final String collectionName;
        public final boolean hasEmbeddingStore;
        public final long totalDocuments;
        public final long processedDocuments;
        public final long failedDocuments;
        public final long processingDocuments;
        public final int totalCharacters;
        public final int totalChunks;
        public final LocalDateTime lastActivity;

        public SessionCollectionStats(String collectionName, boolean hasEmbeddingStore,
                                      long totalDocuments, long processedDocuments,
                                      long failedDocuments, long processingDocuments,
                                      int totalCharacters, int totalChunks,
                                      LocalDateTime lastActivity) {
            this.collectionName = collectionName;
            this.hasEmbeddingStore = hasEmbeddingStore;
            this.totalDocuments = totalDocuments;
            this.processedDocuments = processedDocuments;
            this.failedDocuments = failedDocuments;
            this.processingDocuments = processingDocuments;
            this.totalCharacters = totalCharacters;
            this.totalChunks = totalChunks;
            this.lastActivity = lastActivity;
        }
    }

    public static class UserCollectionsStats {
        public final Long userId;
        public final int totalSessions;
        public final int activeCollections;
        public final long totalDocuments;
        public final long totalProcessedDocuments;
        public final long totalFailedDocuments;
        public final Map<String, SessionCollectionStats> sessionStats;

        public UserCollectionsStats(Long userId, int totalSessions, int activeCollections,
                                    long totalDocuments, long totalProcessedDocuments,
                                    long totalFailedDocuments,
                                    Map<String, SessionCollectionStats> sessionStats) {
            this.userId = userId;
            this.totalSessions = totalSessions;
            this.activeCollections = activeCollections;
            this.totalDocuments = totalDocuments;
            this.totalProcessedDocuments = totalProcessedDocuments;
            this.totalFailedDocuments = totalFailedDocuments;
            this.sessionStats = sessionStats;
        }
    }
}
