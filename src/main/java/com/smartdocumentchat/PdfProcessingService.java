package com.smartdocumentchat;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.Document;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.DocumentRepository;
import com.smartdocumentchat.service.CacheService;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfProcessingService {

    private final EmbeddingStoreIngestor embeddingStoreIngestor;
    private final DocumentRepository documentRepository;
    private final CacheService cacheService;

    /**
     * עיבוד קובץ PDF חדש לשיחה ספציפית
     */
    public Document processPdfFile(MultipartFile file, ChatSession chatSession) throws IOException {
        validateFile(file);

        String originalFileName = file.getOriginalFilename();
        log.info("מתחיל עיבוד קובץ PDF: {} עבור שיחה: {}", originalFileName, chatSession.getId());

        // בדיקה אם קובץ עם אותו שם כבר קיים בשיחה
        Optional<Document> existingDoc = documentRepository
                .findByChatSessionAndFileName(chatSession, originalFileName);

        if (existingDoc.isPresent()) {
            log.warn("קובץ עם שם {} כבר קיים בשיחה {}", originalFileName, chatSession.getId());
            throw new IllegalArgumentException("קובץ עם שם זהה כבר קיים בשיחה");
        }

        // חישוב hash של התוכן למניעת כפילויות
        String contentHash = calculateFileHash(file.getBytes());
        Optional<Document> duplicateDoc = documentRepository
                .findByUserAndContentHash(chatSession.getUser(), contentHash);

        if (duplicateDoc.isPresent()) {
            log.warn("קובץ עם תוכן זהה כבר קיים למשתמש");
            throw new IllegalArgumentException("קובץ עם תוכן זהה כבר קיים");
        }

        // יצירת רשומת Document חדשה
        Document document = new Document();
        document.setFileName(generateUniqueFileName(originalFileName));
        document.setOriginalFileName(originalFileName);
        document.setFileType(getFileExtension(originalFileName));
        document.setFileSize(file.getSize());
        document.setContentHash(contentHash);
        document.setProcessingStatus(Document.ProcessingStatus.PROCESSING);
        document.setProcessingProgress(0);
        document.setUser(chatSession.getUser());
        document.setChatSession(chatSession);
        document.setVectorCollectionName("smart_documents"); // כרגע קולקשן אחד

        // שמירה במסד הנתונים
        document = documentRepository.save(document);

        try {
            // עיבוד הקובץ
            dev.langchain4j.data.document.Document langchainDoc =
                    createDocumentFromInputStream(file.getInputStream(), originalFileName, document.getId().toString());

            document.setCharacterCount(langchainDoc.text().length());

            // הכנסה לmוקטור database
            embeddingStoreIngestor.ingest(langchainDoc);

            // עדכון סטטוס השלמה
            document.setProcessingStatus(Document.ProcessingStatus.COMPLETED);
            document.setProcessingProgress(100);
            document.setProcessedAt(LocalDateTime.now());

            // הערכת מספר chunks (בהנחה של 1200 תווים לchunk)
            int estimatedChunks = (int) Math.ceil(langchainDoc.text().length() / 1200.0);
            document.setChunkCount(estimatedChunks);

            document = documentRepository.save(document);

            // Invalidate caches after successful processing
            invalidateDocumentCache(document.getId(), chatSession.getId());

            log.info("קובץ {} עובד בהצלחה עם {} תווים", originalFileName, langchainDoc.text().length());
            return document;

        } catch (Exception e) {
            log.error("שגיאה בעיבוד קובץ PDF: {}", originalFileName, e);

            // עדכון סטטוס שגיאה
            document.setProcessingStatus(Document.ProcessingStatus.FAILED);
            document.setProcessingProgress(0);
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);

            throw new IOException("לא ניתן לעבד את קובץ ה-PDF: " + e.getMessage(), e);
        }
    }

    /**
     * קבלת כל המסמכים של שיחה עם caching
     */
    public List<Document> getDocumentsBySession(ChatSession chatSession) {
        String cacheKey = "session_docs:" + chatSession.getId();

        @SuppressWarnings("unchecked")
        List<Document> cachedDocs = (List<Document>) cacheService.getDocumentMetadata(cacheKey);

        if (cachedDocs != null) {
            log.debug("Documents for session {} retrieved from cache", chatSession.getId());
            return cachedDocs;
        }

        // Get from database
        List<Document> documents = documentRepository.findByChatSessionAndActiveTrueOrderByCreatedAtDesc(chatSession);

        // Cache for 6 hours
        cacheService.cacheDocumentMetadata(cacheKey, documents);
        log.debug("Documents for session {} retrieved from database and cached", chatSession.getId());

        return documents;
    }

    /**
     * קבלת מסמך לפי ID עם caching
     */
    public Optional<Document> getDocumentById(Long documentId) {
        String cacheKey = "document:" + documentId;
        Document cachedDoc = (Document) cacheService.getDocumentMetadata(cacheKey);

        if (cachedDoc != null) {
            log.debug("Document {} retrieved from cache", documentId);
            return Optional.of(cachedDoc);
        }

        Optional<Document> docOpt = documentRepository.findById(documentId);

        if (docOpt.isPresent()) {
            // Cache the document
            cacheService.cacheDocumentMetadata(cacheKey, docOpt.get());
            log.debug("Document {} retrieved from database and cached", documentId);
        }

        return docOpt;
    }

    /**
     * מחיקת מסמך (soft delete) עם cache invalidation
     */
    public boolean deleteDocument(Long documentId, User user) {
        Optional<Document> docOpt = getDocumentById(documentId);
        if (docOpt.isPresent()) {
            Document document = docOpt.get();

            // בדיקת הרשאות
            if (!document.getUser().getId().equals(user.getId())) {
                log.warn("משתמש {} מנסה למחוק מסמך {} של משתמש אחר",
                        user.getId(), documentId);
                return false;
            }

            document.setActive(false);
            documentRepository.save(document);

            // Invalidate caches
            invalidateDocumentCache(documentId, document.getChatSession().getId());

            log.info("מסמך {} נמחק בהצלחה", document.getOriginalFileName());
            return true;
        }
        return false;
    }

    /**
     * סטטיסטיקות משתמש
     */
    public DocumentStats getUserDocumentStats(User user) {
        String cacheKey = "user_stats:" + user.getId();
        DocumentStats cachedStats = (DocumentStats) cacheService.get(cacheKey);

        if (cachedStats != null) {
            log.debug("Document stats for user {} retrieved from cache", user.getId());
            return cachedStats;
        }

        long totalDocs = documentRepository.countByUserAndProcessingStatus(
                user, Document.ProcessingStatus.COMPLETED);
        long processingDocs = documentRepository.countByUserAndProcessingStatus(
                user, Document.ProcessingStatus.PROCESSING);
        long failedDocs = documentRepository.countByUserAndProcessingStatus(
                user, Document.ProcessingStatus.FAILED);

        DocumentStats stats = new DocumentStats(totalDocs, processingDocs, failedDocs);

        // Cache for 10 minutes
        cacheService.set(cacheKey, stats, java.time.Duration.ofMinutes(10));
        log.debug("Document stats for user {} retrieved from database and cached", user.getId());

        return stats;
    }

    // Helper methods

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("הקובץ ריק");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("הקובץ חייב להיות בפורמט PDF");
        }

        if (file.getSize() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("הקובץ גדול מדי. מקסימום 50MB");
        }
    }

    private dev.langchain4j.data.document.Document createDocumentFromInputStream(
            InputStream inputStream, String fileName, String documentId) throws IOException {

        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
        byte[] fileBytes = inputStream.readAllBytes();

        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(fileBytes)) {
            dev.langchain4j.data.document.Document document = parser.parse(byteStream);

            if (document.text() == null || document.text().trim().isEmpty()) {
                throw new IOException("הקובץ נפרסר אבל לא מכיל טקסט");
            }

            // הוספת metadata
            document.metadata().add("source", fileName);
            document.metadata().add("document_id", documentId);
            document.metadata().add("upload_time", LocalDateTime.now().toString());

            return document;
        }
    }

    private String generateUniqueFileName(String originalFileName) {
        return java.util.UUID.randomUUID().toString().substring(0, 8) + "_" +
                originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return null;
        int lastDot = fileName.lastIndexOf('.');
        return (lastDot >= 0) ? fileName.substring(lastDot + 1).toLowerCase() : null;
    }

    private String calculateFileHash(byte[] fileBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(fileBytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("שגיאה בחישוב hash", e);
            return null;
        }
    }

    /**
     * פינוי cache של מסמכים
     */
    private void invalidateDocumentCache(Long documentId, Long sessionId) {
        // Invalidate individual document cache
        cacheService.delete("document:" + documentId);

        // Invalidate session documents cache
        if (sessionId != null) {
            cacheService.delete("session_docs:" + sessionId);
        }

        // Invalidate user stats cache (we don't have user ID here, but it's OK)
        // Stats cache has short TTL anyway

        log.debug("Invalidated document cache for document {} and session {}", documentId, sessionId);
    }

    // Inner class for statistics
    public static class DocumentStats {
        public final long totalDocuments;
        public final long processingDocuments;
        public final long failedDocuments;

        public DocumentStats(long total, long processing, long failed) {
            this.totalDocuments = total;
            this.processingDocuments = processing;
            this.failedDocuments = failed;
        }
    }
}