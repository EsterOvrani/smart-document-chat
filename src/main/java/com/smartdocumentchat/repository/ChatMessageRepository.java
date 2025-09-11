package com.smartdocumentchat.repository;

import com.smartdocumentchat.entity.ChatMessage;
import com.smartdocumentchat.entity.ChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * מציאת כל ההודעות של שיחה לפי סדר יצירה
     */
    List<ChatMessage> findByChatSessionOrderByMessageOrderAsc(ChatSession chatSession);

    /**
     * מציאת הודעות עם פגינציה
     */
    Page<ChatMessage> findByChatSessionOrderByMessageOrderAsc(ChatSession chatSession, Pageable pageable);

    /**
     * מציאת הודעות לפי סוג
     */
    List<ChatMessage> findByChatSessionAndMessageTypeOrderByMessageOrderAsc(
            ChatSession chatSession, ChatMessage.MessageType messageType);

    /**
     * מציאת ההודעה האחרונה בשיחה
     */
    Optional<ChatMessage> findFirstByChatSessionOrderByMessageOrderDesc(ChatSession chatSession);

    /**
     * מציאת הודעות לפי תאריך
     */
    List<ChatMessage> findByChatSessionAndCreatedAtBetweenOrderByMessageOrderAsc(
            ChatSession chatSession, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * חיפוש הודעות לפי תוכן
     */
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.chatSession = :chatSession AND " +
            "LOWER(cm.content) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "ORDER BY cm.messageOrder ASC")
    List<ChatMessage> searchByContent(@Param("chatSession") ChatSession chatSession,
                                      @Param("searchTerm") String searchTerm);

    /**
     * ספירת הודעות לפי סוג
     */
    long countByChatSessionAndMessageType(ChatSession chatSession, ChatMessage.MessageType messageType);

    /**
     * ספירת כל ההודעות בשיחה
     */
    long countByChatSession(ChatSession chatSession);

    /**
     * מציאת המספר הסידורי הבא להודעה
     */
    @Query("SELECT COALESCE(MAX(cm.messageOrder), 0) + 1 FROM ChatMessage cm WHERE cm.chatSession = :chatSession")
    Integer findNextMessageOrder(@Param("chatSession") ChatSession chatSession);

    /**
     * מציאת הודעות משתמש בלבד
     */
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.chatSession = :chatSession AND " +
            "cm.messageType = 'USER' ORDER BY cm.messageOrder ASC")
    List<ChatMessage> findUserMessages(@Param("chatSession") ChatSession chatSession);

    /**
     * מציאת תשובות עוזר בלבד
     */
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.chatSession = :chatSession AND " +
            "cm.messageType = 'ASSISTANT' ORDER BY cm.messageOrder ASC")
    List<ChatMessage> findAssistantMessages(@Param("chatSession") ChatSession chatSession);

    /**
     * מציאת הודעות עם מידע על עיבוד
     */
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.chatSession = :chatSession AND " +
            "cm.processingTimeMs IS NOT NULL ORDER BY cm.messageOrder ASC")
    List<ChatMessage> findMessagesWithProcessingInfo(@Param("chatSession") ChatSession chatSession);

    /**
     * סטטיסטיקות עיבוד - זמן עיבוד ממוצע
     */
    @Query("SELECT AVG(cm.processingTimeMs) FROM ChatMessage cm WHERE cm.chatSession = :chatSession AND " +
            "cm.messageType = 'ASSISTANT' AND cm.processingTimeMs IS NOT NULL")
    Double getAverageProcessingTime(@Param("chatSession") ChatSession chatSession);

    /**
     * סטטיסטיקות עיבוד - סך טוקנים
     */
    @Query("SELECT SUM(cm.tokenCount) FROM ChatMessage cm WHERE cm.chatSession = :chatSession AND " +
            "cm.tokenCount IS NOT NULL")
    Long getTotalTokenCount(@Param("chatSession") ChatSession chatSession);

    /**
     * מציאת הודעות שגיאה
     */
    List<ChatMessage> findByChatSessionAndMessageTypeOrderByCreatedAtDesc(
            ChatSession chatSession, ChatMessage.MessageType messageType);

    /**
     * מחיקת הודעות ישנות (למעלה מX ימים)
     */
    @Query("DELETE FROM ChatMessage cm WHERE cm.chatSession = :chatSession AND " +
            "cm.createdAt < :beforeDate")
    void deleteOldMessages(@Param("chatSession") ChatSession chatSession,
                           @Param("beforeDate") LocalDateTime beforeDate);

    /**
     * מציאת הודעות לפי מודל שמשמש
     */
    List<ChatMessage> findByChatSessionAndModelUsedOrderByCreatedAtDesc(
            ChatSession chatSession, String modelUsed);

    /**
     * מציאת X הודעות אחרונות
     */
    @Query("SELECT cm FROM ChatMessage cm WHERE cm.chatSession = :chatSession " +
            "ORDER BY cm.messageOrder DESC LIMIT :limit")
    List<ChatMessage> findLastMessages(@Param("chatSession") ChatSession chatSession,
                                       @Param("limit") int limit);

    /**
     * בדיקה אם יש הודעות בשיחה
     */
    boolean existsByChatSession(ChatSession chatSession);
}