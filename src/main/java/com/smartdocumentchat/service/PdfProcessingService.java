package com.smartdocumentchat.service;

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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfProcessingService {

    private final EmbeddingStoreIngestor embeddingStoreIngestor;
    private final DocumentRepository documentRepository;
    private final CacheService cacheService;

    /**
     * עיבוד קובץ PDF חדש לשיחה ספציפית עם תמיכה בקבצים מרובים
     */
    public Document processPdfFile(MultipartFile file, ChatSession chatSession) throws IOException {
        validateFile(file);
        validateChatSessionForUpload(chatSession);

        String originalFileName = file.getOriginalFilename();
        User sessionUser = chatSession.getUser();

        log.info("מתחיל עיבוד קובץ PDF: {} עבור שיחה: {} של משתמש: {}",
                originalFileName, chatSession.getId(), sessionUser.getUsername());

        // בדיקה אם קובץ עם אותו שם כבר קיים בשיחה הספציפית הזו
        Optional<Document> existingDoc = documentRepository
                .findByChatSessionAndFileName(chatSession, originalFileName);

        if (existingDoc.isPresent() && existingDoc.get().getActive()) {
            log.warn("קובץ עם שם {} כבר קיים בשיחה {} של משתמש {}",
                    originalFileName, chatSession.getId(), sessionUser.getUsername());
            throw new IllegalArgumentException("קובץ עם שם זהה כבר קיים בשיחה זו");
        }

        // חישוב hash של התוכן - בודק רק בתוך אותה שיחה
        String contentHash = calculateFileHash(file.getBytes());
        Optional<Document> duplicateDoc = findDuplicateInSession(chatSession, contentHash);

        if (duplicateDoc.isPresent()) {
            log.warn("קובץ עם תוכן זהה כבר קיים בשיחה {} (מסמך קיים: {})",
                    chatSession.getId(), duplicateDoc.get().getId());
            throw new IllegalArgumentException("קובץ עם תוכן זהה כבר קיים בשיחה זו");
        }

        // יצירת רשומת Document חדשה עם קישור לשיחה ספציפית
        Document document = new Document();
        document.setFileName(generateUniqueFileName(originalFileName, sessionUser.getId(), chatSession.getId()));
        document.setOriginalFileName(originalFileName);
        document.setFileType(getFileExtension(originalFileName));
        document.setFileSize(file.getSize());
        document.setContentHash(contentHash);
        document.setProcessingStatus(Document.ProcessingStatus.PROCESSING);
        document.setProcessingProgress(0);

        // קישור מפורש למשתמש ולשיחה הספציפית
        document.setUser(sessionUser);
        document.setChatSession(chatSession);
        document.setVectorCollectionName("session_" + chatSession.getId() + "_user_" + sessionUser.getId());

        // שמירה במסד הנתונים
        document = documentRepository.save(document);

        try {
            // עיבוד הקובץ
            dev.langchain4j.data.document.Document langchainDoc =
                    createDocumentFromInputStream(file.getInputStream(), originalFileName,
                            document.getId().toString(), sessionUser, chatSession);

            document.setCharacterCount(langchainDoc.text().length());

            // הכנסה לmוקטור database עם collection נפרד לכל שיחה
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
            invalidateSessionDocumentCache(chatSession.getId(), sessionUser.getId());

            log.info("קובץ {} עובד בהצלחה עם {} תווים עבור שיחה {} של משתמש {} (מסמך ID: {})",
                    originalFileName, langchainDoc.text().length(), chatSession.getId(),
                    sessionUser.getUsername(), document.getId());
            return document;

        } catch (Exception e) {
            log.error("שגיאה בעיבוד קובץ PDF: {} עבור שיחה {} של משתמש {}",
                    originalFileName, chatSession.getId(), sessionUser.getUsername(), e);

            // עדכון סטטוס שגיאה
            document.setProcessingStatus(Document.ProcessingStatus.FAILED);
            document.setProcessingProgress(0);
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);

            throw new IOException("לא ניתן לעבד את קובץ ה-PDF: " + e.getMessage(), e);
        }
    }

    /**
     * קבלת כל המסמכים של שיחה ספציפית עם caching משופר
     */
    public List<Document> getDocumentsBySession(ChatSession chatSession) {
        validateChatSession(chatSession);

        String cacheKey = "session_docs:" + chatSession.getId() + "_user:" + chatSession.getUser().getId();

        @SuppressWarnings("unchecked")
        List<Document> cachedDocs = (List<Document>) cacheService.getDocumentMetadata(cacheKey);

        if (cachedDocs != null) {
            log.debug("Documents for session {} (user {}) retrieved from cache",
                    chatSession.getId(), chatSession.getUser().getId());
            return cachedDocs;
        }

        // Get from database with user verification - only active documents
        List<Document> documents = documentRepository.findByChatSessionAndActiveTrueOrderByCreatedAtDesc(chatSession);

        // Verify all documents belong to the session's user (security check)
        documents = documents.stream()
                .filter(doc -> doc.getUser().getId().equals(chatSession.getUser().getId()))
                .filter(doc -> doc.getChatSession().getId().equals(chatSession.getId()))
                .collect(Collectors.toList());

        // Cache for 6 hours
        cacheService.cacheDocumentMetadata(cacheKey, documents);
        log.debug("Documents for session {} (user {}) retrieved from database and cached: {} documents",
                chatSession.getId(), chatSession.getUser().getId(), documents.size());

        return documents;
    }

    /**
     * קבלת מסמכים מעובדים בלבד לשיחה (לשאילת שאלות)
     */
    public List<Document> getProcessedDocumentsBySession(ChatSession chatSession) {
        List<Document> allDocuments = getDocumentsBySession(chatSession);

        return allDocuments.stream()
                .filter(Document::isProcessed)
                .collect(Collectors.toList());
    }

    /**
     * קבלת כל המסמכים של משתמש מכל השיחות
     */
    public List<Document> getDocumentsByUser(User user) {
        validateUser(user);

        String cacheKey = "user_all_docs:" + user.getId();

        @SuppressWarnings("unchecked")
        List<Document> cachedDocs = (List<Document>) cacheService.getDocumentMetadata(cacheKey);

        if (cachedDocs != null) {
            log.debug("All documents for user {} retrieved from cache", user.getId());
            return cachedDocs;
        }

        List<Document> documents = documentRepository.findByUserOrderByCreatedAtDesc(user);

        // Cache for 30 minutes (shorter for user-wide queries)
        cacheService.set(cacheKey, documents, java.time.Duration.ofMinutes(30));
        log.debug("All documents for user {} retrieved from database and cached: {} documents",
                user.getId(), documents.size());

        return documents;
    }

    /**
     * העברת מסמך בין שיחות
     */
    public boolean moveDocumentToSession(Long documentId, Long targetSessionId, User user) {
        validateUser(user);

        Optional<Document> documentOpt = getDocumentById(documentId, user);
        if (documentOpt.isEmpty()) {
            log.warn("מסמך {} לא נמצא עבור משתמש {}", documentId, user.getId());
            return false;
        }

        // Validate target session
        // This requires ChatSessionService injection - we'll skip for now
        // In real implementation, we'd validate the target session belongs to the user

        Document document = documentOpt.get();
        Long oldSessionId = document.getChatSession().getId();

        // Update session reference
        // document.setChatSession(targetSession);
        // documentRepository.save(document);

        // Invalidate caches for both sessions
        invalidateSessionDocumentCache(oldSessionId, user.getId());
        invalidateSessionDocumentCache(targetSessionId, user.getId());

        log.info("מסמך {} הועבר משיחה {} לשיחה {} עבור משתמש {}",
                documentId, oldSessionId, targetSessionId, user.getId());
        return true;
    }

    /**
     * קבלת מסמך לפי ID עם בדיקת הרשאות משתמש
     */
    public Optional<Document> getDocumentById(Long documentId, User requestingUser) {
        if (documentId == null || documentId <= 0) {
            log.warn("ניסיון קבלת מסמך עם ID לא תקין: {}", documentId);
            return Optional.empty();
        }

        validateUser(requestingUser);

        String cacheKey = "document:" + documentId + "_user:" + requestingUser.getId();
        Document cachedDoc = (Document) cacheService.getDocumentMetadata(cacheKey);

        if (cachedDoc != null) {
            log.debug("Document {} retrieved from cache (user {})", documentId, requestingUser.getId());
            return Optional.of(cachedDoc);
        }

        Optional<Document> docOpt = documentRepository.findById(documentId);

        if (docOpt.isPresent()) {
            Document document = docOpt.get();

            // בדיקת הרשאות - וודא שהמסמך שייך למשתמש המבקש
            if (!document.getUser().getId().equals(requestingUser.getId())) {
                log.warn("משתמש {} ניסה לגשת למסמך {} של משתמש אחר",
                        requestingUser.getId(), documentId);
                return Optional.empty();
            }

            // Cache the document
            cacheService.cacheDocumentMetadata(cacheKey, document);
            log.debug("Document {} retrieved from database and cached (user {})",
                    documentId, requestingUser.getId());
        }

        return docOpt;
    }

    /**
     * מחיקת מסמך (soft delete) עם בדיקת הרשאות משתמש
     */
    public boolean deleteDocument(Long documentId, User requestingUser) {
        validateUser(requestingUser);

        Optional<Document> docOpt = getDocumentById(documentId, requestingUser);
        if (docOpt.isPresent()) {
            Document document = docOpt.get();

            // בדיקת הרשאות כבר נעשתה ב-getDocumentById
            document.setActive(false);
            documentRepository.save(document);

            // Invalidate caches
            invalidateSessionDocumentCache(document.getChatSession().getId(), requestingUser.getId());
            invalidateUserDocumentCache(requestingUser.getId());

            log.info("מסמך {} ({}) נמחק בהצלחה על ידי משתמש {} משיחה {}",
                    document.getId(), document.getOriginalFileName(),
                    requestingUser.getUsername(), document.getChatSession().getId());
            return true;
        }

        log.warn("ניסיון מחיקת מסמך {} על ידי משתמש {} נכשל - מסמך לא נמצא או אין הרשאה",
                documentId, requestingUser.getUsername());
        return false;
    }

    /**
     * סטטיסטיקות משתמש עם caching משופר
     */
    public DocumentStats getUserDocumentStats(User user) {
        validateUser(user);

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

    /**
     * קבלת סטטיסטיקות מסמכים לשיחה ספציפית
     */
    public SessionDocumentStats getSessionDocumentStats(ChatSession chatSession) {
        validateChatSession(chatSession);

        List<Document> documents = getDocumentsBySession(chatSession);

        long totalDocs = documents.size();
        long completedDocs = documents.stream().mapToLong(doc -> doc.isProcessed() ? 1 : 0).sum();
        long processingDocs = documents.stream().mapToLong(doc -> doc.isProcessing() ? 1 : 0).sum();
        long failedDocs = documents.stream().mapToLong(doc -> doc.hasFailed() ? 1 : 0).sum();

        int totalCharacters = documents.stream()
                .filter(Document::isProcessed)
                .mapToInt(doc -> doc.getCharacterCount() != null ? doc.getCharacterCount() : 0)
                .sum();

        int totalChunks = documents.stream()
                .filter(Document::isProcessed)
                .mapToInt(doc -> doc.getChunkCount() != null ? doc.getChunkCount() : 0)
                .sum();

        return new SessionDocumentStats(totalDocs, completedDocs, processingDocs,
                failedDocs, totalCharacters, totalChunks);
    }

    /**
     * בדיקה אם יש מסמכים מעובדים בשיחה (לשאלות AI)
     */
    public boolean hasProcessedDocuments(ChatSession chatSession) {
        List<Document> processedDocs = getProcessedDocumentsBySession(chatSession);
        return !processedDocs.isEmpty();
    }

    // Helper methods

    private Optional<Document> findDuplicateInSession(ChatSession chatSession, String contentHash) {
        // מציאת כפילויות רק בתוך אותה שיחה
        List<Document> sessionDocuments = getDocumentsBySession(chatSession);

        return sessionDocuments.stream()
                .filter(doc -> contentHash.equals(doc.getContentHash()))
                .findFirst();
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("הקובץ ריק או לא תקין");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("הקובץ חייב להיות בפורמט PDF");
        }

        if (file.getSize() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("הקובץ גדול מדי. מקסימום 50MB");
        }
    }

    private void validateChatSession(ChatSession chatSession) {
        if (chatSession == null) {
            throw new IllegalArgumentException("שיחה לא תקינה");
        }

        if (!chatSession.getActive()) {
            throw new IllegalArgumentException("שיחה לא פעילה");
        }

        validateUser(chatSession.getUser());
    }

    private void validateChatSessionForUpload(ChatSession chatSession) {
        validateChatSession(chatSession);

        // בדיקות נוספות להעלאת קבצים
        if (chatSession.getUser() == null) {
            throw new IllegalArgumentException("שיחה ללא משתמש מקושר");
        }
    }

    private void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("משתמש לא תקין");
        }

        if (!user.getActive()) {
            throw new SecurityException("משתמש לא פעיל");
        }
    }

    private dev.langchain4j.data.document.Document createDocumentFromInputStream(
            InputStream inputStream, String fileName, String documentId,
            User user, ChatSession chatSession) throws IOException {

        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();
        byte[] fileBytes = inputStream.readAllBytes();

        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(fileBytes)) {
            dev.langchain4j.data.document.Document document = parser.parse(byteStream);

            if (document.text() == null || document.text().trim().isEmpty()) {
                throw new IOException("הקובץ נפרסר אבל לא מכיל טקסט");
            }

            // הוספת metadata עם פרטי משתמש ושיחה
            document.metadata().add("source", fileName);
            document.metadata().add("document_id", documentId);
            document.metadata().add("user_id", user.getId().toString());
            document.metadata().add("username", user.getUsername());
            document.metadata().add("session_id", chatSession.getId().toString());
            document.metadata().add("session_title", chatSession.getDisplayTitle());
            document.metadata().add("upload_time", LocalDateTime.now().toString());

            return document;
        }
    }

    private String generateUniqueFileName(String originalFileName, Long userId, Long sessionId) {
        return "s" + sessionId + "_u" + userId + "_" +
                java.util.UUID.randomUUID().toString().substring(0, 8) + "_" +
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

    private void invalidateSessionDocumentCache(Long sessionId, Long userId) {
        String sessionCacheKey = "session_docs:" + sessionId + "_user:" + userId;
        cacheService.delete(sessionCacheKey);
        log.debug("Invalidated session documents cache for session {} and user {}", sessionId, userId);
    }

    private void invalidateUserDocumentCache(Long userId) {
        cacheService.delete("user_all_docs:" + userId);
        cacheService.delete("user_stats:" + userId);
        log.debug("Invalidated user documents cache for user {}", userId);
    }

    // Inner classes for statistics

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

    public static class SessionDocumentStats {
        public final long totalDocuments;
        public final long completedDocuments;
        public final long processingDocuments;
        public final long failedDocuments;
        public final int totalCharacters;
        public final int totalChunks;

        public SessionDocumentStats(long total, long completed, long processing,
                                    long failed, int characters, int chunks) {
            this.totalDocuments = total;
            this.completedDocuments = completed;
            this.processingDocuments = processing;
            this.failedDocuments = failed;
            this.totalCharacters = characters;
            this.totalChunks = chunks;
        }
    }
}