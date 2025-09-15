package com.smartdocumentchat.controller;

import com.smartdocumentchat.PdfProcessingService;
import com.smartdocumentchat.QdrantVectorService;
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
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ConversationalRetrievalChain conversationalRetrievalChain;
    private final PdfProcessingService pdfProcessingService;
    private final QdrantVectorService qdrantVectorService;
    private final UserService userService;
    private final ChatSessionService chatSessionService;
    private final CacheService cacheService;
    private final QuestionHashService questionHashService;

    /**
     * העלאה ועיבוד קובץ PDF חדש לשיחה עם תמיכה בהקשר משתמש
     */
    @PostMapping("/upload-pdf")
    public ResponseEntity<?> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            // קבלת המשתמש - עם תמיכה בהעברת userId או שימוש בדמו
            User currentUser = getCurrentUser(userId);

            // קבלת או יצירת שיחה
            ChatSession chatSession;
            if (sessionId != null) {
                Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
                if (sessionOpt.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "שיחה לא נמצאה"
                    ));
                }

                chatSession = sessionOpt.get();

                // בדיקת הרשאות - וודא שהשיחה שייכת למשתמש
                if (!isUserAuthorizedForSession(currentUser, chatSession)) {
                    log.warn("משתמש {} ניסה לגשת לשיחה {} שלא שייכת לו",
                            currentUser.getId(), sessionId);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                            "success", false,
                            "error", "אין הרשאה לשיחה זו"
                    ));
                }
            } else {
                // צור שיחה חדשה
                String sessionTitle = "שיחה עם " + file.getOriginalFilename();
                chatSession = chatSessionService.createSession(currentUser, sessionTitle, "שיחה לעיבוד מסמך");
            }

            // עיבוד הקובץ
            Document document = pdfProcessingService.processPdfFile(file, chatSession);

            // פינוי cache לשיחה זו
            invalidateSessionCache(chatSession.getId(), currentUser.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "הקובץ הועלה ועובד בהצלחה",
                    "documentId", document.getId(),
                    "sessionId", chatSession.getId(),
                    "fileName", document.getOriginalFileName(),
                    "userId", currentUser.getId(),
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
     * שאילת שאלה על מסמכי השיחה עם הקשר משתמש
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chatWithDocuments(
            @RequestBody ChatRequest request,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
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
            ChatSession chatSession;
            if (sessionId != null) {
                Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
                if (sessionOpt.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "שיחה לא נמצאה"
                    ));
                }

                chatSession = sessionOpt.get();

                // בדיקת הרשאות
                if (!isUserAuthorizedForSession(currentUser, chatSession)) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                            "success", false,
                            "error", "אין הרשאה לשיחה זו"
                    ));
                }
            } else {
                // נסה לקבל את השיחה האחרונה של המשתמש
                Optional<ChatSession> lastSession = chatSessionService.getLastSession(currentUser);
                if (lastSession.isEmpty()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "לא נמצאה שיחה פעילה. אנא העלה קובץ PDF תחילה"
                    ));
                }
                chatSession = lastSession.get();
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
     * קבלת מידע על שיחה ספציפית עם בדיקת הרשאות
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSessionInfo(
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
                    "session", Map.of(
                            "id", session.getId(),
                            "title", session.getDisplayTitle(),
                            "description", session.getDescription(),
                            "userId", session.getUser().getId(),
                            "createdAt", session.getCreatedAt().format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                            ),
                            "documentsCount", documents.size(),
                            "documents", documents.stream().map(doc -> Map.of(
                                    "id", doc.getId(),
                                    "fileName", doc.getOriginalFileName(),
                                    "status", doc.getProcessingStatus(),
                                    "uploadTime", doc.getCreatedAt().format(
                                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                                    )
                            )).toList()
                    )
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת מידע שיחה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת מידע השיחה"
            ));
        }
    }

    /**
     * קבלת רשימת שיחות המשתמש עם data isolation
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> getUserSessions(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);
            List<ChatSession> sessions = chatSessionService.getUserSessions(currentUser);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "userId", currentUser.getId(),
                    "username", currentUser.getUsername(),
                    "sessions", sessions.stream().map(session -> Map.of(
                            "id", session.getId(),
                            "title", session.getDisplayTitle(),
                            "description", session.getDescription(),
                            "createdAt", session.getCreatedAt().format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                            ),
                            "documentsCount", session.getDocumentCount(),
                            "messagesCount", session.getMessageCount()
                    )).toList(),
                    "totalSessions", sessions.size()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת שיחות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת רשימת השיחות"
            ));
        }
    }

    /**
     * בדיקת סטטוס המערכת עם הקשר משתמש
     */
    @GetMapping("/status")
    public ResponseEntity<?> getSystemStatus(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);
            long userSessions = chatSessionService.countUserSessions(currentUser);
            PdfProcessingService.DocumentStats stats = pdfProcessingService.getUserDocumentStats(currentUser);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "systemStatus", "active",
                    "user", Map.of(
                            "id", currentUser.getId(),
                            "username", currentUser.getUsername(),
                            "fullName", currentUser.getFullName(),
                            "sessionsCount", userSessions
                    ),
                    "documents", Map.of(
                            "total", stats.totalDocuments,
                            "processing", stats.processingDocuments,
                            "failed", stats.failedDocuments
                    ),
                    "qdrantCollection", qdrantVectorService.getCurrentCollectionName(),
                    "chunkingSettings", Map.of(
                            "chunkSize", 1200,
                            "chunkOverlap", 200,
                            "maxResults", 5
                    ),
                    "cacheStatus", Map.of(
                            "redisConnected", true,
                            "cacheEnabled", true
                    )
            ));

        } catch (Exception e) {
            log.error("שגיאה בקבלת סטטוס המערכת", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת סטטוס המערכת: " + e.getMessage()
            ));
        }
    }

    /**
     * פינוי cache לשיחה ספציפית עם בדיקת הרשאות
     */
    @PostMapping("/cache/clear/{sessionId}")
    public ResponseEntity<?> clearSessionCache(
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

    @PostMapping("/session/{sessionId}/activate")
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

    // Private helper methods

    /**
     * קבלת המשתמש הנוכחי (זמני עד שלב 9 - Authentication)
     */
    private User getCurrentUser(Long userId) {
        if (userId != null && userId > 0) {
            Optional<User> userOpt = userService.findById(userId);
            if (userOpt.isPresent() && userOpt.get().getActive()) {
                return userOpt.get();
            } else {
                log.warn("ניסיון גישה עם userId לא תקין או משתמש לא פעיל: {}", userId);
                throw new SecurityException("משתמש לא נמצא או לא פעיל");
            }
        }

        // אם לא הועבר userId, השתמש במשתמש הדמו (זמני)
        return userService.getOrCreateDemoUser();
    }

    /**
     * בדיקת הרשאות - וודא שהמשתמש מורשה לגשת לשיחה
     */
    private boolean isUserAuthorizedForSession(User user, ChatSession session) {
        if (user == null || session == null) {
            return false;
        }

        // וודא שהשיחה שייכת למשתמש ושהיא פעילה
        return session.getUser().getId().equals(user.getId()) &&
                session.getActive() &&
                user.getActive();
    }

    /**
     * שיפור השאלה עם הקשר משתמש
     */
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

    /**
     * פינוי cache של שיחה ספציפית עם הקשר משתמש
     */
    private void invalidateSessionCache(Long sessionId, Long userId) {
        // נקה cache ספציפי למשתמש ולשיחה
        String userSessionKey = "user_" + userId + "_session_" + sessionId;
        cacheService.delete(userSessionKey);

        log.debug("Invalidating cache for session: {} and user: {}", sessionId, userId);
    }

    /**
     * מחלקה פנימית לבקשת צ'אט
     */
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