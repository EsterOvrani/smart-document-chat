package com.smartdocumentchat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    private MessageType messageType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "processing_time_ms")
    private Long processingTimeMs;

    @Column(name = "model_used", length = 100)
    private String modelUsed;

    @Column(name = "message_order", nullable = false)
    private Integer messageOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Relationship to ChatSession
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private ChatSession chatSession;

    // Enum for message types
    public enum MessageType {
        USER,           // הודעת משתמש
        ASSISTANT,      // תשובת עוזר AI
        SYSTEM,         // הודעת מערכת
        ERROR,          // הודעת שגיאה
        NOTIFICATION    // הודעת התראה
    }

    // Helper methods
    public boolean isUserMessage() {
        return MessageType.USER.equals(messageType);
    }

    public boolean isAssistantMessage() {
        return MessageType.ASSISTANT.equals(messageType);
    }

    public boolean isSystemMessage() {
        return MessageType.SYSTEM.equals(messageType);
    }

    public String getContentPreview(int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    public String getFormattedCreatedAt() {
        if (createdAt == null) return "";
        // פורמט: dd/MM/yyyy HH:mm
        return createdAt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }

    public String getMessageTypeDisplay() {
        return switch (messageType) {
            case USER -> "משתמש";
            case ASSISTANT -> "עוזר";
            case SYSTEM -> "מערכת";
            case ERROR -> "שגיאה";
            case NOTIFICATION -> "התראה";
        };
    }

    public void updateProcessingInfo(String model, Long processingTime, Integer tokens) {
        this.modelUsed = model;
        this.processingTimeMs = processingTime;
        this.tokenCount = tokens;
    }

    public boolean hasProcessingInfo() {
        return processingTimeMs != null && tokenCount != null;
    }

    public String getProcessingTimeFormatted() {
        if (processingTimeMs == null) return "לא ידוע";

        if (processingTimeMs < 1000) {
            return processingTimeMs + " ms";
        } else {
            return String.format("%.1f s", processingTimeMs / 1000.0);
        }
    }
}