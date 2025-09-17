package com.smartdocumentchat.controller;

import com.smartdocumentchat.config.QdrantConfig;
import com.smartdocumentchat.service.PdfProcessingService;
import com.smartdocumentchat.service.QdrantVectorService;
import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.Document;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.ChatSessionService;
import com.smartdocumentchat.service.UserService;
import com.smartdocumentchat.service.CacheService;
import com.smartdocumentchat.service.QuestionHashService;
import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
public class ChatSessionController {

    private final ConversationalRetrievalChain conversationalRetrievalChain;
    private final PdfProcessingService pdfProcessingService;
    private final QdrantVectorService qdrantVectorService;
    private final UserService userService;
    private final ChatSessionService chatSessionService;
    private final CacheService cacheService;
    private final QuestionHashService questionHashService;
    private final QdrantConfig.SessionAwareIngestorFactory ingestorFactory;

    /**
     * קבלת פרטי השיחה הפעילה (פאנל ימין)
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<?> getActiveSessionDetails(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "includeDocuments", defaultValue = "true") boolean includeDocuments) {
        try {
            User currentUser = getCurrentUser(userId);
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);

            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession session = sessionOpt.get();

            // בדיקת הרשאות
            if (!isUserAuthorizedForSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            Map<String, Object> sessionDetails = buildSessionResponse(session, currentUser);

            // הוספת מסמכים אם נדרש
            if (includeDocuments) {
                List<Document> documents = pdfProcessingService.getDocumentsBySession(session);
                sessionDetails.put("documents", documents.stream()
                        .map(this::buildDocumentSummary)
                        .toList());
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "session", sessionDetails
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת פרטי שיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת פרטי השיחה"
            ));
        }
    }

    /**
     * העלאת קובץ PDF לשיחה הפעילה (פאנל ימין)
     */
    @PostMapping("/{sessionId}/documents")
    public ResponseEntity<?> uploadDocumentToSession(
            @PathVariable Long sessionId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // קבלת השיחה
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession chatSession = sessionOpt.get();

            // בדיקת הרשאות
            if (!isUserAuthorizedForSession(currentUser, chatSession)) {
                log.warn("משתמש {} ניסה לגשת לשיחה {} שלא שייכת לו",
                        currentUser.getId(), sessionId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // עיבוד הקובץ
            Document document = pdfProcessingService.processPdfFile(file, chatSession);

            // פינוי cache לשיחה זו
            invalidateSessionCache(chatSession.getId(), currentUser.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "הקובץ הועלה ועובד בהצלחה",
                    "document", buildDocumentSummary(document),
                    "sessionId", chatSession.getId(),
                    "uploadTime", document.getCreatedAt().format(
                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    )
            ));

        } catch (SecurityException e) {
            log.warn("שגיאת הרשאות בהעלאת קובץ: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (IllegalArgumentException e) {
            log.warn("שגיאה בתקינות הקובץ: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (IOException e) {
            log.error("שגיאה בעיבוד הקובץ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעיבוד הקובץ: " + e.getMessage()
            ));

        } catch (Exception e) {
            log.error("שגיאה כללית בהעלאת הקובץ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה לא צפויה בהעלאת הקובץ"
            ));
        }
    }

    /**
     * שליחת שאלה בשיחה הפעילה (פאנל ימין)
     */
    @PostMapping("/{sessionId}/chat")
    public ResponseEntity<?> chatInActiveSession(
            @PathVariable Long sessionId,
            @RequestBody ChatRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {

        long startTime = System.currentTimeMillis();
        boolean cacheHit = false;

        try {
            if (request.getText() == null || request.getText().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "השאלה לא יכולה להיות ריקה"
                ));
            }

            // קבלת המשתמש
            User currentUser = getCurrentUser(userId);

            // קבלת שיחה עם בדיקת הרשאות
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession chatSession = sessionOpt.get();

            // בדיקת הרשאות
            if (!isUserAuthorizedForSession(currentUser, chatSession)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // בדיקה שיש מסמכים בשיחה
            List<Document> documents = pdfProcessingService.getDocumentsBySession(chatSession);
            if (documents.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "לא נמצאו מסמכים בשיחה. אנא העלה קובץ PDF תחילה"
                ));
            }

