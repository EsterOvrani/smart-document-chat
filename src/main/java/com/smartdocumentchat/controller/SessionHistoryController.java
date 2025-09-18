package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.SessionHistoryService;
import com.smartdocumentchat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/session-history")
@RequiredArgsConstructor
@Slf4j
public class SessionHistoryController {

    private final SessionHistoryService sessionHistoryService;
    private final UserService userService;

    // DateTimeFormatter constants
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_TIME_SECONDS_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /**
     * קבלת היסטוריית פעילות של שיחה ספציפית
     */
    @GetMapping("/{sessionId}/activity")
    public ResponseEntity<?> getSessionActivityHistory(
            @PathVariable Long sessionId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            SessionHistoryService.SessionActivityHistory history =
                    sessionHistoryService.getSessionActivityHistory(sessionId, currentUser);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessionActivity", createSessionActivityMap(history)
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
            log.error("שגיאה בקבלת היסטוריית שיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת היסטוריית השיחה"
            ));
        }
    }

    /**
     * קבלת היסטוריה של כל השיחות של משתמש
     */
    @GetMapping("/user-sessions")
    public ResponseEntity<?> getUserSessionsHistory(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "sortBy", defaultValue = "updated") String sortBy,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            List<SessionHistoryService.SessionSummary> sessionsHistory =
                    sessionHistoryService.getUserSessionsHistory(currentUser, limit, sortBy);

            List<Map<String, Object>> formattedHistory = sessionsHistory.stream()
                    .map(this::formatSessionSummary)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sessionsHistory", formattedHistory,
                    "totalSessions", formattedHistory.size(),
                    "limit", limit,
                    "sortBy", sortBy,
                    "userId", currentUser.getId(),
                    "username", currentUser.getUsername()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת היסטוריית שיחות משתמש", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת היסטוריית השיחות"
            ));
        }
    }

    /**
     * חיפוש בהיסטוריית השיחות
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchSessionsHistory(
            @RequestParam("q") String searchTerm,
            @RequestParam(value = "fromDate", required = false) String fromDateStr,
            @RequestParam(value = "toDate", required = false) String toDateStr,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            if (searchTerm == null || searchTerm.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מונח חיפוש הוא שדה חובה"
                ));
            }

            // פרסור תאריכים אם נתונים
            LocalDateTime fromDate = null;
            LocalDateTime toDate = null;

            try {
                if (fromDateStr != null && !fromDateStr.trim().isEmpty()) {
                    fromDate = LocalDateTime.parse(fromDateStr + "T00:00:00");
                }
                if (toDateStr != null && !toDateStr.trim().isEmpty()) {
                    toDate = LocalDateTime.parse(toDateStr + "T23:59:59");
                }
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "פורמט תאריך לא תקין. השתמש ב-YYYY-MM-DD"
                ));
            }

            List<SessionHistoryService.SessionSummary> searchResults =
                    sessionHistoryService.searchSessionsHistory(currentUser, searchTerm.trim(), fromDate, toDate);

            List<Map<String, Object>> formattedResults = searchResults.stream()
                    .map(this::formatSessionSummary)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "searchResults", formattedResults,
                    "totalResults", formattedResults.size(),
                    "searchTerm", searchTerm.trim(),
                    "fromDate", fromDateStr,
                    "toDate", toDateStr
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בחיפוש היסטוריית שיחות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בחיפוש השיחות"
            ));
        }
    }

    /**
     * עדכון מטאדטה של שיחה
     */
    @PutMapping("/{sessionId}/metadata")
    public ResponseEntity<?> updateSessionMetadata(
            @PathVariable Long sessionId,
            @RequestBody SessionMetadataUpdateRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            com.smartdocumentchat.entity.ChatSession updatedSession = sessionHistoryService.updateSessionMetadata(
                    sessionId,
                    request.getTitle(),
                    request.getDescription(),
                    request.getAdditionalMetadata(),
                    currentUser
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "מטאדטה עודכנה בהצלחה",
                    "session", Map.of(
                            "id", updatedSession.getId(),
                            "title", updatedSession.getDisplayTitle(),
                            "description", updatedSession.getDescription(),
                            "updatedAt", updatedSession.getUpdatedAt() != null ?
                                    updatedSession.getUpdatedAt().format(DATE_TIME_FORMAT) : null
                    )
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
            log.error("שגיאה בעדכון מטאדטה של שיחה: {}", sessionId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון מטאדטה השיחה"
            ));
        }
    }

    /**
     * קבלת סטטיסטיקות כלליות לכל השיחות של משתמש
     */
    @GetMapping("/user-stats")
    public ResponseEntity<?> getUserSessionsStats(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            SessionHistoryService.UserSessionsStats stats =
                    sessionHistoryService.getUserSessionsStats(currentUser);

            Map<String, Object> userStatsMap = new java.util.HashMap<>();
            userStatsMap.put("totalSessions", stats.totalSessions);
            userStatsMap.put("activeSessions", stats.activeSessions);
            userStatsMap.put("inactiveSessions", stats.inactiveSessions);
            userStatsMap.put("totalDocuments", stats.totalDocuments);
            userStatsMap.put("totalMessages", stats.totalMessages);
            userStatsMap.put("avgDocumentsPerSession", String.format("%.1f", stats.avgDocumentsPerSession));
            userStatsMap.put("avgMessagesPerSession", String.format("%.1f", stats.avgMessagesPerSession));
            userStatsMap.put("totalActiveDays", stats.totalActiveDays);
            userStatsMap.put("lastActivity", stats.lastActivity != null ?
                    stats.lastActivity.format(DATE_TIME_FORMAT) : null);
            userStatsMap.put("firstSession", stats.firstSession != null ?
                    stats.firstSession.format(DATE_TIME_FORMAT) : null);
            userStatsMap.put("sessionsPerDay", stats.totalActiveDays > 0 ?
                    String.format("%.2f", (double) stats.totalSessions / stats.totalActiveDays) : "0.00");

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "userSessionsStats", userStatsMap,
                    "userId", currentUser.getId(),
                    "username", currentUser.getUsername()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת סטטיסטיקות שיחות משתמש", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת סטטיסטיקות השיחות"
            ));
        }
    }

    /**
     * קבלת סיכום פעילות יומי/שבועי/חודשי
     */
    @GetMapping("/activity-summary")
    public ResponseEntity<?> getActivitySummary(
            @RequestParam(value = "period", defaultValue = "week") String period,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // חישוב התקופה לפי הפרמטר
            LocalDateTime startDate;
            String periodDisplay;

            if ("day".equalsIgnoreCase(period)) {
                startDate = LocalDateTime.now().minusDays(1);
                periodDisplay = "יום אחרון";
            } else if ("month".equalsIgnoreCase(period)) {
                startDate = LocalDateTime.now().minusMonths(1);
                periodDisplay = "חודש אחרון";
            } else if ("year".equalsIgnoreCase(period)) {
                startDate = LocalDateTime.now().minusYears(1);
                periodDisplay = "שנה אחרונה";
            } else { // "week"
                startDate = LocalDateTime.now().minusWeeks(1);
                periodDisplay = "שבוע אחרון";
            }

            // חיפוש שיחות בתקופה
            List<SessionHistoryService.SessionSummary> periodSessions =
                    sessionHistoryService.searchSessionsHistory(currentUser, "", startDate, LocalDateTime.now());

            // חישוב סטטיסטיקות התקופה
            long activeSessions = periodSessions.stream().filter(s -> s.active).count();
            int totalMessages = periodSessions.stream().mapToInt(s -> s.totalMessages).sum();
            int totalDocuments = periodSessions.stream().mapToInt(s -> s.totalDocuments).sum();
            int completedDocuments = periodSessions.stream().mapToInt(s -> s.completedDocuments).sum();

            double avgMessagesPerDay;
            if ("day".equals(period)) {
                avgMessagesPerDay = totalMessages;
            } else if ("week".equals(period)) {
                avgMessagesPerDay = totalMessages / 7.0;
            } else if ("month".equals(period)) {
                avgMessagesPerDay = totalMessages / 30.0;
            } else { // year
                avgMessagesPerDay = totalMessages / 365.0;
            }

            Map<String, Object> activitySummaryMap = new java.util.HashMap<>();
            activitySummaryMap.put("period", period);
            activitySummaryMap.put("periodDisplay", periodDisplay);
            activitySummaryMap.put("startDate", startDate.format(DATE_TIME_FORMAT));
            activitySummaryMap.put("endDate", LocalDateTime.now().format(DATE_TIME_FORMAT));
            activitySummaryMap.put("totalSessionsInPeriod", periodSessions.size());
            activitySummaryMap.put("activeSessionsInPeriod", activeSessions);
            activitySummaryMap.put("totalMessagesInPeriod", totalMessages);
            activitySummaryMap.put("totalDocumentsInPeriod", totalDocuments);
            activitySummaryMap.put("completedDocumentsInPeriod", completedDocuments);
            activitySummaryMap.put("avgMessagesPerDay", String.format("%.1f", avgMessagesPerDay));
            activitySummaryMap.put("mostActiveSession", periodSessions.stream()
                    .max((s1, s2) -> Integer.compare(s1.activityScore, s2.activityScore))
                    .map(this::formatSessionSummary)
                    .orElse(null));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "activitySummary", activitySummaryMap,
                    "userId", currentUser.getId()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת סיכום פעילות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת סיכום הפעילות"
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

    private Map<String, Object> createSessionActivityMap(SessionHistoryService.SessionActivityHistory history) {
        Map<String, Object> activityMap = new java.util.HashMap<>();
        activityMap.put("sessionId", history.sessionId);
        activityMap.put("title", history.title);
        activityMap.put("createdAt", history.createdAt.format(DATE_TIME_FORMAT));
        activityMap.put("lastActivity", history.lastActivity.format(DATE_TIME_FORMAT));
        activityMap.put("updatedAt", history.updatedAt != null ? history.updatedAt.format(DATE_TIME_FORMAT) : null);
        activityMap.put("totalDaysActive", history.totalDaysActive);
        activityMap.put("daysSinceLastActivity", history.daysSinceLastActivity);
        activityMap.put("totalMessages", history.totalMessages);
        activityMap.put("totalDocuments", history.totalDocuments);
        activityMap.put("completedDocuments", history.completedDocuments);
        activityMap.put("failedDocuments", history.failedDocuments);
        activityMap.put("totalCharacters", history.totalCharacters);
        activityMap.put("totalChunks", history.totalChunks);
        activityMap.put("activityScore", history.activityScore);
        activityMap.put("activityLevel", history.activityLevel);
        return activityMap;
    }

    private Map<String, Object> formatSessionSummary(SessionHistoryService.SessionSummary summary) {
        Map<String, Object> formatted = new java.util.HashMap<>();
        formatted.put("sessionId", summary.sessionId);
        formatted.put("title", summary.title);
        formatted.put("description", summary.description);
        formatted.put("createdAt", summary.createdAt.format(DATE_TIME_FORMAT));
        formatted.put("lastActivity", summary.lastActivity.format(DATE_TIME_FORMAT));
        formatted.put("updatedAt", summary.updatedAt != null ?
                summary.updatedAt.format(DATE_TIME_FORMAT) : null);
        formatted.put("active", summary.active);
        formatted.put("totalMessages", summary.totalMessages);
        formatted.put("totalDocuments", summary.totalDocuments);
        formatted.put("completedDocuments", summary.completedDocuments);
        formatted.put("failedDocuments", summary.failedDocuments);
        formatted.put("totalCharacters", summary.totalCharacters);
        formatted.put("totalChunks", summary.totalChunks);
        formatted.put("activityScore", summary.activityScore);
        formatted.put("activityLevel", getActivityLevelDescription(summary.activityScore));

        return formatted;
    }

    private String getActivityLevelDescription(int activityScore) {
        if (activityScore >= 80) {
            return "פעיל מאוד";
        } else if (activityScore >= 60) {
            return "פעיל";
        } else if (activityScore >= 40) {
            return "פעיל בינוני";
        } else if (activityScore >= 20) {
            return "פעיל מעט";
        } else {
            return "לא פעיל";
        }
    }

    // Request DTOs

    public static class SessionMetadataUpdateRequest {
        private String title;
        private String description;
        private Map<String, Object> additionalMetadata;

        public SessionMetadataUpdateRequest() {}

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Map<String, Object> getAdditionalMetadata() { return additionalMetadata; }
        public void setAdditionalMetadata(Map<String, Object> additionalMetadata) {
            this.additionalMetadata = additionalMetadata;
        }
    }
}