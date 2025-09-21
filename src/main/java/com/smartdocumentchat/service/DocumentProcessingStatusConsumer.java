package com.smartdocumentchat.service;

import com.smartdocumentchat.config.KafkaConfig;
import com.smartdocumentchat.event.DocumentProcessingStatusEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingStatusConsumer {

    private final DocumentProgressTrackingService progressTrackingService;

    @KafkaListener(
            topics = KafkaConfig.DOCUMENT_PROCESSING_STATUS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}-status",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processStatusEvent(
            @Payload DocumentProcessingStatusEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("Received status event: documentId={}, status={}, progress={}%, correlationId={}, partition={}, offset={}",
                event.getDocumentId(), event.getStatus(), event.getProgressPercentage(),
                event.getCorrelationId(), partition, offset);

        try {
            // עדכון progress tracking
            progressTrackingService.updateProgress(
                    event.getDocumentId(),
                    event.getStatus(),
                    event.getProgressPercentage(),
                    event.getStatusMessage()
            );

            // אם הושלם או נכשל, נקה את המעקב
            if (event.isCompleted() || event.isFailed()) {
                log.info("Document {} processing {} - cleaning up tracking",
                        event.getDocumentId(),
                        event.isCompleted() ? "completed" : "failed");

                // נקה לאחר 5 דקות (נותן זמן לUI לקבל את העדכון)
                scheduleCleanup(event.getDocumentId());
            }

            acknowledgment.acknowledge();

            log.debug("Status event processed successfully for document: {}", event.getDocumentId());

        } catch (Exception e) {
            log.error("Failed to process status event: documentId={}, status={}, error={}",
                    event.getDocumentId(), event.getStatus(), e.getMessage(), e);

            // אישור גם במקרה של שגיאה
            acknowledgment.acknowledge();
        }
    }

    /**
     * תזמון ניקוי המעקב אחרי השלמה
     */
    private void scheduleCleanup(Long documentId) {
        // באפליקציה אמיתית נשתמש ב-ScheduledExecutorService
        // לצורך הדגמה, נקיים מיידית
        new Thread(() -> {
            try {
                Thread.sleep(5000); // המתן 5 שניות
                progressTrackingService.cleanupCompletedDocument(documentId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Cleanup thread interrupted for document: {}", documentId);
            }
        }).start();
    }
}