            // יצירת hash לשאלה עם הקשר שיחה-משתמש
            List<String> documentIds = documents.stream()
                    .map(doc -> doc.getId().toString())
                    .collect(Collectors.toList());

            // **עדכון מרכזי: שימוש ב-session ID ב-hash**
            String questionHash = questionHashService.generateQuestionHash(
                    request.getText() + "_session_" + sessionId + "_user_" + currentUser.getId(),
                    documentIds);

            // בדיקה אם יש תשובה בcache
            String cachedAnswer = cacheService.getCachedQAResult(questionHash);
            String answer;

            if (cachedAnswer != null) {
                answer = cachedAnswer;
                cacheHit = true;
                log.debug("Cache HIT for question hash: {} (session: {}, user: {})",
                        questionHash, sessionId, currentUser.getId());
            } else {
                // **עדכון מרכזי: שימוש ב-session-specific retrieval chain**

                // קבלת embedding store ספציפי לשיחה
                EmbeddingStore<TextSegment> sessionEmbeddingStore =
                        qdrantVectorService.getEmbeddingStoreForSession(chatSession);

                // יצירת retrieval chain ספציפי לשיחה
                ConversationalRetrievalChain sessionChain =
                        ingestorFactory.createChainForStore(sessionEmbeddingStore);

                // עיבוד השאלה עם הקשר של השיחה
                String enhancedQuestion = enhanceQuestionForSession(request.getText(), documents,
                        currentUser, chatSession);

                // ביצוע השאלה עם ה-chain הספציפי לשיחה
                answer = sessionChain.execute(enhancedQuestion);

                // שמור בcache
                cacheService.cacheQAResult(questionHash, answer);
                log.debug("Cache MISS for question hash: {}, answer cached (session: {}, user: {})",
                        questionHash, sessionId, currentUser.getId());
            }

            // עדכון זמן פעילות השיחה
            chatSessionService.updateLastActivity(chatSession.getId());

            long processingTime = System.currentTimeMillis() - startTime;

