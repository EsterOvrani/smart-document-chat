package com.smartdocumentchat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_preferences")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Language and Localization
    @Enumerated(EnumType.STRING)
    @Column(name = "language", nullable = false)
    private Language language = Language.HEBREW;

    @Enumerated(EnumType.STRING)
    @Column(name = "timezone")
    private TimeZone timezone = TimeZone.ASIA_JERUSALEM;

    // UI Preferences
    @Enumerated(EnumType.STRING)
    @Column(name = "theme")
    private Theme theme = Theme.LIGHT;

    @Column(name = "items_per_page")
    private Integer itemsPerPage = 10;

    @Column(name = "show_advanced_options")
    private Boolean showAdvancedOptions = false;

    // Chat Preferences
    @Column(name = "auto_save_conversations")
    private Boolean autoSaveConversations = true;

    @Column(name = "show_processing_time")
    private Boolean showProcessingTime = false;

    @Column(name = "enable_sound_notifications")
    private Boolean enableSoundNotifications = true;

    @Column(name = "max_context_length")
    private Integer maxContextLength = 5;

    // Document Processing Preferences
    @Enumerated(EnumType.STRING)
    @Column(name = "default_chunk_strategy")
    private ChunkStrategy defaultChunkStrategy = ChunkStrategy.BALANCED;

    @Column(name = "enable_auto_processing")
    private Boolean enableAutoProcessing = true;

    @Column(name = "show_chunk_details")
    private Boolean showChunkDetails = false;

    // Privacy and Security
    @Column(name = "data_retention_days")
    private Integer dataRetentionDays = 365;

    @Column(name = "enable_analytics")
    private Boolean enableAnalytics = true;

    @Column(name = "share_anonymous_usage")
    private Boolean shareAnonymousUsage = false;

    // Notification Preferences
    @Column(name = "email_notifications")
    private Boolean emailNotifications = true;

    @Column(name = "processing_complete_notifications")
    private Boolean processingCompleteNotifications = true;

    @Column(name = "weekly_summary_email")
    private Boolean weeklySummaryEmail = false;

    // Advanced Settings (JSON format for flexibility)
    @Column(name = "custom_settings", columnDefinition = "TEXT")
    private String customSettings;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Relationship to User (One-to-One)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Enums for preferences

    public enum Language {
        HEBREW("he", "עברית"),
        ENGLISH("en", "English"),
        ARABIC("ar", "العربية");

        private final String code;
        private final String displayName;

        Language(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public String getCode() { return code; }
        public String getDisplayName() { return displayName; }
    }

    public enum TimeZone {
        ASIA_JERUSALEM("Asia/Jerusalem", "ירושלים"),
        UTC("UTC", "UTC"),
        EUROPE_LONDON("Europe/London", "לונדון"),
        AMERICA_NEW_YORK("America/New_York", "ניו יורק");

        private final String zoneId;
        private final String displayName;

        TimeZone(String zoneId, String displayName) {
            this.zoneId = zoneId;
            this.displayName = displayName;
        }

        public String getZoneId() { return zoneId; }
        public String getDisplayName() { return displayName; }
    }

    public enum Theme {
        LIGHT("light", "בהיר"),
        DARK("dark", "כהה"),
        AUTO("auto", "אוטומטי");

        private final String value;
        private final String displayName;

        Theme(String value, String displayName) {
            this.value = value;
            this.displayName = displayName;
        }

        public String getValue() { return value; }
        public String getDisplayName() { return displayName; }
    }

    public enum ChunkStrategy {
        FAST("fast", "מהיר", 800, 100),
        BALANCED("balanced", "מאוזן", 1200, 200),
        DETAILED("detailed", "מפורט", 1600, 300);

        private final String value;
        private final String displayName;
        private final int chunkSize;
        private final int overlap;

        ChunkStrategy(String value, String displayName, int chunkSize, int overlap) {
            this.value = value;
            this.displayName = displayName;
            this.chunkSize = chunkSize;
            this.overlap = overlap;
        }

        public String getValue() { return value; }
        public String getDisplayName() { return displayName; }
        public int getChunkSize() { return chunkSize; }
        public int getOverlap() { return overlap; }
    }

    // Helper methods

    public boolean isRtlLanguage() {
        return language == Language.HEBREW || language == Language.ARABIC;
    }

    public String getLanguageCode() {
        return language.getCode();
    }

    public String getLanguageDisplayName() {
        return language.getDisplayName();
    }

    public String getTimezoneId() {
        return timezone.getZoneId();
    }

    public String getThemeValue() {
        return theme.getValue();
    }

    public ChunkInfo getChunkInfo() {
        return new ChunkInfo(
                defaultChunkStrategy.getChunkSize(),
                defaultChunkStrategy.getOverlap()
        );
    }

    // Inner class for chunk information
    public static class ChunkInfo {
        public final int size;
        public final int overlap;

        public ChunkInfo(int size, int overlap) {
            this.size = size;
            this.overlap = overlap;
        }
    }

    /**
     * יצירת העדפות ברירת מחדל למשתמש חדש
     */
    public static UserPreferences createDefaultPreferences(User user) {
        UserPreferences preferences = new UserPreferences();
        preferences.setUser(user);

        // Default values are already set by field initialization
        return preferences;
    }

    /**
     * עדכון העדפה בודדת
     */
    public void updatePreference(String key, Object value) {
        switch (key.toLowerCase()) {
            case "language":
                if (value instanceof Language) this.language = (Language) value;
                break;
            case "theme":
                if (value instanceof Theme) this.theme = (Theme) value;
                break;
            case "itemsperpage":
                if (value instanceof Integer) this.itemsPerPage = (Integer) value;
                break;
            case "autosaveconversations":
                if (value instanceof Boolean) this.autoSaveConversations = (Boolean) value;
                break;
            case "showprocessingtime":
                if (value instanceof Boolean) this.showProcessingTime = (Boolean) value;
                break;
            case "enableautoprocessing":
                if (value instanceof Boolean) this.enableAutoProcessing = (Boolean) value;
                break;
            case "emailnotifications":
                if (value instanceof Boolean) this.emailNotifications = (Boolean) value;
                break;
            default:
                throw new IllegalArgumentException("העדפה לא ידועה: " + key);
        }
    }

    /**
     * בדיקה אם יש הגדרות מותאמות אישית
     */
    public boolean hasCustomSettings() {
        return customSettings != null && !customSettings.trim().isEmpty();
    }

    /**
     * החזרת העדפות כמפתח-ערך
     */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("language", language);
        map.put("timezone", timezone);
        map.put("theme", theme);
        map.put("itemsPerPage", itemsPerPage);
        map.put("showAdvancedOptions", showAdvancedOptions);
        map.put("autoSaveConversations", autoSaveConversations);
        map.put("showProcessingTime", showProcessingTime);
        map.put("enableSoundNotifications", enableSoundNotifications);
        map.put("maxContextLength", maxContextLength);
        map.put("defaultChunkStrategy", defaultChunkStrategy);
        map.put("enableAutoProcessing", enableAutoProcessing);
        map.put("showChunkDetails", showChunkDetails);
        map.put("dataRetentionDays", dataRetentionDays);
        map.put("enableAnalytics", enableAnalytics);
        map.put("shareAnonymousUsage", shareAnonymousUsage);
        map.put("emailNotifications", emailNotifications);
        map.put("processingCompleteNotifications", processingCompleteNotifications);
        map.put("weeklySummaryEmail", weeklySummaryEmail);
        return map;
    }
}