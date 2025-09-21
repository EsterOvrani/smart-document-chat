package com.smartdocumentchat.service;

import com.smartdocumentchat.config.KafkaConfig;
import com.smartdocumentchat.config.QdrantConfig;
import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.Document;
import com.smartdocumentchat.event.DocumentProcessingEvent;
import com.smartdocumentchat.event.DocumentProcessingStatusEvent;
import com.smartdocumentchat.repository.ChatSessionRepository;
import com.smartdocumentchat.repository.DocumentRepository;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingConsumer {

    private final DocumentRepository documentRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final QdrantVectorService qdrantVectorService;
    private final QdrantConfig.SessionAwareIngestorFactory ingestorFactory;
    private final KafkaEventProducerService kafkaEventProducerService;
    private final CacheService cacheService;

    @KafkaListener(
            topics = KafkaConfig.DOCUMENT_PROCESSING_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processDocumentEvent(
            @Payload DocumentProcessingEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        long startTime = System.currentTimeMillis();
        String correlationId = event.getCorrelationId();

        log.info("Processing document event: documentId={}, userId={}, sessionId={}, action={}, " +
                        "correlationId={}, partition={}, offset={}",
                event.getDocumentId(), event.getUserId(), event.getSessionId(),
                event.getAction(), correlationId, partition, offset);

        try {
            // שליחת סטטוס "התקבל"
            kafkaEventProducerService.sendReceivedStatus(
                    event.getDocumentId(), event.getUserId(), event.getSessionId(), correlationId);

            // טיפול לפי סוג הפעולה
            if (event.isProcessingAction()) {
                processDocument(event);
            } else if (event.isDeletionAction()) {
                deleteDocument(event);
            } else if (event.isCancellationAction()) {
                cancelProcessing(event);
            }

            // אישור הודעה רק אחרי עיבוד מוצלח
            acknowledgment.acknowledge();

            long processingTime = System.currentTimeMillis() - startTime;
            log.info("Document event processed successfully: documentId={}, processingTime={}ms",
                    event.getDocumentId(), processingTime);

        } catch (Exception e) {
            log.error("Failed to process document event: documentId={}, correlationId={}, error={}",
                    event.getDocumentId(), correlationId, e.getMessage(), e);

            // שליחת סטטוס "נכשל"
            kafkaEventProducerService.sendFailedStatus(
                    event.getDocumentId(), event.getUserId(), event.getSessionId(),
                    correlationId, e.getMessage(), getErrorDetails(e));

            // עדכון מסמך בDB לסטטוס failed
            updateDocumentStatus(event.getDocumentId(), Document.ProcessingStatus.FAILED,
                    0, e.getMessage());

            // אישור הודעה גם במקרה של שגיאה (אחרת תישאר בתור לנצח)
            acknowledgment.acknowledge();
        }
    }

    private void processDocument(DocumentProcessingEvent event) throws Exception {
        String correlationId = event.getCorrelationId();
        long startTime = System.currentTimeMillis();

        // קבלת המסמך מהDB
        Optional<Document> documentOpt = documentRepository.findById(event.getDocumentId());
        if (documentOpt.isEmpty()) {
            throw new IllegalArgumentException("Document not found: " + event.getDocumentId());
        }

        Document document = documentOpt.get();

        // קבלת החיפה
        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(event.getSessionId());
        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("Chat session not found: " + event.getSessionId());
        }

        ChatSession chatSession = sessionOpt.get();

        // שליחת סטטוס "החל"
        kafkaEventProducerService.sendStartedStatus(
                event.getDocumentId(), event.getUserId(), event.getSessionId(), correlationId);

        // עדכון סטטוס ל-PROCESSING
        updateDocumentStatus(event.getDocumentId(), Document.ProcessingStatus.PROCESSING, 10, null);

        // פרסור המסמך
        log.info("Parsing document: documentId={}, fileName={}", event.getDocumentId(), event.getFileName());
        kafkaEventProducerService.sendProcessingStatusEvent(
                DocumentProcessingStatusEvent.parsing(
                        event.getDocumentId(), event.getUserId(), event.getSessionId(), correlationId));

        dev.langchain4j.data.document.Document langchainDoc = parseDocument(
                event.getFileContent(), event.getFileName(), event.getDocumentId(), chatSession);

        updateDocumentStatus(event.getDocumentId(), Document.ProcessingStatus.PROCESSING, 30, null);

        // חיתוך לchunks
        int characterCount = langchainDoc.text().length();
        log.info("Chunking document: documentId={}, characters={}", event.getDocumentId(), characterCount);
        kafkaEventProducerService.sendProcessingStatusEvent(
                DocumentProcessingStatusEvent.chunking(
                        event.getDocumentId(), event.getUserId(), event.getSessionId(),
                        correlationId, characterCount));

        updateDocumentStatus(event.getDocumentId(), Document.ProcessingStatus.PROCESSING, 50, null);

        // יצירת embeddings ואחסון
        String vectorCollectionName = qdrantVectorService.generateSessionCollectionName(
                event.getSessionId(), event.getUserId());

        log.info("Creating embeddings: documentId={}, collection={}",
                event.getDocumentId(), vectorCollectionName);
        kafkaEventProducerService.sendProcessingStatusEvent(
                DocumentProcessingStatusEvent.embedding(
                        event.getDocumentId(), event.getUserId(), event.getSessionId(),
                        correlationId, estimateChunkCount(characterCount)));

        updateDocumentStatus(event.getDocumentId(), Document.ProcessingStatus.PROCESSING, 70, null);

        // קבלת embedding store לשיחה
        EmbeddingStore<TextSegment> sessionEmbeddingStore =
                qdrantVectorService.getEmbeddingStoreForSession(chatSession);

        // יצירת ingestor ספציפי לsession
        EmbeddingStoreIngestor sessionIngestor =
                ingestorFactory.createIngestorForStore(sessionEmbeddingStore);

        log.info("Storing in vector database: documentId={}, collection={}",
                event.getDocumentId(), vectorCollectionName);
        kafkaEventProducerService.sendProcessingStatusEvent(
                DocumentProcessingStatusEvent.storing(
                        event.getDocumentId(), event.getUserId(), event.getSessionId(),
                        correlationId, vectorCollectionName));

        // הכנסה ל-vector database
        sessionIngestor.ingest(langchainDoc);

        updateDocumentStatus(event.getDocumentId(), Document.ProcessingStatus.PROCESSING, 90, null);

        // עדכון המסמך בDB להצלחה
        document.setCharacterCount(characterCount);
        document.setChunkCount(estimateChunkCount(characterCount));
        document.setVectorCollectionName(vectorCollectionName);
        document.setProcessingStatus(Document.ProcessingStatus.COMPLETED);
        document.setProcessingProgress(100);
        document.setProcessedAt(LocalDateTime.now());
        documentRepository.save(document);

        // פינוי cache
        invalidateCache(event.getSessionId(), event.getUserId());

        long processingTime = System.currentTimeMillis() - startTime;

        // שליחת סטטוס "הושלם"
        kafkaEventProducerService.sendCompletedStatus(
                event.getDocumentId(), event.getUserId(), event.getSessionId(), correlationId,
                characterCount, estimateChunkCount(characterCount), processingTime, vectorCollectionName);

        log.info("Document processed successfully: documentId={}, characters={}, chunks={}, time={}ms",
                event.getDocumentId(), characterCount, estimateChunkCount(characterCount), processingTime);
    }

    private void deleteDocument(DocumentProcessingEvent event) {
        log.info("Deleting document: documentId={}", event.getDocumentId());

        Optional<Document> documentOpt = documentRepository.findById(event.getDocumentId());
        if (documentOpt.isPresent()) {
            Document document = documentOpt.get();
            document.setActive(false);
            documentRepository.save(document);

            // פינוי cache
            invalidateCache(event.getSessionId(), event.getUserId());

            log.info("Document deleted successfully: documentId={}", event.getDocumentId());
        }
    }

    private void cancelProcessing(DocumentProcessingEvent event) {
        log.info("Canceling document processing: documentId={}", event.getDocumentId());

        updateDocumentStatus(event.getDocumentId(), Document.ProcessingStatus.CANCELLED,
                0, "Processing cancelled by user");

        log.info("Document processing cancelled: documentId={}", event.getDocumentId());
    }

    private dev.langchain4j.data.document.Document parseDocument(
            byte[] fileContent, String fileName, Long documentId, ChatSession chatSession) throws Exception {

        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileContent)) {
            dev.langchain4j.data.document.Document document = parser.parse(inputStream);

            if (document.text() == null || document.text().trim().isEmpty()) {
                throw new Exception("Document parsed but contains no text");
            }

            // הוספת metadata
            document.metadata().add("source", fileName);
            document.metadata().add("document_id", documentId.toString());
            document.metadata().add("user_id", chatSession.getUser().getId().toString());
            document.metadata().add("username", chatSession.getUser().getUsername());
            document.metadata().add("session_id", chatSession.getId().toString());
            document.metadata().add("session_title", chatSession.getDisplayTitle());
            document.metadata().add("upload_time", LocalDateTime.now().toString());

            return document;
        }
    }

    private void updateDocumentStatus(Long documentId, Document.ProcessingStatus status,
                                      int progress, String errorMessage) {
        try {
            Optional<Document> docOpt = documentRepository.findById(documentId);
            if (docOpt.isPresent()) {
                Document doc = docOpt.get();
                doc.setProcessingStatus(status);
                doc.setProcessingProgress(progress);
                doc.setErrorMessage(errorMessage);
                if (status == Document.ProcessingStatus.COMPLETED) {
                    doc.setProcessedAt(LocalDateTime.now());
                }
                documentRepository.save(doc);
            }
        } catch (Exception e) {
            log.error("Failed to update document status: documentId={}, status={}",
                    documentId, status, e);
        }
    }

    private int estimateChunkCount(int characterCount) {
        return (int) Math.ceil(characterCount / 1200.0);
    }

    private void invalidateCache(Long sessionId, Long userId) {
        String cacheKey = "session_docs:" + sessionId + "_user:" + userId;
        cacheService.delete(cacheKey);
    }

    private String getErrorDetails(Exception e) {
        if (e.getCause() != null) {
            return e.getMessage() + " | Cause: " + e.getCause().getMessage();
        }
        return e.getMessage();
    }
}