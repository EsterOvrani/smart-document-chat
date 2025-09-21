package com.smartdocumentchat.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProcessingStatusEvent {

    private Long documentId;
    private Long userId;
    private Long sessionId;
    private ProcessingStatus status;
    private Integer progressPercentage;
    private String statusMessage;
    private String errorMessage;
    private ProcessingMetadata metadata;
    private LocalDateTime timestamp;
    private String correlationId;

    public enum ProcessingStatus {
        RECEIVED,           // הודעה התקבלה
        STARTED,           // עיבוד החל
        IN_PROGRESS,       // בעיבוד
        PARSING,           // פרסור המסמך
        CHUNKING,          // חיתוך לchunks
        EMBEDDING,         // יצירת embeddings
        STORING,           // אחסון ב-vector DB
        COMPLETED,         // הושלם בהצלחה
        FAILED,            // נכשל
        CANCELLED          // בוטל
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingMetadata {
        private Integer characterCount;
        private Integer chunkCount;
        private Long processingTimeMs;
        private String vectorCollectionName;
        private String errorDetails;
        private Integer retryCount;
    }

    // Helper methods
    public static DocumentProcessingStatusEvent received(Long documentId, Long userId,
                                                         Long sessionId, String correlationId) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.RECEIVED);
        event.setProgressPercentage(0);
        event.setStatusMessage("מסמך התקבל לעיבוד");
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);
        event.setMetadata(new ProcessingMetadata());
        return event;
    }

    public static DocumentProcessingStatusEvent started(Long documentId, Long userId,
                                                        Long sessionId, String correlationId) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.STARTED);
        event.setProgressPercentage(5);
        event.setStatusMessage("עיבוד המסמך החל");
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);
        event.setMetadata(new ProcessingMetadata());
        return event;
    }

    public static DocumentProcessingStatusEvent parsing(Long documentId, Long userId,
                                                        Long sessionId, String correlationId) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.PARSING);
        event.setProgressPercentage(20);
        event.setStatusMessage("פרסור המסמך");
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);
        event.setMetadata(new ProcessingMetadata());
        return event;
    }

    public static DocumentProcessingStatusEvent chunking(Long documentId, Long userId,
                                                         Long sessionId, String correlationId,
                                                         Integer characterCount) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.CHUNKING);
        event.setProgressPercentage(40);
        event.setStatusMessage("חיתוך המסמך לchunks");
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);

        ProcessingMetadata metadata = new ProcessingMetadata();
        metadata.setCharacterCount(characterCount);
        event.setMetadata(metadata);

        return event;
    }

    public static DocumentProcessingStatusEvent embedding(Long documentId, Long userId,
                                                          Long sessionId, String correlationId,
                                                          Integer chunkCount) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.EMBEDDING);
        event.setProgressPercentage(60);
        event.setStatusMessage("יצירת embeddings");
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);

        ProcessingMetadata metadata = new ProcessingMetadata();
        metadata.setChunkCount(chunkCount);
        event.setMetadata(metadata);

        return event;
    }

    public static DocumentProcessingStatusEvent storing(Long documentId, Long userId,
                                                        Long sessionId, String correlationId,
                                                        String vectorCollectionName) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.STORING);
        event.setProgressPercentage(80);
        event.setStatusMessage("אחסון ב-vector database");
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);

        ProcessingMetadata metadata = new ProcessingMetadata();
        metadata.setVectorCollectionName(vectorCollectionName);
        event.setMetadata(metadata);

        return event;
    }

    public static DocumentProcessingStatusEvent completed(Long documentId, Long userId,
                                                          Long sessionId, String correlationId,
                                                          Integer characterCount, Integer chunkCount,
                                                          Long processingTimeMs, String vectorCollectionName) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.COMPLETED);
        event.setProgressPercentage(100);
        event.setStatusMessage("עיבוד הושלם בהצלחה");
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);

        ProcessingMetadata metadata = new ProcessingMetadata();
        metadata.setCharacterCount(characterCount);
        metadata.setChunkCount(chunkCount);
        metadata.setProcessingTimeMs(processingTimeMs);
        metadata.setVectorCollectionName(vectorCollectionName);
        event.setMetadata(metadata);

        return event;
    }

    public static DocumentProcessingStatusEvent failed(Long documentId, Long userId,
                                                       Long sessionId, String correlationId,
                                                       String errorMessage, String errorDetails) {
        DocumentProcessingStatusEvent event = new DocumentProcessingStatusEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setStatus(ProcessingStatus.FAILED);
        event.setProgressPercentage(0);
        event.setStatusMessage("עיבוד נכשל");
        event.setErrorMessage(errorMessage);
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(correlationId);

        ProcessingMetadata metadata = new ProcessingMetadata();
        metadata.setErrorDetails(errorDetails);
        event.setMetadata(metadata);

        return event;
    }

    public boolean isCompleted() {
        return status == ProcessingStatus.COMPLETED;
    }

    public boolean isFailed() {
        return status == ProcessingStatus.FAILED;
    }

    public boolean isInProgress() {
        return status == ProcessingStatus.IN_PROGRESS ||
                status == ProcessingStatus.PARSING ||
                status == ProcessingStatus.CHUNKING ||
                status == ProcessingStatus.EMBEDDING ||
                status == ProcessingStatus.STORING;
    }
}