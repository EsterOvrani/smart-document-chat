package com.smartdocumentchat.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentProcessingEvent {

    private Long documentId;
    private Long userId;
    private Long sessionId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String contentHash;
    private byte[] fileContent;
    private ProcessingAction action;
    private LocalDateTime timestamp;
    private String correlationId;

    public enum ProcessingAction {
        PROCESS_DOCUMENT,
        REPROCESS_DOCUMENT,
        DELETE_DOCUMENT,
        CANCEL_PROCESSING
    }

    // Helper constructor for processing new document
    public static DocumentProcessingEvent forProcessing(Long documentId, Long userId, Long sessionId,
                                                        String fileName, String fileType, Long fileSize,
                                                        String contentHash, byte[] fileContent) {
        DocumentProcessingEvent event = new DocumentProcessingEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setFileName(fileName);
        event.setFileType(fileType);
        event.setFileSize(fileSize);
        event.setContentHash(contentHash);
        event.setFileContent(fileContent);
        event.setAction(ProcessingAction.PROCESS_DOCUMENT);
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(generateCorrelationId(documentId, userId));
        return event;
    }

    // Helper constructor for reprocessing
    public static DocumentProcessingEvent forReprocessing(Long documentId, Long userId, Long sessionId,
                                                          String fileName, byte[] fileContent) {
        DocumentProcessingEvent event = new DocumentProcessingEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setFileName(fileName);
        event.setFileContent(fileContent);
        event.setAction(ProcessingAction.REPROCESS_DOCUMENT);
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(generateCorrelationId(documentId, userId));
        return event;
    }

    // Helper constructor for deletion
    public static DocumentProcessingEvent forDeletion(Long documentId, Long userId, Long sessionId) {
        DocumentProcessingEvent event = new DocumentProcessingEvent();
        event.setDocumentId(documentId);
        event.setUserId(userId);
        event.setSessionId(sessionId);
        event.setAction(ProcessingAction.DELETE_DOCUMENT);
        event.setTimestamp(LocalDateTime.now());
        event.setCorrelationId(generateCorrelationId(documentId, userId));
        return event;
    }

    private static String generateCorrelationId(Long documentId, Long userId) {
        return String.format("%d-%d-%d", documentId, userId, System.currentTimeMillis());
    }

    public boolean isProcessingAction() {
        return action == ProcessingAction.PROCESS_DOCUMENT ||
                action == ProcessingAction.REPROCESS_DOCUMENT;
    }

    public boolean isDeletionAction() {
        return action == ProcessingAction.DELETE_DOCUMENT;
    }

    public boolean isCancellationAction() {
        return action == ProcessingAction.CANCEL_PROCESSING;
    }
}