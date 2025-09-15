package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.entity.UserPreferences;
import com.smartdocumentchat.service.UserService;
import com.smartdocumentchat.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
@Slf4j
public class UserPreferencesController {

    private final UserPreferencesService userPreferencesService;
    private final UserService userService;

    /**
     * קבלת כל העדפות המשתמש
     */
    @GetMapping
    public ResponseEntity<?> getUserPreferences(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);
            UserPreferences preferences = userPreferencesService.getUserPreferences(user);

            // Build preferences map using HashMap to avoid Map.of() limitations
            Map<String, Object> preferencesMap = new java.util.HashMap<>();
            preferencesMap.put("language", preferences.getLanguage());
            preferencesMap.put("languageCode", preferences.getLanguageCode());
            preferencesMap.put("languageDisplay", preferences.getLanguageDisplayName());
            preferencesMap.put("theme", preferences.getTheme());
            preferencesMap.put("themeValue", preferences.getThemeValue());
            preferencesMap.put("timezone", preferences.getTimezone());
            preferencesMap.put("timezoneId", preferences.getTimezoneId());
            preferencesMap.put("itemsPerPage", preferences.getItemsPerPage());
            preferencesMap.put("showAdvancedOptions", preferences.getShowAdvancedOptions());
            preferencesMap.put("autoSaveConversations", preferences.getAutoSaveConversations());
            preferencesMap.put("showProcessingTime", preferences.getShowProcessingTime());
            preferencesMap.put("enableSoundNotifications", preferences.getEnableSoundNotifications());
            preferencesMap.put("maxContextLength", preferences.getMaxContextLength());
            preferencesMap.put("defaultChunkStrategy", preferences.getDefaultChunkStrategy());
            preferencesMap.put("enableAutoProcessing", preferences.getEnableAutoProcessing());
            preferencesMap.put("showChunkDetails", preferences.getShowChunkDetails());
            preferencesMap.put("emailNotifications", preferences.getEmailNotifications());
            preferencesMap.put("processingCompleteNotifications", preferences.getProcessingCompleteNotifications());
            preferencesMap.put("weeklySummaryEmail", preferences.getWeeklySummaryEmail());
            preferencesMap.put("isRtlLanguage", preferences.isRtlLanguage());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "preferences", preferencesMap,
                    "userId", user.getId(),
                    "username", user.getUsername()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת העדפות משתמש", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת העדפות המשתמש"
            ));
        }
    }

    /**
     * עדכון העדפות משתמש כללי
     */
    @PutMapping
    public ResponseEntity<?> updateUserPreferences(
            @RequestBody Map<String, Object> updates,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);
            UserPreferences updatedPreferences = userPreferencesService.updateUserPreferences(user, updates);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "העדפות עודכנו בהצלחה",
                    "updatedPreferences", updatedPreferences.toMap()
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
            log.error("שגיאה בעדכון העדפות משתמש", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון העדפות המשתמש"
            ));
        }
    }

    /**
     * עדכון שפה בלבד
     */
    @PutMapping("/language")
    public ResponseEntity<?> updateLanguage(
            @RequestBody LanguageUpdateRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);
            UserPreferences.Language language = UserPreferences.Language.valueOf(request.getLanguage().toUpperCase());

            UserPreferences updatedPreferences = userPreferencesService.updateLanguage(user, language);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "שפה עודכנה בהצלחה",
                    "language", updatedPreferences.getLanguage(),
                    "languageDisplay", updatedPreferences.getLanguageDisplayName(),
                    "isRtl", updatedPreferences.isRtlLanguage()
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "שפה לא תקינה: " + request.getLanguage()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בעדכון שפה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון השפה"
            ));
        }
    }

    /**
     * עדכון ערכת נושא בלבד
     */
    @PutMapping("/theme")
    public ResponseEntity<?> updateTheme(
            @RequestBody ThemeUpdateRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);
            UserPreferences.Theme theme = UserPreferences.Theme.valueOf(request.getTheme().toUpperCase());

            UserPreferences updatedPreferences = userPreferencesService.updateTheme(user, theme);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "ערכת נושא עודכנה בהצלחה",
                    "theme", updatedPreferences.getTheme(),
                    "themeValue", updatedPreferences.getThemeValue()
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "ערכת נושא לא תקינה: " + request.getTheme()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בעדכון ערכת נושא", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון ערכת הנושא"
            ));
        }
    }

    /**
     * עדכון העדפות צ'אט
     */
    @PutMapping("/chat")
    public ResponseEntity<?> updateChatPreferences(
            @RequestBody ChatPreferencesRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);

            UserPreferences updatedPreferences = userPreferencesService.updateChatPreferences(
                    user,
                    request.isAutoSaveConversations(),
                    request.isShowProcessingTime(),
                    request.isEnableSoundNotifications(),
                    request.getMaxContextLength()
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "העדפות צ'אט עודכנו בהצלחה",
                    "chatPreferences", Map.of(
                            "autoSaveConversations", updatedPreferences.getAutoSaveConversations(),
                            "showProcessingTime", updatedPreferences.getShowProcessingTime(),
                            "enableSoundNotifications", updatedPreferences.getEnableSoundNotifications(),
                            "maxContextLength", updatedPreferences.getMaxContextLength()
                    )
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בעדכון העדפות צ'אט", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון העדפות הצ'אט"
            ));
        }
    }

    /**
     * עדכון העדפות עיבוד מסמכים
     */
    @PutMapping("/documents")
    public ResponseEntity<?> updateDocumentPreferences(
            @RequestBody DocumentPreferencesRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);
            UserPreferences.ChunkStrategy chunkStrategy =
                    UserPreferences.ChunkStrategy.valueOf(request.getChunkStrategy().toUpperCase());

            UserPreferences updatedPreferences = userPreferencesService.updateDocumentProcessingPreferences(
                    user,
                    chunkStrategy,
                    request.isEnableAutoProcessing(),
                    request.isShowChunkDetails()
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "העדפות עיבוד מסמכים עודכנו בהצלחה",
                    "documentPreferences", Map.of(
                            "defaultChunkStrategy", updatedPreferences.getDefaultChunkStrategy(),
                            "enableAutoProcessing", updatedPreferences.getEnableAutoProcessing(),
                            "showChunkDetails", updatedPreferences.getShowChunkDetails(),
                            "chunkInfo", updatedPreferences.getChunkInfo()
                    )
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "אסטרטגיית chunking לא תקינה: " + request.getChunkStrategy()
            ));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בעדכון העדפות מסמכים", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון העדפות המסמכים"
            ));
        }
    }

    /**
     * עדכון העדפות התראות
     */
    @PutMapping("/notifications")
    public ResponseEntity<?> updateNotificationPreferences(
            @RequestBody NotificationPreferencesRequest request,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);

            UserPreferences updatedPreferences = userPreferencesService.updateNotificationPreferences(
                    user,
                    request.isEmailNotifications(),
                    request.isProcessingCompleteNotifications(),
                    request.isWeeklySummaryEmail()
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "העדפות התראות עודכנו בהצלחה",
                    "notificationPreferences", Map.of(
                            "emailNotifications", updatedPreferences.getEmailNotifications(),
                            "processingCompleteNotifications", updatedPreferences.getProcessingCompleteNotifications(),
                            "weeklySummaryEmail", updatedPreferences.getWeeklySummaryEmail()
                    )
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בעדכון העדפות התראות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון העדפות ההתראות"
            ));
        }
    }

    /**
     * איפוס לערכי ברירת מחדל
     */
    @PostMapping("/reset")
    public ResponseEntity<?> resetToDefault(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User user = getCurrentUser(userId);
            UserPreferences resetPreferences = userPreferencesService.resetToDefault(user);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "העדפות אופסו לברירת מחדל",
                    "preferences", resetPreferences.toMap()
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה באיפוס העדפות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה באיפוס העדפות"
            ));
        }
    }

    /**
     * קבלת אפשרויות זמינות
     */
    @GetMapping("/options")
    public ResponseEntity<?> getAvailableOptions() {
        try {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "options", Map.of(
                            "languages", UserPreferences.Language.values(),
                            "themes", UserPreferences.Theme.values(),
                            "timezones", UserPreferences.TimeZone.values(),
                            "chunkStrategies", UserPreferences.ChunkStrategy.values()
                    )
            ));

        } catch (Exception e) {
            log.error("שגיאה בקבלת אפשרויות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת האפשרויות"
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

    // Request DTOs

    public static class LanguageUpdateRequest {
        private String language;

        public LanguageUpdateRequest() {}
        public LanguageUpdateRequest(String language) { this.language = language; }

        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
    }

    public static class ThemeUpdateRequest {
        private String theme;

        public ThemeUpdateRequest() {}
        public ThemeUpdateRequest(String theme) { this.theme = theme; }

        public String getTheme() { return theme; }
        public void setTheme(String theme) { this.theme = theme; }
    }

    public static class ChatPreferencesRequest {
        private boolean autoSaveConversations = true;
        private boolean showProcessingTime = false;
        private boolean enableSoundNotifications = true;
        private int maxContextLength = 5;

        public ChatPreferencesRequest() {}

        public boolean isAutoSaveConversations() { return autoSaveConversations; }
        public void setAutoSaveConversations(boolean autoSaveConversations) { this.autoSaveConversations = autoSaveConversations; }

        public boolean isShowProcessingTime() { return showProcessingTime; }
        public void setShowProcessingTime(boolean showProcessingTime) { this.showProcessingTime = showProcessingTime; }

        public boolean isEnableSoundNotifications() { return enableSoundNotifications; }
        public void setEnableSoundNotifications(boolean enableSoundNotifications) { this.enableSoundNotifications = enableSoundNotifications; }

        public int getMaxContextLength() { return maxContextLength; }
        public void setMaxContextLength(int maxContextLength) { this.maxContextLength = maxContextLength; }
    }

    public static class DocumentPreferencesRequest {
        private String chunkStrategy = "balanced";
        private boolean enableAutoProcessing = true;
        private boolean showChunkDetails = false;

        public DocumentPreferencesRequest() {}

        public String getChunkStrategy() { return chunkStrategy; }
        public void setChunkStrategy(String chunkStrategy) { this.chunkStrategy = chunkStrategy; }

        public boolean isEnableAutoProcessing() { return enableAutoProcessing; }
        public void setEnableAutoProcessing(boolean enableAutoProcessing) { this.enableAutoProcessing = enableAutoProcessing; }

        public boolean isShowChunkDetails() { return showChunkDetails; }
        public void setShowChunkDetails(boolean showChunkDetails) { this.showChunkDetails = showChunkDetails; }
    }

    public static class NotificationPreferencesRequest {
        private boolean emailNotifications = true;
        private boolean processingCompleteNotifications = true;
        private boolean weeklySummaryEmail = false;

        public NotificationPreferencesRequest() {}

        public boolean isEmailNotifications() { return emailNotifications; }
        public void setEmailNotifications(boolean emailNotifications) { this.emailNotifications = emailNotifications; }

        public boolean isProcessingCompleteNotifications() { return processingCompleteNotifications; }
        public void setProcessingCompleteNotifications(boolean processingCompleteNotifications) { this.processingCompleteNotifications = processingCompleteNotifications; }

        public boolean isWeeklySummaryEmail() { return weeklySummaryEmail; }
        public void setWeeklySummaryEmail(boolean weeklySummaryEmail) { this.weeklySummaryEmail = weeklySummaryEmail; }
    }
}