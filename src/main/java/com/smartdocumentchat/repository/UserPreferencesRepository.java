package com.smartdocumentchat.repository;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.entity.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, Long> {

    /**
     * מציאת העדפות לפי משתמש
     */
    Optional<UserPreferences> findByUser(User user);

    /**
     * מציאת העדפות לפי מזהה משתמש
     */
    @Query("SELECT up FROM UserPreferences up WHERE up.user.id = :userId")
    Optional<UserPreferences> findByUserId(@Param("userId") Long userId);

    /**
     * בדיקה אם יש העדפות למשתמש
     */
    boolean existsByUser(User user);

    /**
     * מציאת כל המשתמשים עם שפה ספציפית
     */
    @Query("SELECT up FROM UserPreferences up WHERE up.language = :language")
    List<UserPreferences> findByLanguage(@Param("language") UserPreferences.Language language);

    /**
     * מציאת משתמשים עם הפעלת התראות אימייל
     */
    @Query("SELECT up FROM UserPreferences up WHERE up.emailNotifications = true")
    List<UserPreferences> findUsersWithEmailNotificationsEnabled();

    /**
     * מציאת משתמשים עם סיכום שבועי
     */
    @Query("SELECT up FROM UserPreferences up WHERE up.weeklySummaryEmail = true")
    List<UserPreferences> findUsersWithWeeklySummaryEnabled();

    /**
     * מציאת משתמשים לפי אסטרטגיית chunking
     */
    @Query("SELECT up FROM UserPreferences up WHERE up.defaultChunkStrategy = :strategy")
    List<UserPreferences> findByDefaultChunkStrategy(
            @Param("strategy") UserPreferences.ChunkStrategy strategy);

    /**
     * מציאת משתמשים עם עיבוד אוטומטי מופעל
     */
    @Query("SELECT up FROM UserPreferences up WHERE up.enableAutoProcessing = true")
    List<UserPreferences> findUsersWithAutoProcessingEnabled();

    /**
     * מציאת העדפות לפי ערכת נושא
     */
    @Query("SELECT up FROM UserPreferences up WHERE up.theme = :theme")
    List<UserPreferences> findByTheme(@Param("theme") UserPreferences.Theme theme);

    /**
     * עדכון שפה לפי מזהה משתמש
     */
    @Query("UPDATE UserPreferences up SET up.language = :language WHERE up.user.id = :userId")
    void updateLanguageByUserId(@Param("userId") Long userId,
                                @Param("language") UserPreferences.Language language);

    /**
     * עדכון ערכת נושא לפי מזהה משתמש
     */
    @Query("UPDATE UserPreferences up SET up.theme = :theme WHERE up.user.id = :userId")
    void updateThemeByUserId(@Param("userId") Long userId,
                             @Param("theme") UserPreferences.Theme theme);

    /**
     * סטטיסטיקות - ספירת משתמשים לפי שפה
     */
    @Query("SELECT up.language, COUNT(up) FROM UserPreferences up GROUP BY up.language")
    List<Object[]> countUsersByLanguage();

    /**
     * סטטיסטיקות - ספירת משתמשים לפי ערכת נושא
     */
    @Query("SELECT up.theme, COUNT(up) FROM UserPreferences up GROUP BY up.theme")
    List<Object[]> countUsersByTheme();

    /**
     * מחיקת העדפות לפי משתמש
     */
    void deleteByUser(User user);
}