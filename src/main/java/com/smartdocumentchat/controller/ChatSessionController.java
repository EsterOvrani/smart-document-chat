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
import com.smartdocumentchat.util.AuthenticationUtils;
import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@PreAuthorize("isAuthenticated()")
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
     * קבלת פרטי השיחה הפעילה (פאנל ימין) - עם אבטחה מחוזקת
     */
    @GetMapping("/{sessionId}")
    @PreAuthorize("isAuthenticated()") // הרשאה ברמת המתודה
    public ResponseEntity<?> getActiveSessionDetails(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "includeDocuments", defaultValue = "true") boolean includeDocuments) {
        try {
            // שלב 1: בדיקת תקינות פרמטרים
            if (sessionId == null || sessionId <= 0) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מזהה שיחה לא תקין"
                ));
            }

            // שלב 2: בדיקת הרשאות משתמש
            validateUserAccess(userId);

            // שלב 3: קבלת המשתמש הנוכחי
            User currentUser = getCurrentUser(userId);

            // שלב 4: בדיקת הרשאה נוספת - אם צוין userId שונה מהמשתמש הנוכחי
            if (userId != null && !AuthenticationUtils.isCurrentUser(userId)) {
                // רק admin יכול לגשת לשיחות של משתמשים אחרים
                if (!AuthenticationUtils.hasAdminRole()) {
                    log.warn("משתמש {} ניסה לגשת לשיחה {} של משתמש {} ללא הרשאה",
                            AuthenticationUtils.getCurrentUserId().orElse(-1L), sessionId, userId);
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                            "success", false,
                            "error", "אין הרשאה לצפות בשיחות של משתמשים אחרים"
                    ));
                }
            }

            // שלב 5: קבלת השיחה מהמסד נתונים
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);

            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession session = sessionOpt.get();

            // שלב 6: בדיקת הרשאות מפורטת לשיחה הספציפית
            if (!isUserAuthorizedForSession(currentUser, session)) {
                log.warn("משתמש {} ניסה לגשת לשיחה {} ללא הרשאה",
                        currentUser.getId(), sessionId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // שלב 7: בדיקת סטטוס השיחה
            if (!session.getActive()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא פעילה"
                ));
            }

            // שלב 8: בניית מידע השיחה
            Map<String, Object> sessionDetails = buildSessionResponse(session, currentUser);

            // שלב 9: הוספת מסמכים אם נדרש (עם בדיקת הרשאות)
            if (includeDocuments) {
                try {
                    List<Document> documents = pdfProcessingService.getDocumentsBySession(session);

                    // סינון מסמכים לפי הרשאות משתמש
                    List<Document> authorizedDocuments = documents.stream()
                            .filter(doc -> isUserAuthorizedForDocument(currentUser, doc))
                            .collect(Collectors.toList());

                    sessionDetails.put("documents", authorizedDocuments.stream()
                            .map(this::buildDocumentSummary)
                            .collect(Collectors.toList()));

                    sessionDetails.put("documentsCount", authorizedDocuments.size());

                } catch (Exception e) {
                    log.error("שגיאה בקבלת מסמכי השיחה: {}", sessionId, e);
                    // לא נכשיל את כל הבקשה בגלל שגיאה במסמכים
                    sessionDetails.put("documents", List.of());
                    sessionDetails.put("documentsError", "שגיאה בטעינת המסמכים");
                }
            }

            // שלב 10: עדכון זמן גישה אחרונה (רק למשתמש הבעלים)
            if (session.getUser().getId().equals(currentUser.getId())) {
                try {
                    chatSessionService.updateLastActivity(sessionId);
                } catch (Exception e) {
                    log.warn("לא ניתן לעדכן זמן פעילות לשיחה: {}", sessionId, e);
                    // לא נכשיל את הבקשה בגלל זה
                }
            }

            // שלב 11: רישום פעילות לצורכי ביקורת
            log.info("משתמש {} צפה בשיחה {} (כולל מסמכים: {})",
                    currentUser.getUsername(), sessionId, includeDocuments);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "session", sessionDetails,
                    "accessTime", LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")),
                    "userRole", AuthenticationUtils.hasAdminRole() ? "admin" : "user"
            ));

        } catch (SecurityException e) {
            log.warn("הפרת אבטחה בניסיון גישה לשיחה {}: {}", sessionId, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            log.warn("פרמטרים לא תקינים בבקשה לשיחה {}: {}", sessionId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה כללית בקבלת פרטי שיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה פנימית בשרת"
            ));
        }
    }

    /**
     * בדיקת הרשאות למשאב משתמש - מתודה חדשה
     */
    private void validateUserAccess(Long userId) {
        if (userId != null && !AuthenticationUtils.canAccessUserResource(userId)) {
            throw new SecurityException("אין הרשאה למשאב זה");
        }
    }

    /**
     * בדיקת הרשאות למסמך ספציפי - מתודה חדשה
     */
    private boolean isUserAuthorizedForDocument(User user, Document document) {
        // המשתמש יכול לראות רק את המסמכים שלו, או admin יכול לראות הכל
        return document.getUser().getId().equals(user.getId()) ||
                AuthenticationUtils.hasAdminRole();
    }

    /**
     * בדיקת הרשאות לשיחה - מתודה מעודכנת
     */
    private boolean isUserAuthorizedForSession(User user, ChatSession session) {
        // בדיקות בסיסיות
        if (session == null || user == null || !user.getActive() || !session.getActive()) {
            return false;
        }

        // הבעלים של השיחה תמיד מורשה
        if (session.getUser().getId().equals(user.getId())) {
            return true;
        }

        // admin יכול לגשת לכל השיחות
        if (AuthenticationUtils.hasAdminRole()) {
            return true;
        }

        // אחרת - אין הרשאה
        return false;
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

            // שמירת מספר המסמכים המקורי
            int totalAvailableDocuments = documents.size();

            // **זה החלק החדש - סינון מסמכים לפי IDs אם צוין**
            if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
                documents = documents.stream()
                        .filter(doc -> request.getDocumentIds().contains(doc.getId()))
                        .collect(Collectors.toList());

                log.info("Filtered to {} documents from {} by IDs",
                        documents.size(), totalAvailableDocuments);
            }

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

            return ResponseEntity.ok(
                    Map.ofEntries(
                            Map.entry("success", true),
                            Map.entry("answer", answer),
                            Map.entry("originalQuestion", request.getText()),
                            Map.entry("sessionId", chatSession.getId()),
                            Map.entry("userId", currentUser.getId()),
                            Map.entry("documentsCount", documents.size()),
                            Map.entry("totalAvailableDocuments", totalAvailableDocuments), // חדש
                            Map.entry("documentFiltering", Map.ofEntries(
                                    Map.entry("appliedFilters", request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()),
                                    Map.entry("selectedDocumentIds", request.getDocumentIds() != null ? request.getDocumentIds() : List.of())
                            )),
                            Map.entry("processingTime", processingTime),
                            Map.entry("cacheHit", cacheHit),
                            Map.entry("questionHash", questionHash),
                            Map.entry("collectionName", qdrantVectorService.generateSessionCollectionName(sessionId, currentUser.getId()))
                    )
            );

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

        // הוסף מידע מפורט על כל מסמך
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            documentsList.append(String.format("%d. %s (ID: %d, סוג: %s, גודל: %s, %d תווים)\n",
                    i + 1,
                    doc.getOriginalFileName(),
                    doc.getId(),
                    doc.getFileType() != null ? doc.getFileType() : "unknown",
                    doc.getFileSizeFormatted(),
                    doc.getCharacterCount() != null ? doc.getCharacterCount() : 0));
        }

        String enhancedQuestion = String.format(
                "בהקשר של השיחה '%s' עם %d מסמכים:\n%s\n" +
                        "ענה על השאלה: %s\n\n" +
                        "חשוב:\n" +
                        "1. ענה רק על בסיס המידע שמופיע במסמכים האלה\n" +
                        "2. אם מידע מופיע במספר מסמכים - השווה ביניהם והזכר הבדלים\n" +
                        "3. ציין מאיזה מסמך (לפי שמו או מספרו) לקחת כל חלק מהמידע\n" +
                        "4. אם המידע לא מופיע במסמכים - ציין זאת במפורש",
                chatSession.getDisplayTitle(),
                documents.size(),
                documentsList.toString(),
                originalQuestion
        );

        // שיפורים ספציפיים לסוגי שאלות
        String lowerQuestion = originalQuestion.toLowerCase();
        if (lowerQuestion.contains("השווה") || lowerQuestion.contains("compare") ||
                lowerQuestion.contains("הבדל")) {
            enhancedQuestion += "\n\nהשווה את המידע בין המסמכים השונים והצג הבדלים ודמיון.";
        } else if (lowerQuestion.contains("סיכום") || lowerQuestion.contains("summary") ||
                lowerQuestion.contains("תמצית")) {
            enhancedQuestion += "\n\nצור סיכום מקיף המשלב מידע מכל המסמכים.";
        } else if (lowerQuestion.contains("מצא") || lowerQuestion.contains("find") ||
                lowerQuestion.contains("חפש")) {
            enhancedQuestion += "\n\nחפש במסמכים וציין בדיוק באילו מסמכים נמצא המידע.";
        }

        return enhancedQuestion;
    }

    private void invalidateSessionCache(Long sessionId, Long userId) {
        String userSessionKey = "user_" + userId + "_session_" + sessionId;
        cacheService.delete(userSessionKey);

        log.debug("Invalidating cache for session: {} and user: {}", sessionId, userId);
    }

    /**
     * חיפוש מתקדם עם filtering מרובה
     */
    @PostMapping("/{sessionId}/advanced-search")
    public ResponseEntity<?> advancedSearch(
            @PathVariable Long sessionId,
            @RequestBody AdvancedSearchRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {

        long startTime = System.currentTimeMillis();

        try {
            User currentUser = getCurrentUser(userId);
            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);

            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession chatSession = sessionOpt.get();

            if (!isUserAuthorizedForSession(currentUser, chatSession)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // קבלת כל המסמכים
            List<Document> allDocuments = pdfProcessingService.getDocumentsBySession(chatSession);
            List<Document> filteredDocuments = new ArrayList<>(allDocuments);

            Map<String, Object> filteringInfo = new HashMap<>();
            filteringInfo.put("totalDocuments", allDocuments.size());

            // סינון לפי document IDs
            if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
                Set<Long> requestedIds = new HashSet<>(request.getDocumentIds());
                filteredDocuments = filteredDocuments.stream()
                        .filter(doc -> requestedIds.contains(doc.getId()))
                        .collect(Collectors.toList());
                filteringInfo.put("documentIdFilter", request.getDocumentIds());
            }

            // סינון לפי סוג קובץ
            if (request.getFileTypes() != null && !request.getFileTypes().isEmpty()) {
                filteredDocuments = filteredDocuments.stream()
                        .filter(doc -> request.getFileTypes().contains(doc.getFileType()))
                        .collect(Collectors.toList());
                filteringInfo.put("fileTypeFilter", request.getFileTypes());
            }

            // סינון לפי גודל קובץ
            if (request.getMinFileSize() != null) {
                filteredDocuments = filteredDocuments.stream()
                        .filter(doc -> doc.getFileSize() >= request.getMinFileSize())
                        .collect(Collectors.toList());
                filteringInfo.put("minFileSize", request.getMinFileSize());
            }

            if (request.getMaxFileSize() != null) {
                filteredDocuments = filteredDocuments.stream()
                        .filter(doc -> doc.getFileSize() <= request.getMaxFileSize())
                        .collect(Collectors.toList());
                filteringInfo.put("maxFileSize", request.getMaxFileSize());
            }

            // סינון לפי תאריך העלאה
            if (request.getUploadedAfter() != null) {
                filteredDocuments = filteredDocuments.stream()
                        .filter(doc -> doc.getCreatedAt().isAfter(request.getUploadedAfter()))
                        .collect(Collectors.toList());
                filteringInfo.put("uploadedAfter", request.getUploadedAfter());
            }

            filteringInfo.put("filteredDocuments", filteredDocuments.size());

            if (filteredDocuments.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "לא נמצאו מסמכים תואמים לקריטריונים",
                        "filteringInfo", filteringInfo
                ));
            }

            // ביצוע החיפוש
            String enhancedQuestion = enhanceQuestionForSession(
                    request.getQuery(), filteredDocuments, currentUser, chatSession);

            EmbeddingStore<TextSegment> sessionEmbeddingStore =
                    qdrantVectorService.getEmbeddingStoreForSession(chatSession);

            ConversationalRetrievalChain sessionChain =
                    ingestorFactory.createChainForStore(sessionEmbeddingStore);

            String answer = sessionChain.execute(enhancedQuestion);

            long processingTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer,
                    "query", request.getQuery(),
                    "searchedDocuments", filteredDocuments.stream()
                            .map(this::buildDocumentSummary)
                            .collect(Collectors.toList()),
                    "filteringInfo", filteringInfo,
                    "processingTime", processingTime,
                    "sessionId", chatSession.getId()
            ));

        } catch (Exception e) {
            log.error("שגיאה בחיפוש מתקדם", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בחיפוש: " + e.getMessage()
            ));
        }
    }

    /**
     * עדכון מטאדטה של מסמך בשיחה
     */
    @PutMapping("/{sessionId}/documents/{documentId}")
    public ResponseEntity<?> updateDocumentMetadata(
            @PathVariable Long sessionId,
            @PathVariable Long documentId,
            @RequestBody DocumentUpdateRequest request,
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

            // קבלת המסמך ועדכונו
            Optional<Document> documentOpt = pdfProcessingService.getDocumentById(documentId, currentUser);
            if (documentOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מסמך לא נמצא"
                ));
            }

            Document document = documentOpt.get();

            // וידוא שהמסמך שייך לשיחה
            if (!document.getChatSession().getId().equals(sessionId)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מסמך לא שייך לשיחה זו"
                ));
            }

            // עדכון שדות לפי הבקשה
            boolean updated = false;
            if (request.getDisplayName() != null && !request.getDisplayName().trim().isEmpty()) {
                document.setOriginalFileName(request.getDisplayName().trim());
                updated = true;
            }

            if (updated) {
                pdfProcessingService.updateDocument(document);
                invalidateSessionCache(sessionId, currentUser.getId());

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "מטאדטה של המסמך עודכנה בהצלחה",
                        "document", buildDocumentSummary(document)
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "לא בוצעו שינויים"
                ));
            }

        } catch (Exception e) {
            log.error("שגיאה בעדכון מטאדטה של מסמך {} בשיחה {}", documentId, sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון המסמך"
            ));
        }
    }

    /**
     * קבלת מסמכים עם סינון ומיון
     */
    @GetMapping("/{sessionId}/documents/search")
    public ResponseEntity<?> searchSessionDocuments(
            @PathVariable Long sessionId,
            @RequestParam(value = "query", required = false) String searchQuery,
            @RequestParam(value = "fileType", required = false) String fileType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "sortBy", defaultValue = "uploadTime") String sortBy,
            @RequestParam(value = "sortOrder", defaultValue = "desc") String sortOrder,
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

            // קבלת כל המסמכים
            List<Document> documents = pdfProcessingService.getDocumentsBySession(session);

            // סינון לפי חיפוש
            if (searchQuery != null && !searchQuery.trim().isEmpty()) {
                String query = searchQuery.toLowerCase();
                documents = documents.stream()
                        .filter(doc -> doc.getOriginalFileName().toLowerCase().contains(query))
                        .collect(Collectors.toList());
            }

            // סינון לפי סוג קובץ
            if (fileType != null && !fileType.trim().isEmpty()) {
                documents = documents.stream()
                        .filter(doc -> fileType.equalsIgnoreCase(doc.getFileType()))
                        .collect(Collectors.toList());
            }

            // סינון לפי סטטוס
            if (status != null && !status.trim().isEmpty()) {
                Document.ProcessingStatus filterStatus = Document.ProcessingStatus.valueOf(status.toUpperCase());
                documents = documents.stream()
                        .filter(doc -> doc.getProcessingStatus() == filterStatus)
                        .collect(Collectors.toList());
            }

            // מיון
            documents = sortDocuments(documents, sortBy, sortOrder);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessionId", sessionId,
                    "documents", documents.stream().map(this::buildDocumentSummary).toList(),
                    "totalResults", documents.size(),
                    "filters", Map.of(
                            "query", searchQuery != null ? searchQuery : "",
                            "fileType", fileType != null ? fileType : "",
                            "status", status != null ? status : "",
                            "sortBy", sortBy,
                            "sortOrder", sortOrder
                    )
            ));

        } catch (Exception e) {
            log.error("שגיאה בחיפוש מסמכים בשיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בחיפוש המסמכים"
            ));
        }
    }

    /**
     * חיפוש בתוך מסמך ספציפי או קבוצת מסמכים
     */
    @PostMapping("/{sessionId}/search")
    public ResponseEntity<?> searchInDocuments(
            @PathVariable Long sessionId,
            @RequestBody DocumentSearchRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {

        long startTime = System.currentTimeMillis();

        try {
            User currentUser = getCurrentUser(userId);

            if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שאלת החיפוש לא יכולה להיות ריקה"
                ));
            }

            Optional<ChatSession> sessionOpt = chatSessionService.findById(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה"
                ));
            }

            ChatSession chatSession = sessionOpt.get();

            if (!isUserAuthorizedForSession(currentUser, chatSession)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // קבלת מסמכים לחיפוש
            List<Document> documents = pdfProcessingService.getDocumentsBySession(chatSession);

            // סינון לפי מסמכים ספציפיים אם צוין
            if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
                Set<Long> requestedIds = new HashSet<>(request.getDocumentIds());
                documents = documents.stream()
                        .filter(doc -> requestedIds.contains(doc.getId()))
                        .collect(Collectors.toList());

                log.info("מחפש במסמכים ספציפיים: {} מתוך {}",
                        documents.size(), requestedIds.size());
            }

            if (documents.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "לא נמצאו מסמכים לחיפוש"
                ));
            }

            // בניית שאלה משופרת עבור מסמכים ספציפיים
            String enhancedQuery = buildEnhancedSearchQuery(
                    request.getQuery(),
                    documents,
                    chatSession,
                    request.getSearchMode()
            );

            // קבלת embedding store ספציפי לשיחה
            EmbeddingStore<TextSegment> sessionEmbeddingStore =
                    qdrantVectorService.getEmbeddingStoreForSession(chatSession);

            // יצירת retrieval chain ספציפי לשיחה
            ConversationalRetrievalChain sessionChain =
                    ingestorFactory.createChainForStore(sessionEmbeddingStore);

            // ביצוע החיפוש
            String answer = sessionChain.execute(enhancedQuery);

            long processingTime = System.currentTimeMillis() - startTime;

            log.info("חיפוש בוצע בהצלחה: {} מסמכים, {} ms",
                    documents.size(), processingTime);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer,
                    "query", request.getQuery(),
                    "searchedDocuments", documents.stream()
                            .map(this::buildDocumentSummary)
                            .collect(Collectors.toList()),
                    "documentCount", documents.size(),
                    "searchMode", request.getSearchMode() != null ?
                            request.getSearchMode() : "semantic",
                    "processingTime", processingTime,
                    "sessionId", sessionId
            ));

        } catch (Exception e) {
            log.error("שגיאה בחיפוש במסמכים", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בביצוע החיפוש: " + e.getMessage()
            ));
        }
    }

    /**
     * בניית שאלה משופרת לחיפוש במסמכים ספציפיים
     */
    private String buildEnhancedSearchQuery(String originalQuery, List<Document> documents,
                                            ChatSession chatSession, String searchMode) {
        StringBuilder query = new StringBuilder();

        // כותרת
        query.append("חפש במסמכים הבאים");

        if (documents.size() == 1) {
            query.append(" במסמך: ").append(documents.get(0).getOriginalFileName());
        } else {
            query.append(" ב-").append(documents.size()).append(" מסמכים:\n");
            for (int i = 0; i < Math.min(documents.size(), 5); i++) {
                query.append((i + 1)).append(". ")
                        .append(documents.get(i).getOriginalFileName()).append("\n");
            }
            if (documents.size() > 5) {
                query.append("ועוד ").append(documents.size() - 5).append(" מסמכים...\n");
            }
        }

        query.append("\n");

        // הוראות חיפוש לפי מצב
        if ("exact".equals(searchMode)) {
            query.append("חפש התאמה מדויקת לטקסט: \"").append(originalQuery).append("\"\n");
            query.append("החזר את הקטעים המדויקים שמכילים את הטקסט הזה.\n");
        } else if ("keyword".equals(searchMode)) {
            query.append("חפש מילות מפתח: ").append(originalQuery).append("\n");
            query.append("מצא קטעים שמכילים את מילות המפתח האלה.\n");
        } else { // semantic - ברירת מחדל
            query.append("שאלה: ").append(originalQuery).append("\n");
            query.append("ענה על בסיס ההקשר והמשמעות של השאלה.\n");
        }

        query.append("\nחשוב:\n");
        query.append("1. ציין מאיזה מסמך לקחת כל מידע (בשם המסמך)\n");
        query.append("2. אם המידע לא קיים במסמכים - ציין זאת במפורש\n");
        query.append("3. אם יש מידע דומה במספר מסמכים - ציין את ההבדלים\n");

        return query.toString();
    }


    // Helper method למיון מסמכים
    private List<Document> sortDocuments(List<Document> documents, String sortBy, String sortOrder) {
        Comparator<Document> comparator;

        switch (sortBy.toLowerCase()) {
            case "name":
                comparator = Comparator.comparing(Document::getOriginalFileName);
                break;
            case "size":
                comparator = Comparator.comparing(Document::getFileSize);
                break;
            case "status":
                comparator = Comparator.comparing(Document::getProcessingStatus);
                break;
            case "uploadtime":
            default:
                comparator = Comparator.comparing(Document::getCreatedAt);
                break;
        }

        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }

        return documents.stream().sorted(comparator).collect(Collectors.toList());
    }

    // Request DTO לחיפוש במסמכים
    public static class DocumentSearchRequest {
        private String query;
        private List<Long> documentIds;  // מסמכים ספציפיים לחיפוש
        private String searchMode;        // "semantic", "keyword", "exact"
        private Integer maxResults;

        public DocumentSearchRequest() {}

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }

        public List<Long> getDocumentIds() { return documentIds; }
        public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }

        public String getSearchMode() { return searchMode; }
        public void setSearchMode(String searchMode) { this.searchMode = searchMode; }

        public Integer getMaxResults() { return maxResults; }
        public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }
    }

    // Request DTO לעדכון מסמך
    public static class DocumentUpdateRequest {
        private String displayName;

        public DocumentUpdateRequest() {}

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
    }

    // DTO חדש לחיפוש מתקדם
    public static class AdvancedSearchRequest {
        private String query;
        private List<Long> documentIds;
        private List<String> fileTypes;
        private Long minFileSize;
        private Long maxFileSize;
        private LocalDateTime uploadedAfter;
        private Double minRelevanceScore;
        private Integer maxResults;

        public AdvancedSearchRequest() {}

        // Getters and Setters
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }

        public List<Long> getDocumentIds() { return documentIds; }
        public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }

        public List<String> getFileTypes() { return fileTypes; }
        public void setFileTypes(List<String> fileTypes) { this.fileTypes = fileTypes; }

        public Long getMinFileSize() { return minFileSize; }
        public void setMinFileSize(Long minFileSize) { this.minFileSize = minFileSize; }

        public Long getMaxFileSize() { return maxFileSize; }
        public void setMaxFileSize(Long maxFileSize) { this.maxFileSize = maxFileSize; }

        public LocalDateTime getUploadedAfter() { return uploadedAfter; }
        public void setUploadedAfter(LocalDateTime uploadedAfter) { this.uploadedAfter = uploadedAfter; }

        public Double getMinRelevanceScore() { return minRelevanceScore; }
        public void setMinRelevanceScore(Double minRelevanceScore) { this.minRelevanceScore = minRelevanceScore; }

        public Integer getMaxResults() { return maxResults; }
        public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }
    }

    // Request DTO
    public static class ChatRequest {
        private String text;
        private List<Long> documentIds;        // חדש - לבחירת מסמכים ספציפיים
        private String searchMode;             // חדש - סוג החיפוש

        public ChatRequest() {}

        public ChatRequest(String text) {
            this.text = text;
        }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public List<Long> getDocumentIds() { return documentIds; }
        public void setDocumentIds(List<Long> documentIds) { this.documentIds = documentIds; }

        public String getSearchMode() { return searchMode; }
        public void setSearchMode(String searchMode) { this.searchMode = searchMode; }
    }
}