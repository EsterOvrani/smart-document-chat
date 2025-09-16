package com.smartdocumentchat.controller;

import com.smartdocumentchat.PdfProcessingService;
import com.smartdocumentchat.QdrantVectorService;
import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.ChatSessionService;
import com.smartdocumentchat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final UserService userService;
    private final PdfProcessingService pdfProcessingService;
    private final QdrantVectorService qdrantVectorService;

    /**
     * יצירת שיחה חדשה (כפתור + בפאנל השמאלי)
     */
    @PostMapping("/sessions")
    public ResponseEntity<?> createNewSession(
            @RequestBody SessionCreationRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // Validation
            if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "כותרת השיחה היא שדה חובה"
                ));
            }

            String title = request.getTitle().trim();
            String description = request.getDescription() != null ?
                    request.getDescription().trim() : "שיחה חדשה";

            // יצירת השיחה
            ChatSession newSession = chatSessionService.createSession(currentUser, title, description);

            log.info("שיחה חדשה נוצרה: {} עבור משתמש: {}", newSession.getId(), currentUser.getUsername());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "שיחה נוצרה בהצלחה",
                    "session", buildSessionSummary(newSession)
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה ביצירת שיחה חדשה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה ביצירת השיחה"
            ));
        }
    }

    /**
     * קבלת רשימת כל השיחות למשתמש (פאנל שמאלי)
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> getUserSessions(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive,
            @RequestParam(value = "sortBy", defaultValue = "updated") String sortBy) {
        try {
            User currentUser = getCurrentUser(userId);
            List<ChatSession> sessions = chatSessionService.getUserSessions(currentUser);

            // Filter active/inactive if needed
            if (!includeInactive) {
                sessions = sessions.stream()
                        .filter(ChatSession::getActive)
                        .toList();
            }

            // Sort sessions based on parameter
            sessions = sortSessions(sessions, sortBy);

            List<Map<String, Object>> sessionList = sessions.stream()
                    .map(this::buildSessionSummary)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessions", sessionList,
                    "totalSessions", sessions.size(),
                    "userId", currentUser.getId(),
                    "username", currentUser.getUsername()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת שיחות המשתמש", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת רשימת השיחות"
            ));
        }
    }

    /**
     * עדכון שם שיחה בפאנל השמאלי
     */
    @PutMapping("/sessions/{sessionId}")
    public ResponseEntity<?> updateSessionTitle(
            @PathVariable Long sessionId,
            @RequestBody SessionUpdateRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // Validation
            if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "כותרת השיחה היא שדה חובה"
                ));
            }

            String title = request.getTitle().trim();
            String description = request.getDescription() != null ?
                    request.getDescription().trim() : null;

            ChatSession updatedSession = chatSessionService.updateSession(
                    sessionId, title, description, currentUser);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "פרטי השיחה עודכנו בהצלחה",
                    "session", buildSessionSummary(updatedSession)
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בעדכון שיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון השיחה"
            ));
        }
    }

    /**
     * מחיקת שיחה מהפאנל השמאלי
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> deleteSession(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            boolean deleted = chatSessionService.deleteSession(sessionId, currentUser);

            if (!deleted) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה לא נמצאה או שאין הרשאה למחיקה"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "שיחה נמחקה בהצלחה"
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה במחיקת שיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה במחיקת השיחה"
            ));
        }
    }

    /**
     * חיפוש שיחות בפאנל השמאלי
     */
    @GetMapping("/sessions/search")
    public ResponseEntity<?> searchSessions(
            @RequestParam("q") String searchTerm,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מונח חיפוש הוא שדה חובה"
                ));
            }

            List<ChatSession> searchResults = chatSessionService.searchSessions(currentUser, searchTerm.trim());

            List<Map<String, Object>> sessionList = searchResults.stream()
                    .map(this::buildSessionSummary)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessions", sessionList,
                    "totalResults", searchResults.size(),
                    "searchTerm", searchTerm.trim()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בחיפוש שיחות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בחיפוש השיחות"
            ));
        }
    }

    /**
     * שכפול שיחה (מהפאנל השמאלי)
     */
    @PostMapping("/sessions/{sessionId}/duplicate")
    public ResponseEntity<?> duplicateSession(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "newTitle", required = false) String newTitle) {
        try {
            User currentUser = getCurrentUser(userId);
            Optional<ChatSession> originalSessionOpt = chatSessionService.findById(sessionId);

            if (originalSessionOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שיחה מקורית לא נמצאה"
                ));
            }

            ChatSession originalSession = originalSessionOpt.get();

            // בדיקת הרשאות
            if (!isUserAuthorizedForSession(currentUser, originalSession)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשיחה זו"
                ));
            }

            // יצירת כותרת לשיחה המשוכפלת
            String duplicateTitle = newTitle != null && !newTitle.trim().isEmpty() ?
                    newTitle.trim() : "עותק של " + originalSession.getDisplayTitle();

            // יצירת שיחה חדשה
            ChatSession duplicatedSession = chatSessionService.createSession(
                    currentUser,
                    duplicateTitle,
                    "שיחה משוכפלת מ: " + originalSession.getDisplayTitle()
            );

            log.info("שיחה {} שוכפלה לשיחה {} עבור משתמש {}",
                    sessionId, duplicatedSession.getId(), currentUser.getUsername());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "שיחה שוכפלה בהצלחה",
                    "originalSession", buildSessionSummary(originalSession),
                    "duplicatedSession", buildSessionSummary(duplicatedSession)
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בשכפול שיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בשכפול השיחה"
            ));
        }
    }

    /**
     * סטטיסטיקות כלליות של השיחות
     */
    @GetMapping("/sessions/stats")
    public ResponseEntity<?> getSessionStats(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            long totalSessions = chatSessionService.countUserSessions(currentUser);
            List<ChatSession> allSessions = chatSessionService.getUserSessions(currentUser);

            long activeSessions = allSessions.stream()
                    .filter(ChatSession::getActive)
                    .count();

            long sessionsWithDocuments = allSessions.stream()
                    .filter(session -> session.getDocumentCount() > 0)
                    .count();

            // חישוב פעילות אחרונה
            Optional<LocalDateTime> lastActivity = allSessions.stream()
                    .map(ChatSession::getLastActivityAt)
                    .filter(java.util.Objects::nonNull)
                    .max(LocalDateTime::compareTo);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "stats", Map.of(
                            "totalSessions", totalSessions,
                            "activeSessions", activeSessions,
                            "inactiveSessions", totalSessions - activeSessions,
                            "sessionsWithDocuments", sessionsWithDocuments,
                            "lastActivity", lastActivity.map(dt ->
                                    dt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                            ).orElse("אין פעילות"),
                            "averageDocumentsPerSession", activeSessions > 0 ?
                                    allSessions.stream()
                                            .mapToInt(ChatSession::getDocumentCount)
                                            .average()
                                            .orElse(0.0) : 0.0
                    ),
                    "userId", currentUser.getId()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת סטטיסטיקות שיחות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת סטטיסטיקות השיחות"
            ));
        }
    }

    /**
     * בדיקת סטטוס כללי של המערכת
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

    private Map<String, Object> buildSessionSummary(ChatSession session) {
        Map<String, Object> summary = new java.util.HashMap<>();
        summary.put("id", session.getId());
        summary.put("title", session.getDisplayTitle());
        summary.put("description", session.getDescription());
        summary.put("active", session.getActive());
        summary.put("createdAt", session.getCreatedAt().format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        summary.put("lastActivityAt", session.getLastActivityAt() != null ?
                session.getLastActivityAt().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : null);
        summary.put("documentsCount", session.getDocumentCount());
        summary.put("messagesCount", session.getMessageCount());

        return summary;
    }

    private List<ChatSession> sortSessions(List<ChatSession> sessions, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "created" -> sessions.stream()
                    .sorted((s1, s2) -> s2.getCreatedAt().compareTo(s1.getCreatedAt()))
                    .toList();
            case "title" -> sessions.stream()
                    .sorted((s1, s2) -> s1.getDisplayTitle().compareToIgnoreCase(s2.getDisplayTitle()))
                    .toList();
            case "activity" -> sessions.stream()
                    .sorted((s1, s2) -> {
                        LocalDateTime activity1 = s1.getLastActivityAt() != null ?
                                s1.getLastActivityAt() : s1.getCreatedAt();
                        LocalDateTime activity2 = s2.getLastActivityAt() != null ?
                                s2.getLastActivityAt() : s2.getCreatedAt();
                        return activity2.compareTo(activity1);
                    })
                    .toList();
            default -> sessions.stream() // "updated" - default
                    .sorted((s1, s2) -> {
                        LocalDateTime updated1 = s1.getUpdatedAt() != null ?
                                s1.getUpdatedAt() : s1.getCreatedAt();
                        LocalDateTime updated2 = s2.getUpdatedAt() != null ?
                                s2.getUpdatedAt() : s2.getCreatedAt();
                        return updated2.compareTo(updated1);
                    })
                    .toList();
        };
    }

    // Request DTOs

    public static class SessionCreationRequest {
        private String title;
        private String description;

        public SessionCreationRequest() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class SessionUpdateRequest {
        private String title;
        private String description;

        public SessionUpdateRequest() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}