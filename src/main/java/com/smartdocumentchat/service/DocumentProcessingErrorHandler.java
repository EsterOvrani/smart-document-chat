package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.Document;
import com.smartdocumentchat.event.DocumentProcessingEvent;
import com.smartdocumentchat.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingErrorHandler {

    private final DocumentRepository documentRepository;
    private final KafkaEventProducerService kafkaEventProducerService;
    private final CacheService cacheService;

    @Value("${document.processing.max-retries:3}")
    private int maxRetries;

    @Value("${document.processing.retry-delay-seconds:5}")
    private int retryDelaySeconds;

    // מעקב אחר מספר ניסיונות
    private final Map<Long, RetryInfo> retryTracker = new ConcurrentHashMap<>();

    /**
     * טיפול בשגיאת עיבוד
     */
    public void handleProcessingError(DocumentProcessingEvent event, Exception error, String correlationId) {
        Long documentId = event.getDocumentId();

        log.error("Processing error for document {}: {}", documentId, error.getMessage(), error);

        // קבלת מידע על ניסיונות קודמים
        RetryInfo retryInfo = retryTracker.computeIfAbsent(documentId, id -> new RetryInfo(documentId));
        retryInfo.incrementAttempts();
        retryInfo.setLastError(error.getMessage());
        retryInfo.setLastAttempt(LocalDateTime.now());

        // בדיקה אם צריך לנסות שוב
        if (shouldRetry(retryInfo, error)) {
            handleRetry(event, retryInfo, correlationId);
        } else {
            handleFinalFailure(event, retryInfo, error, correlationId);
        }
    }

    /**
     * בדיקה אם צריך לנסות שוב
     */
    private boolean shouldRetry(RetryInfo retryInfo, Exception error) {
        // לא לנסות שוב אם הגענו למקסימום
        if (retryInfo.getAttempts() >= maxRetries) {
            log.warn("Max retries ({}) reached for document: {}", maxRetries, retryInfo.getDocumentId());
            return false;
        }

        // לא לנסות שוב עבור שגיאות שלא ניתנות לתיקון
        if (isNonRetryableError(error)) {
            log.warn("Non-retryable error for document {}: {}",
                    retryInfo.getDocumentId(), error.getClass().getSimpleName());
            return false;
        }

        return true;
    }

    /**
     * בדיקה אם זו שגיאה שלא ניתנת לתיקון
     */
    private boolean isNonRetryableError(Exception error) {
        // שגיאות שלא כדאי לנסות שוב
        return error instanceof IllegalArgumentException ||
                error instanceof IllegalStateException ||
                error instanceof NullPointerException ||
                (error.getMessage() != null && (
                        error.getMessage().contains("not found") ||
                                error.getMessage().contains("invalid") ||
                                error.getMessage().contains("corrupt")
                ));
    }

    /**
     * טיפול בניסיון נוסף
     */
    private void handleRetry(DocumentProcessingEvent event, RetryInfo retryInfo, String correlationId) {
        int attemptNumber = retryInfo.getAttempts();

        log.info("Scheduling retry attempt {} for document: {}", attemptNumber, event.getDocumentId());

        // עדכון סטטוס המסמך
        updateDocumentStatus(event.getDocumentId(),
                Document.ProcessingStatus.PROCESSING,
                0,
                String.format("ניסיון %d מתוך %d נכשל, מנסה שוב...", attemptNumber, maxRetries));

        // חישוב delay עם exponential backoff
        long delayMs = calculateRetryDelay(attemptNumber);

        // תזמון ניסיון נוסף
        scheduleRetry(event, delayMs, correlationId);
    }

    /**
     * טיפול בכשלון סופי
     */
    private void handleFinalFailure(DocumentProcessingEvent event, RetryInfo retryInfo,
                                    Exception error, String correlationId) {

        log.error("Final failure for document {} after {} attempts",
                event.getDocumentId(), retryInfo.getAttempts());

        // עדכון סטטוס לFAILED
        String errorMessage = String.format("עיבוד נכשל לאחר %d ניסיונות. שגיאה אחרונה: %s",
                retryInfo.getAttempts(), error.getMessage());

        updateDocumentStatus(event.getDocumentId(),
                Document.ProcessingStatus.FAILED,
                0,
                errorMessage);

        // שליחת אירוע כשלון
        kafkaEventProducerService.sendFailedStatus(
                event.getDocumentId(),
                event.getUserId(),
                event.getSessionId(),
                correlationId,
                errorMessage,
                getErrorDetails(error, retryInfo)
        );

        // ניקוי מעקב
        retryTracker.remove(event.getDocumentId());

        // שמירת מידע על השגיאה בcache לצורך debugging
        String errorCacheKey = "doc_error:" + event.getDocumentId();
        cacheService.set(errorCacheKey,
                Map.of(
                        "attempts", retryInfo.getAttempts(),
                        "lastError", retryInfo.getLastError(),
                        "errorDetails", getErrorDetails(error, retryInfo)
                ),
                java.time.Duration.ofDays(1));
    }

    /**
     * חישוב delay לניסיון הבא (exponential backoff)
     */
    private long calculateRetryDelay(int attemptNumber) {
        // Exponential backoff: 5s, 10s, 20s, 40s...
        return retryDelaySeconds * 1000L * (long) Math.pow(2, attemptNumber - 1);
    }

    /**
     * תזמון ניסיון נוסף
     */
    private void scheduleRetry(DocumentProcessingEvent event, long delayMs, String correlationId) {
        new Thread(() -> {
            try {
                log.info("Waiting {}ms before retry for document: {}", delayMs, event.getDocumentId());
                Thread.sleep(delayMs);

                // שליחת האירוע מחדש ל-Kafka
                log.info("Retrying processing for document: {}", event.getDocumentId());
                kafkaEventProducerService.sendDocumentProcessingEvent(event);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Retry scheduling interrupted for document: {}", event.getDocumentId());
            } catch (Exception e) {
                log.error("Failed to schedule retry for document: {}", event.getDocumentId(), e);
            }
        }).start();
    }

    /**
     * עדכון סטטוס מסמך
     */
    private void updateDocumentStatus(Long documentId, Document.ProcessingStatus status,
                                      int progress, String errorMessage) {
        try {
            Optional<Document> docOpt = documentRepository.findById(documentId);
            if (docOpt.isPresent()) {
                Document doc = docOpt.get();
                doc.setProcessingStatus(status);
                doc.setProcessingProgress(progress);
                doc.setErrorMessage(errorMessage);
                documentRepository.save(doc);

                log.debug("Updated document {} status to {}", documentId, status);
            }
        } catch (Exception e) {
            log.error("Failed to update document status: {}", documentId, e);
        }
    }

    /**
     * קבלת פרטי שגיאה מפורטים
     */
    private String getErrorDetails(Exception error, RetryInfo retryInfo) {
        StringBuilder details = new StringBuilder();

        details.append("Error Type: ").append(error.getClass().getSimpleName()).append("\n");
        details.append("Error Message: ").append(error.getMessage()).append("\n");
        details.append("Attempts: ").append(retryInfo.getAttempts()).append("\n");
        details.append("Last Attempt: ").append(retryInfo.getLastAttempt()).append("\n");

        if (error.getCause() != null) {
            details.append("Cause: ").append(error.getCause().getMessage()).append("\n");
        }

        // Stack trace של השגיאה העליונה
        StackTraceElement[] stackTrace = error.getStackTrace();
        if (stackTrace.length > 0) {
            details.append("Location: ").append(stackTrace[0].toString()).append("\n");
        }

        return details.toString();
    }

    /**
     * קבלת מידע על ניסיונות
     */
    public Optional<RetryInfo> getRetryInfo(Long documentId) {
        return Optional.ofNullable(retryTracker.get(documentId));
    }

    /**
     * ניקוי מעקב עבור מסמך
     */
    public void clearRetryInfo(Long documentId) {
        retryTracker.remove(documentId);
        log.debug("Cleared retry info for document: {}", documentId);
    }

    /**
     * Inner class למעקב אחר ניסיונות
     */
    public static class RetryInfo {
        private final Long documentId;
        private int attempts;
        private String lastError;
        private LocalDateTime lastAttempt;

        public RetryInfo(Long documentId) {
            this.documentId = documentId;
            this.attempts = 0;
        }

        public void incrementAttempts() {
            this.attempts++;
        }

        // Getters and Setters
        public Long getDocumentId() { return documentId; }
        public int getAttempts() { return attempts; }
        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
        public LocalDateTime getLastAttempt() { return lastAttempt; }
        public void setLastAttempt(LocalDateTime lastAttempt) { this.lastAttempt = lastAttempt; }
    }
}