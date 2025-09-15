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
     * העלאה ועיבוד קובץ PDF חדש לשיחה
     */
    @PostMapping("/upload-pdf")
    public ResponseEntity<?> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        try {
            // קבלת או יצירת משתמש דמה (זמני עד שלב 9)
            User demoUser = userService.getOrCreateDemoUser();

            // קבלת או יצירת שיחה
            ChatSession chatSession;
            if (sessionId != null) {
                Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
                if (sessionOpt.isEmpty() || !chatSessionService.isSessionOwnedByUser(sessionId, demoUser)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "שיחה לא נמצאה או שאין הרשאה"
                    ));
                }
                chatSession = sessionOpt.get();
            } else {
                // צור שיחה חדשה
                String sessionTitle = "שיחה עם " + file.getOriginalFilename();
                chatSession = chatSessionService.createSession(demoUser, sessionTitle, "שיחה לעיבוד מסמך");
            }

            // עיבוד הקובץ
            Document document = pdfProcessingService.processPdfFile(file, chatSession);

            // אחרי הוספת מסמך חדש, נקה cache של Q&A לשיחה זו
            invalidateSessionCache(chatSession.getId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "הקובץ הועלה ועובד בהצלחה",
                    "documentId", document.getId(),
                    "sessionId", chatSession.getId(),
                    "fileName", document.getOriginalFileName(),
                    "uploadTime", document.getCreatedAt().format(
                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    )
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
     * שאילת שאלה על מסמכי השיחה
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chatWithDocuments(
            @RequestBody ChatRequest request,
            @RequestParam(value = "sessionId", required = false) Long sessionId) {

        long startTime = System.currentTimeMillis();
        boolean cacheHit = false;

        try {
            if (request.getText() == null || request.getText().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "השאלה לא יכולה להיות ריקה"
                ));
            }

            // קבלת משתמש דמה
            User demoUser = userService.getOrCreateDemoUser();

            // קבלת שיחה
            ChatSession chatSession;
            if (sessionId != null) {
                Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
                if (sessionOpt.isEmpty() || !chatSessionService.isSessionOwnedByUser(sessionId, demoUser)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "success", false,
                            "error", "שיחה לא נמצאה או שאין הרשאה"
                    ));
                }
                chatSession = sessionOpt.get();
            } else {
                // נסה לקבל את השיחה האחרונה
                Optional<ChatSession> lastSession = chatSessionService.getLastSession(demoUser);
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

            // יצירת hash לשאלה
            List<String> documentIds = documents.stream()
                    .map(doc -> doc.getId().toString())
                    .collect(Collectors.toList());

            String questionHash = questionHashService.generateQuestionHash(request.getText(), documentIds);

            // בדיקה אם יש תשובה בcache
            String cachedAnswer = cacheService.getCachedQAResult(questionHash);
            String answer;

            if (cachedAnswer != null) {
                answer = cachedAnswer;
                cacheHit = true;
                log.debug("Cache HIT for question hash: {}", questionHash);
            } else {
                // אם אין בcache, עבד את השאלה
                String enhancedQuestion = enhanceQuestion(request.getText(), documents);
                answer = conversationalRetrievalChain.execute(enhancedQuestion);

                // שמור בcache
                cacheService.cacheQAResult(questionHash, answer);
                log.debug("Cache MISS for question hash: {}, answer cached", questionHash);
            }

            // עדכון זמן פעילות השיחה
            chatSessionService.updateLastActivity(chatSession.getId());

            long processingTime = System.currentTimeMillis() - startTime;

            log.debug("Question processed for session {} in {}ms (cache: {})",
                    chatSession.getId(), processingTime, cacheHit ? "HIT" : "MISS");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer,
                    "originalQuestion", request.getText(),
                    "sessionId", chatSession.getId(),
                    "documentsCount", documents.size(),
                    "processingTime", processingTime,
                    "cacheHit", cacheHit,
                    "questionHash", questionHash
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
     * קבלת מידע על שיחה ספציפית
     */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSessionInfo(@PathVariable Long sessionId) {
        try {
            User demoUser = userService.getOrCreateDemoUser();
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);

            if (sessionOpt.isEmpty() || !chatSessionService.isSessionOwnedByUser(sessionId, demoUser)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession session = sessionOpt.get();
            List<Document> documents = pdfProcessingService.getDocumentsBySession(session);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "session", Map.of(
                            "id", session.getId(),
                            "title", session.getDisplayTitle(),
                            "description", session.getDescription(),
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
        } catch (Exception e) {
            log.error("שגיאה בקבלת מידע שיחה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת מידע השיחה"
            ));
        }
    }

    /**
     * קבלת רשימת שיחות המשתמש
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> getUserSessions() {
        try {
            User demoUser = userService.getOrCreateDemoUser();
            List<ChatSession> sessions = chatSessionService.getUserSessions(demoUser);

            return ResponseEntity.ok(Map.of(
                    "success", true,
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
        } catch (Exception e) {
            log.error("שגיאה בקבלת שיחות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת רשימת השיחות"
            ));
        }
    }

    /**
     * בדיקת סטטוס המערכת
     */
    @GetMapping("/status")
    public ResponseEntity<?> getSystemStatus() {
        try {
            User demoUser = userService.getOrCreateDemoUser();
            long userSessions = chatSessionService.countUserSessions(demoUser);
            PdfProcessingService.DocumentStats stats = pdfProcessingService.getUserDocumentStats(demoUser);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "systemStatus", "active",
                    "user", Map.of(
                            "username", demoUser.getUsername(),
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
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת סטטוס המערכת: " + e.getMessage()
            ));
        }
    }

    /**
     * פינוי cache לשיחה ספציפית
     */
    @PostMapping("/cache/clear/{sessionId}")
    public ResponseEntity<?> clearSessionCache(@PathVariable Long sessionId) {
        try {
            User demoUser = userService.getOrCreateDemoUser();

            if (!chatSessionService.isSessionOwnedByUser(sessionId, demoUser)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            invalidateSessionCache(sessionId);

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
     * שיפור השאלה לדיוק טוב יותר
     */
    private String enhanceQuestion(String originalQuestion, List<Document> documents) {
        StringBuilder documentsList = new StringBuilder();
        for (Document doc : documents) {
            documentsList.append("- ").append(doc.getOriginalFileName()).append("\n");
        }

        String enhancedQuestion = String.format(
                "בהתבסס על המסמכים הבאים שהועלו למערכת:\n%s\nענה על השאלה: %s\n\n" +
                        "חשוב: ענה רק על בסיס המידע שמופיע במסמכים האלה בלבד.",
                documentsList.toString(), originalQuestion
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
     * פינוי cache של שיחה ספציפית
     */
    private void invalidateSessionCache(Long sessionId) {
        // כרגע לא מימשנו cache לפי session, אבל נוסיף בעתיד
        log.debug("Invalidating cache for session: {}", sessionId);
    }

    @PostMapping("/session/{sessionId}/activate")
    public ResponseEntity<?> activateSession(@PathVariable Long sessionId) {
        try {
            User demoUser = userService.getOrCreateDemoUser();

            if (!chatSessionService.isSessionOwnedByUser(sessionId, demoUser)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            // הגדר כשיחה פעילה
            chatSessionService.setActiveSession(demoUser, sessionOpt.get());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "שיחה הוגדרה כפעילה",
                    "activeSessionId", sessionId
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