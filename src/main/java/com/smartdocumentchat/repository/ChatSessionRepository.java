package com.smartdocumentchat.repository;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, Long> {

    /**
     * מציאת כל השיחות של משתמש ספציפי
     */
    List<ChatSession> findByUserOrderByUpdatedAtDesc(User user);

    /**
     * מציאת כל השיחות הפעילות של משתמש
     */
    List<ChatSession> findByUserAndActiveTrueOrderByUpdatedAtDesc(User user);

    /**
     * מציאת שיחות לפי משתמש ותאריך יצירה
     */
    List<ChatSession> findByUserAndCreatedAtBetweenOrderByCreatedAtDesc(
            User user, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * חיפוש שיחות לפי כותרת
     */
    @Query("SELECT cs FROM ChatSession cs WHERE cs.user = :user AND " +
            "LOWER(cs.title) LIKE LOWER(CONCAT('%', :title, '%'))")
    List<ChatSession> findByUserAndTitleContaining(@Param("user") User user,
                                                   @Param("title") String title);

    /**
     * מציאת השיחה האחרונה של משתמש
     */
    Optional<ChatSession> findFirstByUserAndActiveTrueOrderByUpdatedAtDesc(User user);

    /**
     * ספירת השיחות הפעילות של משתמש
     */
    long countByUserAndActiveTrue(User user);

    /**
     * מציאת שיחות שלא היו פעילות במשך זמן מסוים
     */
    @Query("SELECT cs FROM ChatSession cs WHERE cs.user = :user AND " +
            "cs.lastActivityAt < :beforeDate")
    List<ChatSession> findInactiveSessionsBefore(@Param("user") User user,
                                                 @Param("beforeDate") LocalDateTime beforeDate);

    /**
     * מציאת שיחות עם מספר הודעות מינימלי
     */
    @Query("SELECT cs FROM ChatSession cs WHERE cs.user = :user AND " +
            "SIZE(cs.messages) >= :minMessages")
    List<ChatSession> findSessionsWithMinimumMessages(@Param("user") User user,
                                                      @Param("minMessages") int minMessages);

    /**
     * מציאת שיחות עם מסמכים
     */
    @Query("SELECT cs FROM ChatSession cs WHERE cs.user = :user AND " +
            "SIZE(cs.documents) > 0")
    List<ChatSession> findSessionsWithDocuments(@Param("user") User user);

    /**
     * עדכון זמן פעילות אחרונה
     */
    @Query("UPDATE ChatSession cs SET cs.lastActivityAt = :activityTime " +
            "WHERE cs.id = :sessionId")
    void updateLastActivityTime(@Param("sessionId") Long sessionId,
                                @Param("activityTime") LocalDateTime activityTime);
}