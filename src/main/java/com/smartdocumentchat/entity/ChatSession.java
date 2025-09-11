package com.smartdocumentchat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;


@Entity
@Table(name = "chat_sessions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(columnDefinition = "boolean default true")
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    // Relationship to User
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Relationship to ChatMessages (One-to-Many)
    @OneToMany(mappedBy = "chatSession", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ChatMessage> messages = new ArrayList<>();

    // Relationship to Documents (One-to-Many)
    @OneToMany(mappedBy = "chatSession", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Document> documents = new ArrayList<>();

    // Helper methods
    public void addMessage(ChatMessage message) {
        messages.add(message);
        message.setChatSession(this);
        this.lastActivityAt = LocalDateTime.now();
    }

    public void removeMessage(ChatMessage message) {
        messages.remove(message);
        message.setChatSession(null);
    }

    public void addDocument(Document document) {
        documents.add(document);
        document.setChatSession(this);
    }

    public void removeDocument(Document document) {
        documents.remove(document);
        document.setChatSession(null);
    }

    public int getMessageCount() {
        return messages.size();
    }

    public int getDocumentCount() {
        return documents.size();
    }

    public String getDisplayTitle() {
        if (title != null && !title.trim().isEmpty()) {
            return title;
        }
        return "שיחה " + id;
    }
}