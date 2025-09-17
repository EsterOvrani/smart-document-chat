package com.smartdocumentchat.service;

import com.smartdocumentchat.config.QdrantProperties;
import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class QdrantVectorService {

    private final QdrantProperties qdrantProperties;
    private final EmbeddingStore<TextSegment> defaultEmbeddingStore;

    // Cache for session-specific embedding stores
    private final Map<String, EmbeddingStore<TextSegment>> sessionEmbeddingStores = new ConcurrentHashMap<>();

    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Qdrant Vector service with session-based collections support");
            log.info("Default collection: {}, Host: {}:{}",
                    qdrantProperties.getCollectionName(),
                    qdrantProperties.getHost(),
                    qdrantProperties.getPort());

            log.info("Qdrant Vector service initialized successfully");

        } catch (Exception e) {
            log.error("Failed to initialize Qdrant Vector service: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Qdrant Vector service", e);
        }
    }

    /**
     * יצירת שם collection לשיחה ספציפית
     */
    public String generateSessionCollectionName(Long sessionId, Long userId) {
        if (sessionId == null || userId == null) {
            throw new IllegalArgumentException("Session ID and User ID cannot be null");
        }

        return String.format("session_%d_user_%d", sessionId, userId);
    }

    /**
     * קבלת embedding store לשיחה ספציפית
     */
    public EmbeddingStore<TextSegment> getEmbeddingStoreForSession(ChatSession chatSession) {
        validateChatSession(chatSession);

        String collectionName = generateSessionCollectionName(
                chatSession.getId(),
                chatSession.getUser().getId()
        );

        return getOrCreateEmbeddingStore(collectionName);
    }

    /**
     * קבלת embedding store לשיחה לפי IDs
     */
    public EmbeddingStore<TextSegment> getEmbeddingStoreForSession(Long sessionId, Long userId) {
        String collectionName = generateSessionCollectionName(sessionId, userId);
        return getOrCreateEmbeddingStore(collectionName);
    }

    /**
     * יצירה או קבלה של embedding store לcollection ספציפי
     */
    private EmbeddingStore<TextSegment> getOrCreateEmbeddingStore(String collectionName) {
        return sessionEmbeddingStores.computeIfAbsent(collectionName, name -> {
            log.info("Creating new Qdrant embedding store for collection: {}", name);

            try {
                EmbeddingStore<TextSegment> store = QdrantEmbeddingStore.builder()
                        .host(qdrantProperties.getHost())
                        .port(qdrantProperties.getPort())
                        .collectionName(name)
                        .build();

                log.info("Successfully created embedding store for collection: {}", name);
                return store;

            } catch (Exception e) {
                log.error("Failed to create embedding store for collection: {}", name, e);
                throw new RuntimeException("Failed to create embedding store for collection: " + name, e);
            }
        });
    }

    /**
     * מחיקת embedding store לשיחה (ניקיון זיכרון)
     */
    public void removeEmbeddingStoreForSession(Long sessionId, Long userId) {
        String collectionName = generateSessionCollectionName(sessionId, userId);

        EmbeddingStore<TextSegment> removed = sessionEmbeddingStores.remove(collectionName);

        if (removed != null) {
            log.info("Removed embedding store from cache for collection: {}", collectionName);
        } else {
            log.debug("No embedding store found in cache for collection: {}", collectionName);
        }
    }

    /**
     * בדיקה אם יש embedding store לשיחה
     */
    public boolean hasEmbeddingStoreForSession(Long sessionId, Long userId) {
        String collectionName = generateSessionCollectionName(sessionId, userId);
        return sessionEmbeddingStores.containsKey(collectionName);
    }

    /**
     * קבלת מידע על כמות collections פעילים
     */
    public int getActiveCollectionsCount() {
        return sessionEmbeddingStores.size();
    }

    /**
     * קבלת רשימת כל שמות הcollections הפעילים
     */
    public java.util.Set<String> getActiveCollectionNames() {
        return new java.util.HashSet<>(sessionEmbeddingStores.keySet());
    }

    /**
     * ניקוי cache של embedding stores (לתחזוקה)
     */
    public void clearEmbeddingStoresCache() {
        int clearedCount = sessionEmbeddingStores.size();
        sessionEmbeddingStores.clear();
        log.info("Cleared {} embedding stores from cache", clearedCount);
    }

    /**
     * קבלת embedding store ברירת מחדל (לתאימות לאחור)
     */
    public EmbeddingStore<TextSegment> getDefaultEmbeddingStore() {
        return defaultEmbeddingStore;
    }

    /**
     * קבלת שם collection ברירת מחדל
     */
    public String getCurrentCollectionName() {
        return qdrantProperties.getCollectionName();
    }

    /**
     * מידע על הגדרות Qdrant
     */
    public String getCollectionInfo() {
        return String.format("Host: %s:%d, Default Collection: %s, Active Session Collections: %d",
                qdrantProperties.getHost(),
                qdrantProperties.getPort(),
                qdrantProperties.getCollectionName(),
                sessionEmbeddingStores.size());
    }

    /**
     * בדיקה אם השירות מוכן לעבודה
     */
    public boolean isReady() {
        return defaultEmbeddingStore != null;
    }

    /**
     * סטטיסטיקות על השימוש
     */
    public Map<String, Object> getUsageStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("activeSessionCollections", sessionEmbeddingStores.size());
        stats.put("defaultCollection", qdrantProperties.getCollectionName());
        stats.put("host", qdrantProperties.getHost());
        stats.put("port", qdrantProperties.getPort());
        stats.put("collectionNames", new java.util.ArrayList<>(sessionEmbeddingStores.keySet()));

        return stats;
    }

    // Helper methods

    private void validateChatSession(ChatSession chatSession) {
        if (chatSession == null) {
            throw new IllegalArgumentException("ChatSession cannot be null");
        }

        if (chatSession.getId() == null) {
            throw new IllegalArgumentException("ChatSession ID cannot be null");
        }

        if (chatSession.getUser() == null || chatSession.getUser().getId() == null) {
            throw new IllegalArgumentException("ChatSession must have a valid user with ID");
        }

        if (!chatSession.getActive()) {
            throw new IllegalArgumentException("ChatSession must be active");
        }
    }
}