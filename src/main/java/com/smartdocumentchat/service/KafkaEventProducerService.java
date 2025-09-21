package com.smartdocumentchat.service;

import com.smartdocumentchat.config.KafkaConfig;
import com.smartdocumentchat.event.DocumentProcessingEvent;
import com.smartdocumentchat.event.DocumentProcessingStatusEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaEventProducerService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * שליחת אירוע עיבוד מסמך
     */
    public CompletableFuture<SendResult<String, Object>> sendDocumentProcessingEvent(
            DocumentProcessingEvent event) {

        String key = generateEventKey(event.getDocumentId(), event.getUserId());

        log.info("Sending document processing event: documentId={}, userId={}, action={}, correlationId={}",
                event.getDocumentId(), event.getUserId(), event.getAction(), event.getCorrelationId());

        return kafkaTemplate.send(KafkaConfig.DOCUMENT_PROCESSING_TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Document processing event sent successfully: partition={}, offset={}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to send document processing event: documentId={}, error={}",
                                event.getDocumentId(), ex.getMessage(), ex);
                    }
                });
    }

    /**
     * שליחת אירוע סטטוס עיבוד
     */
    public CompletableFuture<SendResult<String, Object>> sendProcessingStatusEvent(
            DocumentProcessingStatusEvent event) {

        String key = generateEventKey(event.getDocumentId(), event.getUserId());

        log.info("Sending processing status event: documentId={}, status={}, progress={}%, correlationId={}",
                event.getDocumentId(), event.getStatus(), event.getProgressPercentage(),
                event.getCorrelationId());

        return kafkaTemplate.send(KafkaConfig.DOCUMENT_PROCESSING_STATUS_TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Processing status event sent successfully: partition={}, offset={}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("Failed to send processing status event: documentId={}, status={}, error={}",
                                event.getDocumentId(), event.getStatus(), ex.getMessage(), ex);
                    }
                });
    }

    /**
     * שליחת אירוע עיבוד עם callback
     */
    public void sendDocumentProcessingEventWithCallback(
            DocumentProcessingEvent event,
            ProcessingEventCallback callback) {

        sendDocumentProcessingEvent(event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        callback.onSuccess(event, result);
                    } else {
                        callback.onFailure(event, ex);
                    }
                });
    }

    /**
     * שליחת סטטוס "התקבל"
     */
    public void sendReceivedStatus(Long documentId, Long userId, Long sessionId, String correlationId) {
        DocumentProcessingStatusEvent event = DocumentProcessingStatusEvent.received(
                documentId, userId, sessionId, correlationId);
        sendProcessingStatusEvent(event);
    }

    /**
     * שליחת סטטוס "החל"
     */
    public void sendStartedStatus(Long documentId, Long userId, Long sessionId, String correlationId) {
        DocumentProcessingStatusEvent event = DocumentProcessingStatusEvent.started(
                documentId, userId, sessionId, correlationId);
        sendProcessingStatusEvent(event);
    }

    /**
     * שליחת סטטוס "הושלם"
     */
    public void sendCompletedStatus(Long documentId, Long userId, Long sessionId, String correlationId,
                                    Integer characterCount, Integer chunkCount,
                                    Long processingTimeMs, String vectorCollectionName) {
        DocumentProcessingStatusEvent event = DocumentProcessingStatusEvent.completed(
                documentId, userId, sessionId, correlationId,
                characterCount, chunkCount, processingTimeMs, vectorCollectionName);
        sendProcessingStatusEvent(event);
    }

    /**
     * שליחת סטטוס "נכשל"
     */
    public void sendFailedStatus(Long documentId, Long userId, Long sessionId, String correlationId,
                                 String errorMessage, String errorDetails) {
        DocumentProcessingStatusEvent event = DocumentProcessingStatusEvent.failed(
                documentId, userId, sessionId, correlationId, errorMessage, errorDetails);
        sendProcessingStatusEvent(event);
    }

    /**
     * יצירת מפתח לאירוע (לconsistent partitioning)
     */
    private String generateEventKey(Long documentId, Long userId) {
        return String.format("doc_%d_user_%d", documentId, userId);
    }

    /**
     * Interface לcallbacks
     */
    public interface ProcessingEventCallback {
        void onSuccess(DocumentProcessingEvent event, SendResult<String, Object> result);
        void onFailure(DocumentProcessingEvent event, Throwable ex);
    }
}