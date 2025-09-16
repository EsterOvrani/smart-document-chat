package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.entity.UserPreferences;
import com.smartdocumentchat.repository.UserPreferencesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserPreferencesService {

    private final UserPreferencesRepository userPreferencesRepository;
    private final CacheService cacheService;

    /**
     * קבלת העדפות משתמש עם caching
     */
    public UserPreferences getUserPreferences(User user) {
        validateUser(user);

        String cacheKey = "user_preferences:" + user.getId();
        UserPreferences cachedPreferences = (UserPreferences) cacheService.get(cacheKey);

        if (cachedPreferences != null) {
            log.debug("User preferences for user {} retrieved from cache", user.getId());
            return cachedPreferences;
        }

        Optional<UserPreferences> preferencesOpt = userPreferencesRepository.findByUser(user);

        UserPreferences preferences;
        if (preferencesOpt.isPresent()) {
            preferences = preferencesOpt.get();
        } else {
            // יצירת העדפות ברירת מחדל אם לא קיימות
            preferences = createDefaultPreferences(user);
            log.info("העדפות ברירת מחדל נוצרו עבור משתמש: {}", user.getUsername());
        }

        // Cache for 1 hour
        cacheService.set(cacheKey, preferences, java.time.Duration.ofHours(1));
        log.debug("User preferences for user {} retrieved from database and cached", user.getId());

        return preferences;
    }

    /**
     * יצירת העדפות ברירת מחדל למשתמש חדש
     */
    @Transactional
    public UserPreferences createDefaultPreferences(User user) {
        validateUser(user);

        // בדיקה שאין כבר העדפות
        if (userPreferencesRepository.existsByUser(user)) {
            throw new IllegalArgumentException("כבר קיימות העדפות למשתמש זה");
        }

        UserPreferences preferences = UserPreferences.createDefaultPreferences(user);
        UserPreferences savedPreferences = userPreferencesRepository.save(preferences);

        // Cache the new preferences
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, savedPreferences, java.time.Duration.ofHours(1));

        log.info("העדפות ברירת מחדל נוצרו ונשמרו עבור משתמש: {}", user.getUsername());
        return savedPreferences;
    }

    /**
     * עדכון העדפות משתמש
     */
    @Transactional
    public UserPreferences updateUserPreferences(User user, Map<String, Object> updates) {
        validateUser(user);

        UserPreferences preferences = getUserPreferences(user);

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            try {
                updateSinglePreference(preferences, key, value);
                log.debug("עדכן העדפה {} ל-{} עבור משתמש {}", key, value, user.getUsername());
            } catch (IllegalArgumentException e) {
                log.warn("שגיאה בעדכון העדפה {} עבור משתמש {}: {}",
                        key, user.getUsername(), e.getMessage());
                throw e;
            }
        }

        UserPreferences updatedPreferences = userPreferencesRepository.save(preferences);

        // Update cache
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, updatedPreferences, java.time.Duration.ofHours(1));

        log.info("העדפות עודכנו בהצלחה עבור משתמש: {}", user.getUsername());
        return updatedPreferences;
    }

    /**
     * עדכון שפה בלבד
     */
    @Transactional
    public UserPreferences updateLanguage(User user, UserPreferences.Language language) {
        validateUser(user);

        UserPreferences preferences = getUserPreferences(user);
        preferences.setLanguage(language);

        UserPreferences updatedPreferences = userPreferencesRepository.save(preferences);

        // Update cache
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, updatedPreferences, java.time.Duration.ofHours(1));

        log.info("שפה עודכנה ל-{} עבור משתמש: {}", language.getDisplayName(), user.getUsername());
        return updatedPreferences;
    }

    /**
     * עדכון ערכת נושא בלבד
     */
    @Transactional
    public UserPreferences updateTheme(User user, UserPreferences.Theme theme) {
        validateUser(user);

        UserPreferences preferences = getUserPreferences(user);
        preferences.setTheme(theme);

        UserPreferences updatedPreferences = userPreferencesRepository.save(preferences);

        // Update cache
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, updatedPreferences, java.time.Duration.ofHours(1));

        log.info("ערכת נושא עודכנה ל-{} עבור משתמש: {}", theme.getDisplayName(), user.getUsername());
        return updatedPreferences;
    }

    /**
     * עדכון העדפות צ'אט
     */
    @Transactional
    public UserPreferences updateChatPreferences(User user, boolean autoSave, boolean showProcessingTime,
                                                 boolean soundNotifications, int maxContextLength) {
        validateUser(user);

        UserPreferences preferences = getUserPreferences(user);
        preferences.setAutoSaveConversations(autoSave);
        preferences.setShowProcessingTime(showProcessingTime);
        preferences.setEnableSoundNotifications(soundNotifications);
        preferences.setMaxContextLength(Math.max(1, Math.min(20, maxContextLength))); // Limit 1-20

        UserPreferences updatedPreferences = userPreferencesRepository.save(preferences);

        // Update cache
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, updatedPreferences, java.time.Duration.ofHours(1));

        log.info("העדפות צ'אט עודכנו עבור משתמש: {}", user.getUsername());
        return updatedPreferences;
    }

    /**
     * עדכון העדפות עיבוד מסמכים
     */
    @Transactional
    public UserPreferences updateDocumentProcessingPreferences(User user,
                                                               UserPreferences.ChunkStrategy chunkStrategy,
                                                               boolean autoProcessing,
                                                               boolean showChunkDetails) {
        validateUser(user);

        UserPreferences preferences = getUserPreferences(user);
        preferences.setDefaultChunkStrategy(chunkStrategy);
        preferences.setEnableAutoProcessing(autoProcessing);
        preferences.setShowChunkDetails(showChunkDetails);

        UserPreferences updatedPreferences = userPreferencesRepository.save(preferences);

        // Update cache
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, updatedPreferences, java.time.Duration.ofHours(1));

        log.info("העדפות עיבוד מסמכים עודכנו עבור משתמש: {}", user.getUsername());
        return updatedPreferences;
    }

    /**
     * עדכון העדפות התראות
     */
    @Transactional
    public UserPreferences updateNotificationPreferences(User user, boolean emailNotifications,
                                                         boolean processingComplete,
                                                         boolean weeklySummary) {
        validateUser(user);

        UserPreferences preferences = getUserPreferences(user);
        preferences.setEmailNotifications(emailNotifications);
        preferences.setProcessingCompleteNotifications(processingComplete);
        preferences.setWeeklySummaryEmail(weeklySummary);

        UserPreferences updatedPreferences = userPreferencesRepository.save(preferences);

        // Update cache
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, updatedPreferences, java.time.Duration.ofHours(1));

        log.info("העדפות התראות עודכנו עבור משתמש: {}", user.getUsername());
        return updatedPreferences;
    }

    /**
     * איפוס לערכי ברירת מחדל
     */
    @Transactional
    public UserPreferences resetToDefault(User user) {
        validateUser(user);

        UserPreferences preferences = getUserPreferences(user);

        // Reset to defaults
        UserPreferences defaultPrefs = UserPreferences.createDefaultPreferences(user);
        preferences.setLanguage(defaultPrefs.getLanguage());
        preferences.setTimezone(defaultPrefs.getTimezone());
        preferences.setTheme(defaultPrefs.getTheme());
        preferences.setItemsPerPage(defaultPrefs.getItemsPerPage());
        preferences.setShowAdvancedOptions(defaultPrefs.getShowAdvancedOptions());
        preferences.setAutoSaveConversations(defaultPrefs.getAutoSaveConversations());
        preferences.setShowProcessingTime(defaultPrefs.getShowProcessingTime());
        preferences.setEnableSoundNotifications(defaultPrefs.getEnableSoundNotifications());
        preferences.setMaxContextLength(defaultPrefs.getMaxContextLength());
        preferences.setDefaultChunkStrategy(defaultPrefs.getDefaultChunkStrategy());
        preferences.setEnableAutoProcessing(defaultPrefs.getEnableAutoProcessing());
        preferences.setShowChunkDetails(defaultPrefs.getShowChunkDetails());
        preferences.setDataRetentionDays(defaultPrefs.getDataRetentionDays());
        preferences.setEnableAnalytics(defaultPrefs.getEnableAnalytics());
        preferences.setShareAnonymousUsage(defaultPrefs.getShareAnonymousUsage());
        preferences.setEmailNotifications(defaultPrefs.getEmailNotifications());
        preferences.setProcessingCompleteNotifications(defaultPrefs.getProcessingCompleteNotifications());
        preferences.setWeeklySummaryEmail(defaultPrefs.getWeeklySummaryEmail());
        preferences.setCustomSettings(null);

        UserPreferences resetPreferences = userPreferencesRepository.save(preferences);

        // Update cache
        String cacheKey = "user_preferences:" + user.getId();
        cacheService.set(cacheKey, resetPreferences, java.time.Duration.ofHours(1));

        log.info("העדפות אופסו לברירת מחדל עבור משתמש: {}", user.getUsername());
        return resetPreferences;
    }

    /**
     * מחיקת העדפות משתמש
     */
    @Transactional
    public boolean deleteUserPreferences(User user) {
        validateUser(user);

        Optional<UserPreferences> preferencesOpt = userPreferencesRepository.findByUser(user);
        if (preferencesOpt.isPresent()) {
            userPreferencesRepository.delete(preferencesOpt.get());

            // Remove from cache
            String cacheKey = "user_preferences:" + user.getId();
            cacheService.delete(cacheKey);

            log.info("העדפות נמחקו עבור משתמש: {}", user.getUsername());
            return true;
        }

        return false;
    }

    /**
     * קבלת מידע על אסטרטגיית chunking של המשתמש
     */
    public UserPreferences.ChunkInfo getChunkInfo(User user) {
        UserPreferences preferences = getUserPreferences(user);
        return preferences.getChunkInfo();
    }

    /**
     * בדיקה אם משתמש רוצה עיבוד אוטומטי
     */
    public boolean isAutoProcessingEnabled(User user) {
        UserPreferences preferences = getUserPreferences(user);
        return preferences.getEnableAutoProcessing();
    }

    /**
     * קבלת שפת המשתמש
     */
    public UserPreferences.Language getUserLanguage(User user) {
        UserPreferences preferences = getUserPreferences(user);
        return preferences.getLanguage();
    }

    /**
     * קבלת ערכת הנושא של המשתמש
     */
    public UserPreferences.Theme getUserTheme(User user) {
        UserPreferences preferences = getUserPreferences(user);
        return preferences.getTheme();
    }

    // Private helper methods

    private void validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("משתמש לא תקין");
        }

        if (!user.getActive()) {
            throw new SecurityException("משתמש לא פעיל");
        }
    }

    private void updateSinglePreference(UserPreferences preferences, String key, Object value) {
        switch (key.toLowerCase()) {
            case "language":
                if (value instanceof String) {
                    preferences.setLanguage(UserPreferences.Language.valueOf(((String) value).toUpperCase()));
                } else if (value instanceof UserPreferences.Language) {
                    preferences.setLanguage((UserPreferences.Language) value);
                }
                break;

            case "theme":
                if (value instanceof String) {
                    preferences.setTheme(UserPreferences.Theme.valueOf(((String) value).toUpperCase()));
                } else if (value instanceof UserPreferences.Theme) {
                    preferences.setTheme((UserPreferences.Theme) value);
                }
                break;

            case "itemsperpage":
                if (value instanceof Number) {
                    int items = ((Number) value).intValue();
                    preferences.setItemsPerPage(Math.max(5, Math.min(50, items))); // Limit 5-50
                }
                break;

            case "autosaveconversations":
                if (value instanceof Boolean) {
                    preferences.setAutoSaveConversations((Boolean) value);
                }
                break;

            case "showprocessingtime":
                if (value instanceof Boolean) {
                    preferences.setShowProcessingTime((Boolean) value);
                }
                break;

            case "enableautoprocessing":
                if (value instanceof Boolean) {
                    preferences.setEnableAutoProcessing((Boolean) value);
                }
                break;

            case "emailnotifications":
                if (value instanceof Boolean) {
                    preferences.setEmailNotifications((Boolean) value);
                }
                break;

            case "defaultchunkstrategy":
                if (value instanceof String) {
                    preferences.setDefaultChunkStrategy(
                            UserPreferences.ChunkStrategy.valueOf(((String) value).toUpperCase()));
                } else if (value instanceof UserPreferences.ChunkStrategy) {
                    preferences.setDefaultChunkStrategy((UserPreferences.ChunkStrategy) value);
                }
                break;

            case "maxcontextlength":
                if (value instanceof Number) {
                    int length = ((Number) value).intValue();
                    preferences.setMaxContextLength(Math.max(1, Math.min(20, length))); // Limit 1-20
                }
                break;

            default:
                throw new IllegalArgumentException("העדפה לא ידועה: " + key);
        }
    }
}