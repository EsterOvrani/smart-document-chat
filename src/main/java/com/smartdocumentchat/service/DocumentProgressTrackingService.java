package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.Document;
import com.smartdocumentchat.event.DocumentProcessingStatusEvent;
import com.smartdocumentchat.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProgressTrackingService {

    private final DocumentRepository documentRepository;
    private final CacheService cacheService;

    // Cache למעקב אחר progress בזמן אמת
    private final Map<Long, ProgressInfo> activeProcessing = new ConcurrentHashMap<>();

    /**
     * עדכון progress למסמך
     */
    public void updateProgress(Long documentId, DocumentProcessingStatusEvent.ProcessingStatus status,
                               Integer progressPercentage, String statusMessage) {
        try {
            // עדכון ב-cache
            ProgressInfo progressInfo = activeProcessing.computeIfAbsent(documentId,
                    id -> new ProgressInfo(documentId));

            progressInfo.setStatus(status);
            progressInfo.setProgressPercentage(progressPercentage);
            progressInfo.setStatusMessage(statusMessage);
            progressInfo.setLastUpdated(LocalDateTime.now());

            // עדכון גם ב-Redis למקרה של restart
            String cacheKey = "doc_progress:" + documentId;
            cacheService.set(cacheKey, progressInfo, Duration.ofHours(1));

            // עדכון ב-DB
            updateDocumentInDatabase(documentId, progressPercentage, statusMessage);

            log.debug("Progress updated for document {}: {}% - {}",
                    documentId, progressPercentage, statusMessage);

        } catch (Exception e) {
            log.error("Failed to update progress for document: {}", documentId, e);
        }
    }

    /**
     * קבלת progress נוכחי של מסמך
     */
    public ProgressInfo getProgress(Long documentId) {
        // נסה קודם מה-memory
        ProgressInfo progressInfo = activeProcessing.get(documentId);

        if (progressInfo != null) {
            return progressInfo;
        }

        // נסה מ-cache
        String cacheKey = "doc_progress:" + documentId;
        ProgressInfo cachedProgress = (ProgressInfo) cacheService.get(cacheKey);

        if (cachedProgress != null) {
            return cachedProgress;
        }

        // קבל מה-DB
        Optional<Document> docOpt = documentRepository.findById(documentId);
        if (docOpt.isPresent()) {
            Document doc = docOpt.get();
            progressInfo = new ProgressInfo(documentId);
            progressInfo.setProgressPercentage(doc.getProcessingProgress());
            progressInfo.setStatus(mapDocumentStatus(doc.getProcessingStatus()));
            progressInfo.setStatusMessage(getStatusMessage(doc.getProcessingStatus()));
            progressInfo.setLastUpdated(doc.getUpdatedAt());

            return progressInfo;
        }

        return null;
    }

    /**
     * חישוב אחוז progress מבוסס character count
     */
    public int calculateProgressPercentage(DocumentProcessingStatusEvent.ProcessingStatus status,
                                           Integer characterCount, Integer currentChunk, Integer totalChunks) {
        if (status == null) {
            return 0;
        }

        return switch (status) {
            case RECEIVED -> 0;
            case STARTED -> 5;
            case PARSING -> 20;
            case CHUNKING -> {
                if (characterCount != null && characterCount > 0) {
                    // אומדן מבוסס גודל
                    yield Math.min(40, 30 + (characterCount / 10000));
                }
                yield 35;
            }
            case EMBEDDING -> {
                if (currentChunk != null && totalChunks != null && totalChunks > 0) {
                    // progress מבוסס chunks
                    int chunkProgress = (int) ((currentChunk * 20.0) / totalChunks);
                    yield 50 + chunkProgress;
                }
                yield 60;
            }
            case STORING -> 80;
            case COMPLETED -> 100;
            case FAILED, CANCELLED -> 0;
            default -> 50;
        };
    }

    /**
     * אומדן זמן נותר לעיבוד
     */
    public Duration estimateTimeRemaining(Long documentId) {
        ProgressInfo progressInfo = getProgress(documentId);

        if (progressInfo == null || progressInfo.getProgressPercentage() == 0) {
            return null;
        }

        if (progressInfo.getProgressPercentage() >= 100) {
            return Duration.ZERO;
        }

        // חישוב מבוסס זמן עיבוד עד כה
        Duration elapsed = Duration.between(progressInfo.getStartTime(), LocalDateTime.now());
        long elapsedSeconds = elapsed.getSeconds();

        if (elapsedSeconds == 0) {
            return null;
        }

        // אומדן לינארי פשוט
        int remainingPercentage = 100 - progressInfo.getProgressPercentage();
        long estimatedRemainingSeconds = (elapsedSeconds * remainingPercentage) /
                progressInfo.getProgressPercentage();

        return Duration.ofSeconds(estimatedRemainingSeconds);
    }

    /**
     * קבלת כל המסמכים בעיבוד של משתמש
     */
    public Map<Long, ProgressInfo> getActiveProcessingForUser(Long userId) {
        Map<Long, ProgressInfo> userProcessing = new HashMap<>();

        for (Map.Entry<Long, ProgressInfo> entry : activeProcessing.entrySet()) {
            ProgressInfo info = entry.getValue();
            if (info.getUserId() != null && info.getUserId().equals(userId)) {
                userProcessing.put(entry.getKey(), info);
            }
        }

        return userProcessing;
    }

    /**
     * ניקוי מסמכים שהושלמו
     */
    public void cleanupCompletedDocument(Long documentId) {
        activeProcessing.remove(documentId);
        String cacheKey = "doc_progress:" + documentId;
        cacheService.delete(cacheKey);

        log.debug("Cleaned up progress tracking for document: {}", documentId);
    }

    /**
     * התחלת מעקב חדש
     */
    public void startTracking(Long documentId, Long userId, Long sessionId) {
        ProgressInfo progressInfo = new ProgressInfo(documentId);
        progressInfo.setUserId(userId);
        progressInfo.setSessionId(sessionId);
        progressInfo.setStartTime(LocalDateTime.now());
        progressInfo.setStatus(DocumentProcessingStatusEvent.ProcessingStatus.RECEIVED);
        progressInfo.setProgressPercentage(0);

        activeProcessing.put(documentId, progressInfo);

        String cacheKey = "doc_progress:" + documentId;
        cacheService.set(cacheKey, progressInfo, Duration.ofHours(1));

        log.info("Started progress tracking for document: {}", documentId);
    }

    /**
     * עדכון מסמך בDB
     */
    private void updateDocumentInDatabase(Long documentId, Integer progress, String statusMessage) {
        try {
            Optional<Document> docOpt = documentRepository.findById(documentId);
            if (docOpt.isPresent()) {
                Document doc = docOpt.get();
                if (progress != null) {
                    doc.setProcessingProgress(progress);
                }
                // statusMessage יכול להישמר ב-errorMessage זמנית או בשדה נוסף
                documentRepository.save(doc);
            }
        } catch (Exception e) {
            log.error("Failed to update document in database: {}", documentId, e);
        }
    }

    /**
     * המרת סטטוס Document לסטטוס Event
     */
    private DocumentProcessingStatusEvent.ProcessingStatus mapDocumentStatus(
            Document.ProcessingStatus docStatus) {

        if (docStatus == null) {
            return DocumentProcessingStatusEvent.ProcessingStatus.RECEIVED;
        }

        return switch (docStatus) {
            case PENDING -> DocumentProcessingStatusEvent.ProcessingStatus.RECEIVED;
            case PROCESSING -> DocumentProcessingStatusEvent.ProcessingStatus.IN_PROGRESS;
            case COMPLETED -> DocumentProcessingStatusEvent.ProcessingStatus.COMPLETED;
            case FAILED -> DocumentProcessingStatusEvent.ProcessingStatus.FAILED;
            case CANCELLED -> DocumentProcessingStatusEvent.ProcessingStatus.CANCELLED;
        };
    }

    /**
     * קבלת הודעת סטטוס
     */
    private String getStatusMessage(Document.ProcessingStatus status) {
        if (status == null) {
            return "ממתין לעיבוד";
        }

        return switch (status) {
            case PENDING -> "ממתין בתור לעיבוד";
            case PROCESSING -> "מעבד מסמך";
            case COMPLETED -> "עיבוד הושלם בהצלחה";
            case FAILED -> "עיבוד נכשל";
            case CANCELLED -> "עיבוד בוטל";
        };
    }

    /**
     * Inner class למידע על progress
     */
    public static class ProgressInfo {
        private Long documentId;
        private Long userId;
        private Long sessionId;
        private DocumentProcessingStatusEvent.ProcessingStatus status;
        private Integer progressPercentage;
        private String statusMessage;
        private LocalDateTime startTime;
        private LocalDateTime lastUpdated;

        public ProgressInfo(Long documentId) {
            this.documentId = documentId;
            this.startTime = LocalDateTime.now();
            this.lastUpdated = LocalDateTime.now();
        }

        // Getters and Setters
        public Long getDocumentId() { return documentId; }
        public void setDocumentId(Long documentId) { this.documentId = documentId; }

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }

        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

        public DocumentProcessingStatusEvent.ProcessingStatus getStatus() { return status; }
        public void setStatus(DocumentProcessingStatusEvent.ProcessingStatus status) {
            this.status = status;
        }

        public Integer getProgressPercentage() { return progressPercentage; }
        public void setProgressPercentage(Integer progressPercentage) {
            this.progressPercentage = progressPercentage;
        }

        public String getStatusMessage() { return statusMessage; }
        public void setStatusMessage(String statusMessage) {
            this.statusMessage = statusMessage;
        }

        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

        public LocalDateTime getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(LocalDateTime lastUpdated) {
            this.lastUpdated = lastUpdated;
        }
    }
}