package com.smartdocumentchat.repository;

import com.smartdocumentchat.entity.Document;
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
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * מציאת כל המסמכים של משתמש
     */
    List<Document> findByUserOrderByCreatedAtDesc(User user);

    /**
     * מציאת מסמכים לפי שיחה
     */
    List<Document> findByChatSessionOrderByCreatedAtDesc(ChatSession chatSession);

    /**
     * מציאת מסמכים פעילים לפי שיחה
     */
    List<Document> findByChatSessionAndActiveTrueOrderByCreatedAtDesc(ChatSession chatSession);

    /**
     * מציאת מסמך לפי שם קובץ ושיחה
     */
    Optional<Document> findByChatSessionAndFileName(ChatSession chatSession, String fileName);

    /**
     * מציאת מסמכים לפי סטטוס עיבוד
     */
    List<Document> findByProcessingStatusOrderByCreatedAtDesc(Document.ProcessingStatus status);

    /**
     * מציאת מסמכים לפי משתמש וסטטוס עיבוד
     */
    List<Document> findByUserAndProcessingStatusOrderByCreatedAtDesc(
            User user, Document.ProcessingStatus status);

    /**
     * מציאת מסמכים לפי סוג קובץ
     */
    List<Document> findByUserAndFileTypeOrderByCreatedAtDesc(User user, String fileType);

    /**
     * חיפוש מסמכים לפי שם
     */
    @Query("SELECT d FROM Document d WHERE d.user = :user AND " +
            "(LOWER(d.fileName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
            "LOWER(d.originalFileName) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    List<Document> searchByFileName(@Param("user") User user,
                                    @Param("searchTerm") String searchTerm);

    /**
     * מציאת מסמכים לפי תאריך יצירה
     */
    List<Document> findByUserAndCreatedAtBetweenOrderByCreatedAtDesc(
            User user, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * מציאת מסמכים לפי גודל קובץ
     */
    @Query("SELECT d FROM Document d WHERE d.user = :user AND " +
            "d.fileSize BETWEEN :minSize AND :maxSize ORDER BY d.createdAt DESC")
    List<Document> findByFileSizeRange(@Param("user") User user,
                                       @Param("minSize") Long minSize,
                                       @Param("maxSize") Long maxSize);

    /**
     * ספירת מסמכים לפי משתמש וסטטוס
     */
    long countByUserAndProcessingStatus(User user, Document.ProcessingStatus status);

    /**
     * ספירת מסמכים פעילים לפי שיחה
     */
    long countByChatSessionAndActiveTrue(ChatSession chatSession);

    /**
     * מציאת מסמכים שנכשלו בעיבוד
     */
    @Query("SELECT d FROM Document d WHERE d.user = :user AND " +
            "d.processingStatus = 'FAILED' AND d.createdAt > :since")
    List<Document> findFailedDocumentsSince(@Param("user") User user,
                                            @Param("since") LocalDateTime since);

    /**
     * מציאת מסמכים ללא פעילות לאחרונה
     */
    @Query("SELECT d FROM Document d WHERE d.chatSession.user = :user AND " +
            "d.updatedAt < :beforeDate AND d.active = true")
    List<Document> findInactiveDocuments(@Param("user") User user,
                                         @Param("beforeDate") LocalDateTime beforeDate);

    /**
     * מציאת מסמכים לפי hash התוכן (למניעת כפילויות)
     */
    Optional<Document> findByUserAndContentHash(User user, String contentHash);

    /**
     * מציאת מסמכים לפי שם קולקשן וקטורי
     */
    List<Document> findByVectorCollectionName(String vectorCollectionName);

    /**
     * עדכון סטטוס עיבוד
     */
    @Query("UPDATE Document d SET d.processingStatus = :status, " +
            "d.processingProgress = :progress, d.errorMessage = :errorMessage " +
            "WHERE d.id = :documentId")
    void updateProcessingStatus(@Param("documentId") Long documentId,
                                @Param("status") Document.ProcessingStatus status,
                                @Param("progress") Integer progress,
                                @Param("errorMessage") String errorMessage);

    /**
     * מציאת מסמכים מעובדים לפי שיחה
     */
    @Query("SELECT d FROM Document d WHERE d.chatSession = :chatSession AND " +
            "d.processingStatus = 'COMPLETED' AND d.active = true")
    List<Document> findProcessedDocumentsBySession(@Param("chatSession") ChatSession chatSession);
}