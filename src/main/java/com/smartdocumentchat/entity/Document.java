package com.smartdocumentchat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 255)
    private String originalFileName;

    @Column(length = 100)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;

    @Column(name = "processing_progress")
    private Integer processingProgress = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "character_count")
    private Integer characterCount;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "vector_collection_name", length = 255)
    private String vectorCollectionName;

    @Column(columnDefinition = "boolean default true")
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    // Relationships
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private ChatSession chatSession;

    // Enum for processing status
    public enum ProcessingStatus {
        PENDING,      // ממתין לעיבוד
        PROCESSING,   // בעיבוד
        COMPLETED,    // הושלם
        FAILED,       // נכשל
        CANCELLED     // בוטל
    }

    // Helper methods
    public String getDisplayName() {
        return originalFileName != null ? originalFileName : fileName;
    }

    public boolean isProcessed() {
        return ProcessingStatus.COMPLETED.equals(processingStatus);
    }

    public boolean isProcessing() {
        return ProcessingStatus.PROCESSING.equals(processingStatus);
    }

    public boolean hasFailed() {
        return ProcessingStatus.FAILED.equals(processingStatus);
    }

    public String getFileSizeFormatted() {
        if (fileSize == null) return "לא ידוע";

        if (fileSize < 1024) {
            return fileSize + " B";
        } else if (fileSize < 1024 * 1024) {
            return String.format("%.1f KB", fileSize / 1024.0);
        } else {
            return String.format("%.1f MB", fileSize / (1024.0 * 1024.0));
        }
    }

    public void updateProcessingStatus(ProcessingStatus status, String errorMessage) {
        this.processingStatus = status;
        this.errorMessage = errorMessage;
        if (status == ProcessingStatus.COMPLETED) {
            this.processedAt = LocalDateTime.now();
            this.processingProgress = 100;
        } else if (status == ProcessingStatus.FAILED) {
            this.processingProgress = 0;
        }
    }

    public void updateProgress(int progress) {
        this.processingProgress = Math.max(0, Math.min(100, progress));
        if (progress >= 100) {
            this.processingStatus = ProcessingStatus.COMPLETED;
            this.processedAt = LocalDateTime.now();
        }
    }
}