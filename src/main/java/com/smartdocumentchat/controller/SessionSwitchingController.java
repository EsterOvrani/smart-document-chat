package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.SessionSwitchingService;
import com.smartdocumentchat.service.UserService;
import com.smartdocumentchat.service.ChatSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/session-switch")
@RequiredArgsConstructor
@Slf4j
public class SessionSwitchingController {

    private final SessionSwitchingService sessionSwitchingService;
    private final UserService userService;
    private final ChatSessionService chatSessionService;

    /**
     * החלפה לשיחה אחרת
     */
    @PostMapping("/switch/{targetSessionId}")
    public ResponseEntity<?> switchToSession(
            @PathVariable Long targetSessionId,
            @RequestBody(required = false) SwitchSessionRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);
            Long currentSessionId = request != null ? request.getCurrentSessionId() : null;

            SessionSwitchingService.SessionSwitchResult result =
                    sessionSwitchingService.switchToSession(currentUser, targetSessionId, currentSessionId);

            if (!result.success) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", result.error
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "החלפה בוצעה בהצלחה",
                    "session", buildSessionResponse(result.session),
                    "sessionState", Map.of(
                            "sessionId", result.sessionState.sessionId,
                            "title", result.sessionState.title,
                            "lastAccess", result.sessionState.lastAccess.format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                            "documentCount", result.sessionState.documentCount,
                            "messageCount", result.sessionState.messageCount
                    ),
                    "switchedAt", java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בהחלפת שיחה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בהחלפת השיחה"
            ));
        }
    }

    /**
     * קבלת רשימת שיחות זמינות להחלפה
     */
    @GetMapping("/available")
    public ResponseEntity<?> getAvailableSessions(
            @RequestParam(value = "currentSessionId", required = false) Long currentSessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            List<SessionSwitchingService.SessionSwitchOption> availableSessions =
                    sessionSwitchingService.getAvailableSessions(currentUser, currentSessionId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "availableSessions", availableSessions.stream().map(option -> Map.of(
                            "sessionId", option.sessionId,
                            "title", option.title,
                            "description", option.description,
                            "lastActivity", option.lastActivity.format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                            "documentCount", option.documentCount,
                            "messageCount", option.messageCount,
                            "hasVectorData", option.hasVectorData,
                            "canSwitch", true
                    )).toList(),
                    "totalAvailable", availableSessions.size(),
                    "currentSessionId", currentSessionId
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת שיחות זמינות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת השיחות הזמינות"
            ));
        }
    }

    /**
     * החלפה מהירה לשיחות אחרונות (לקיצורי מקלדת)
     */
    @GetMapping("/recent")
    public ResponseEntity<?> getRecentSessions(
            @RequestParam(value = "limit", defaultValue = "5") int limit,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            List<SessionSwitchingService.SessionSwitchOption> recentSessions =
                    sessionSwitchingService.getRecentSessions(currentUser, limit);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "recentSessions", recentSessions.stream().map(option -> Map.of(
                            "sessionId", option.sessionId,
                            "title", option.title,
                            "lastActivity", option.lastActivity.format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                            "documentCount", option.documentCount,
                            "messageCount", option.messageCount,
                            "hasVectorData", option.hasVectorData
                    )).toList(),
                    "limit", limit
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת שיחות אחרונות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת השיחות האחרונות"
            ));
        }
    }

    /**
     * החלפה לשיחה הקודמת
     */
    @PostMapping("/previous")
    public ResponseEntity<?> switchToPreviousSession(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            SessionSwitchingService.SessionSwitchResult result =
                    sessionSwitchingService.switchToPreviousSession(currentUser);

            if (!result.success) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", result.error
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "עבר לשיחה הקודמת",
                    "session", buildSessionResponse(result.session),
                    "sessionState", Map.of(
                            "sessionId", result.sessionState.sessionId,
                            "title", result.sessionState.title,
                            "lastAccess", result.sessionState.lastAccess.format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                            "documentCount", result.sessionState.documentCount,
                            "messageCount", result.sessionState.messageCount
                    )
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה במעבר לשיחה קודמת", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה במעבר לשיחה הקודמת"
            ));
        }
    }

    /**
     * יצירת שיחה חדשה והחלפה אליה
     */
    @PostMapping("/create-and-switch")
    public ResponseEntity<?> createAndSwitchToNewSession(
            @RequestBody CreateAndSwitchRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            if (request.getTitle() == null || request.getTitle().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "כותרת השיחה היא שדה חובה"
                ));
            }

            SessionSwitchingService.SessionSwitchResult result =
                    sessionSwitchingService.createAndSwitchToNewSession(
                            currentUser,
                            request.getTitle().trim(),
                            request.getDescription() != null ? request.getDescription().trim() : null,
                            request.getCurrentSessionId()
                    );

            if (!result.success) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", result.error
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "שיחה חדשה נוצרה והפעלה",
                    "session", buildSessionResponse(result.session),
                    "sessionState", Map.of(
                            "sessionId", result.sessionState.sessionId,
                            "title", result.sessionState.title,
                            "lastAccess", result.sessionState.lastAccess.format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")),
                            "documentCount", result.sessionState.documentCount,
                            "messageCount", result.sessionState.messageCount
                    )
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה ביצירת והחלפה לשיחה חדשה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה ביצירת שיחה חדשה"
            ));
        }
    }

    /**
     * קבלת מידע על מצב החלפת השיחות
     */
    @GetMapping("/status")
    public ResponseEntity<?> getSwitchingStatus(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // קבלת השיחה הפעילה
            Optional<ChatSession> activeSessionOpt =
                    getCurrentActiveSession(currentUser); // נצטרך להוסיף מתודה זו

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "switchingStatus", Map.of(
                            "hasActiveSession", activeSessionOpt.isPresent(),
                            "activeSession", activeSessionOpt.map(this::buildSessionResponse).orElse(null),
                            "canSwitchToRecent", true,
                            "canCreateNew", true,
                            "maxRecentSessions", 10
                    ),
                    "userId", currentUser.getId(),
                    "username", currentUser.getUsername()
            ));

        } catch (Exception e) {
            log.error("שגיאה בקבלת מצב החלפת שיחות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת מצב החלפת השיחות"
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

    private Map<String, Object> buildSessionResponse(com.smartdocumentchat.entity.ChatSession session) {
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("id", session.getId());
        response.put("title", session.getDisplayTitle());
        response.put("description", session.getDescription());
        response.put("active", session.getActive());
        response.put("createdAt", session.getCreatedAt().format(
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        response.put("lastActivityAt", session.getLastActivityAt() != null ?
                session.getLastActivityAt().format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : null);
        response.put("documentsCount", session.getDocumentCount());
        response.put("messagesCount", session.getMessageCount());

        return response;
    }

    // נוסיף מתודה זו בהמשך - כרגע פשוט נחזיר empty
    private Optional<com.smartdocumentchat.entity.ChatSession> getCurrentActiveSession(User user) {
        try {
            // נשתמש ב-ChatSessionService לקבלת השיחה הפעילה
            return chatSessionService.getActiveSession(user);
        } catch (Exception e) {
            log.error("שגיאה בקבלת שיחה פעילה למשתמש {}", user.getId(), e);
            return Optional.empty();
        }
    }

    // Request DTOs

    public static class SwitchSessionRequest {
        private Long currentSessionId;

        public SwitchSessionRequest() {}

        public Long getCurrentSessionId() { return currentSessionId; }
        public void setCurrentSessionId(Long currentSessionId) { this.currentSessionId = currentSessionId; }
    }

    public static class CreateAndSwitchRequest {
        private String title;
        private String description;
        private Long currentSessionId;

        public CreateAndSwitchRequest() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Long getCurrentSessionId() { return currentSessionId; }
        public void setCurrentSessionId(Long currentSessionId) { this.currentSessionId = currentSessionId; }
    }
}