            log.info("Question processed for session {} by user {} in {}ms (cache: {}, collection: {})",
                    chatSession.getId(), currentUser.getId(), processingTime,
                    cacheHit ? "HIT" : "MISS",
                    qdrantVectorService.generateSessionCollectionName(sessionId, currentUser.getId()));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer,
                    "originalQuestion", request.getText(),
                    "sessionId", chatSession.getId(),
                    "userId", currentUser.getId(),
                    "documentsCount", documents.size(),
                    "processingTime", processingTime,
                    "cacheHit", cacheHit,
                    "questionHash", questionHash,
                    "collectionName", qdrantVectorService.generateSessionCollectionName(sessionId, currentUser.getId())
            ));

        } catch (SecurityException e) {
            log.warn("שגיאת הרשאות בשיחה: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (Exception e) {
            log.error("שגיאה בביצוע שיחה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעיבוד השאלה: " + e.getMessage()
            ));
        }
    }

    /**
     * קבלת רשימת מסמכים בשיחה הפעילה
     */
    @GetMapping("/{sessionId}/documents")
    public ResponseEntity<?> getSessionDocuments(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);

            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession session = sessionOpt.get();

            // בדיקת הרשאות
            if (!isUserAuthorizedForSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            List<Document> documents = pdfProcessingService.getDocumentsBySession(session);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessionId", sessionId,
                    "documents", documents.stream().map(this::buildDocumentSummary).toList(),
                    "totalDocuments", documents.size()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת מסמכי השיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת מסמכי השיחה"
            ));
        }
    }

    /**
     * מחיקת מסמך מהשיחה הפעילה
     */
    @DeleteMapping("/{sessionId}/documents/{documentId}")
    public ResponseEntity<?> deleteDocumentFromSession(
            @PathVariable Long sessionId,
            @PathVariable Long documentId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // בדיקת הרשאות לשיחה
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            if (!isUserAuthorizedForSession(currentUser, sessionOpt.get())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            boolean deleted = pdfProcessingService.deleteDocument(documentId, currentUser);

            if (!deleted) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מסמך לא נמצא או שאין הרשאה למחיקה"
                ));
            }

            // פינוי cache לאחר מחיקת מסמך
            invalidateSessionCache(sessionId, currentUser.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "מסמך נמחק בהצלחה מהשיחה"
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה במחיקת מסמך {} משיחה {}", documentId, sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה במחיקת המסמך"
            ));
        }
    }

    /**
     * הפעלת שיחה (החלפת שיחה פעילה)
     */
    @PostMapping("/{sessionId}/activate")
    public ResponseEntity<?> activateSession(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            if (!isUserAuthorizedForSession(currentUser, sessionOpt.get())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // הגדר כשיחה פעילה
            chatSessionService.setActiveSession(currentUser, sessionOpt.get());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "שיחה הוגדרה כפעילה",
                    "activeSessionId", sessionId,
                    "userId", currentUser.getId()
            ));

        } catch (Exception e) {
            log.error("שגיאה בהפעלת שיחה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בהפעלת השיחה"
            ));
        }
    }

    /**
     * פינוי cache לשיחה הפעילה
     */
    @PostMapping("/{sessionId}/cache/clear")
    public ResponseEntity<?> clearActiveSessionCache(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            if (!isUserAuthorizedForSession(currentUser, sessionOpt.get())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            invalidateSessionCache(sessionId, currentUser.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Cache של השיחה נוקה בהצלחה"
            ));
        } catch (Exception e) {
            log.error("שגיאה בניקוי cache", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בניקוי cache"
            ));
        }
    }

    /**
     * קבלת מידע על collection של השיחה
     */
    @GetMapping("/{sessionId}/collection-info")
    public ResponseEntity<?> getSessionCollectionInfo(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);

            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession session = sessionOpt.get();

            if (!isUserAuthorizedForSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            String collectionName = qdrantVectorService.generateSessionCollectionName(
                    sessionId, currentUser.getId());

            boolean hasEmbeddingStore = qdrantVectorService.hasEmbeddingStoreForSession(
                    sessionId, currentUser.getId());

            List<Document> documents = pdfProcessingService.getDocumentsBySession(session);
            long processedDocs = documents.stream().filter(Document::isProcessed).count();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessionId", sessionId,
                    "collectionName", collectionName,
                    "hasEmbeddingStore", hasEmbeddingStore,
                    "totalDocuments", documents.size(),
                    "processedDocuments", processedDocs,
                    "sessionTitle", session.getDisplayTitle(),
                    "vectorStoreReady", processedDocs > 0
            ));

        } catch (Exception e) {
            log.error("שגיאה בקבלת מידע על collection", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת מידע על collection"
            ));
        }
    }

    /**
     * ניקוי collection של שיחה (למקרה של בעיות)
     */
    @PostMapping("/{sessionId}/clear-collection")
    public ResponseEntity<?> clearSessionCollection(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);

            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            if (!isUserAuthorizedForSession(currentUser, sessionOpt.get())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // הסרה מהcache (הcollection עצמו ב-Qdrant יישאר)
            qdrantVectorService.removeEmbeddingStoreForSession(sessionId, currentUser.getId());

            // פינוי cache מקושר
            invalidateSessionCache(sessionId, currentUser.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Collection cache נוקה בהצלחה",
                    "sessionId", sessionId
            ));

        } catch (Exception e) {
            log.error("שגיאה בניקוי collection", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בניקוי collection"
            ));
        }
    }

    /**
     * קבלת סטטיסטיקות collections כלליות למשתמש
     */
    @GetMapping("/collections-stats")
    public ResponseEntity<?> getCollectionsStats(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            List<ChatSession> userSessions = chatSessionService.getUserSessions(currentUser);

            int totalSessions = userSessions.size();
            int sessionsWithDocuments = 0;
            int totalProcessedDocuments = 0;

            for (ChatSession session : userSessions) {
                List<Document> docs = pdfProcessingService.getDocumentsBySession(session);
                if (!docs.isEmpty()) {
                    sessionsWithDocuments++;
                    totalProcessedDocuments += (int) docs.stream().filter(Document::isProcessed).count();
                }
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "userId", currentUser.getId(),
                    "totalSessions", totalSessions,
                    "sessionsWithDocuments", sessionsWithDocuments,
                    "totalProcessedDocuments", totalProcessedDocuments,
                    "activeCollections", qdrantVectorService.getActiveCollectionsCount(),
                    "qdrantStats", qdrantVectorService.getUsageStats()
            ));

        } catch (Exception e) {
            log.error("שגיאה בקבלת סטטיסטיקות collections", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת סטטיסטיקות collections"
            ));
        }
    }

    // Helper methods

    private User getCurrentUser(Long userId) {
        if (userId != null && userId > 0) {
            Optional<User> userOpt = userService.findById(userId);
            if (userOpt.isPresent() && userOpt.get().getActive()) {
                return userOpt.get();
            } else {
                throw new SecurityException("משתמש לא נמצא או לא פעיל");
            }
        }

        return userService.getOrCreateDemoUser();
    }

    private boolean isUserAuthorizedForSession(User user, ChatSession session) {
        return session.getUser().getId().equals(user.getId()) &&
                session.getActive() &&
                user.getActive();
    }

    private Map<String, Object> buildSessionResponse(ChatSession session, User user) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("id", session.getId());
        response.put("title", session.getDisplayTitle());
        response.put("description", session.getDescription());
        response.put("active", session.getActive());
        response.put("createdAt", session.getCreatedAt().format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        response.put("updatedAt", session.getUpdatedAt() != null ?
                session.getUpdatedAt().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : null);
        response.put("lastActivityAt", session.getLastActivityAt() != null ?
                session.getLastActivityAt().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : null);
        response.put("documentsCount", session.getDocumentCount());
        response.put("messagesCount", session.getMessageCount());
        response.put("userId", user.getId());

        return response;
    }

    private Map<String, Object> buildDocumentSummary(Document document) {
        Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("id", document.getId());
        summary.put("fileName", document.getOriginalFileName());
        summary.put("fileType", document.getFileType());
        summary.put("fileSize", document.getFileSizeFormatted());
        summary.put("status", document.getProcessingStatus());
        summary.put("uploadTime", document.getCreatedAt().format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        summary.put("processed", document.isProcessed());
        summary.put("characterCount", document.getCharacterCount());
        summary.put("chunkCount", document.getChunkCount());

        return summary;
    }

    /**
     * מתודה חדשה לשיפור השאלה עם הקשר השיחה
     */
    private String enhanceQuestionForSession(String originalQuestion, List<Document> documents,
                                             User user, ChatSession chatSession) {
        StringBuilder documentsList = new StringBuilder();
        for (Document doc : documents) {
            documentsList.append("- ").append(doc.getOriginalFileName()).append("\n");
        }

        String enhancedQuestion = String.format(
                "בהקשר של השיחה '%s' עם המסמכים הבאים שהועלו על ידי %s:\n%s\nענה על השאלה: %s\n\n" +
                        "חשוב: ענה רק על בסיס המידע שמופיע במסמכים האלה בלבד, במסגרת השיחה הזו.",
                chatSession.getDisplayTitle(),
                user.getFullName() != null ? user.getFullName() : user.getUsername(),
                documentsList.toString(),
                originalQuestion
        );

        // שיפורים ספציפיים לסוגי שאלות נפוצות
        String lowerQuestion = originalQuestion.toLowerCase();
        if (lowerQuestion.contains("השווה") || lowerQuestion.contains("compare")) {
            enhancedQuestion += "\n\nהשווה מידע בין המסמכים השונים בשיחה הזו.";
        } else if (lowerQuestion.contains("סיכום") || lowerQuestion.contains("summary")) {
            enhancedQuestion += "\n\nצור סיכום מקיף על בסיס כל המסמכים בשיחה הזו.";
        }

        return enhancedQuestion;
    }

    private void invalidateSessionCache(Long sessionId, Long userId) {
        String userSessionKey = "user_" + userId + "_session_" + sessionId;
        cacheService.delete(userSessionKey);

        log.debug("Invalidating cache for session: {} and user: {}", sessionId, userId);
    }

    // Request DTO
    public static class ChatRequest {
        private String text;

        public ChatRequest() {}

        public ChatRequest(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}