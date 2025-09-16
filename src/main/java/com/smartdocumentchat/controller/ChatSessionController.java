package com.smartdocumentchat.controller;

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

            // יצירת hash לשאלה עם הקשר משתמש
            List<String> documentIds = documents.stream()
                    .map(doc -> doc.getId().toString())
                    .collect(Collectors.toList());

            String questionHash = questionHashService.generateQuestionHash(
                    request.getText() + "_user_" + currentUser.getId(), documentIds);

            // בדיקה אם יש תשובה בcache
            String cachedAnswer = cacheService.getCachedQAResult(questionHash);
            String answer;

            if (cachedAnswer != null) {
                answer = cachedAnswer;
                cacheHit = true;
                log.debug("Cache HIT for question hash: {} (user: {})", questionHash, currentUser.getId());
            } else {
                // אם אין בcache, עבד את השאלה
                String enhancedQuestion = enhanceQuestion(request.getText(), documents, currentUser);
                answer = conversationalRetrievalChain.execute(enhancedQuestion);

                // שמור בcache
                cacheService.cacheQAResult(questionHash, answer);
                log.debug("Cache MISS for question hash: {}, answer cached (user: {})",
                        questionHash, currentUser.getId());
            }

            // עדכון זמן פעילות השיחה
            chatSessionService.updateLastActivity(chatSession.getId());

            long processingTime = System.currentTimeMillis() - startTime;

            log.debug("Question processed for session {} by user {} in {}ms (cache: {})",
                    chatSession.getId(), currentUser.getId(), processingTime, cacheHit ? "HIT" : "MISS");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer,
                    "originalQuestion", request.getText(),
                    "sessionId", chatSession.getId(),
                    "userId", currentUser.getId(),
                    "documentsCount", documents.size(),
                    "processingTime", processingTime,
                    "cacheHit", cacheHit,
                    "questionHash", questionHash
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

    private String enhanceQuestion(String originalQuestion, List<Document> documents, User user) {
        StringBuilder documentsList = new StringBuilder();
        for (Document doc : documents) {
            documentsList.append("- ").append(doc.getOriginalFileName()).append("\n");
        }

        String enhancedQuestion = String.format(
                "בהתבסס על המסמכים הבאים שהועלו למערכת על ידי %s:\n%s\nענה על השאלה: %s\n\n" +
                        "חשוב: ענה רק על בסיס המידע שמופיע במסמכים האלה בלבד.",
                user.getFullName() != null ? user.getFullName() : user.getUsername(),
                documentsList.toString(),
                originalQuestion
        );

        // שיפורים ספציפיים לסוגי שאלות נפוצות
        String lowerQuestion = originalQuestion.toLowerCase();
        if (lowerQuestion.contains("שם") || lowerQuestion.contains("name")) {
            enhancedQuestion += "\n\nחפש שמות של אנשים, חברות, פרויקטים או טכנולוגיות המוזכרים במסמכים.";
        } else if (lowerQuestion.contains("פרויקט") || lowerQuestion.contains("project")) {
            enhancedQuestion += "\n\nחפש מידע על פרויקטים, עבודות או התפתחות מקצועית המוזכרת במסמכים.";
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