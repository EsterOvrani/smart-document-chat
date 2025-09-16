package com.smartdocumentchat.service;

import com.smartdocumentchat.config.QdrantProperties;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

@Service
@RequiredArgsConstructor
@Slf4j
public class QdrantVectorService {

    private final QdrantProperties qdrantProperties;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @PostConstruct
    public void initialize() {
        try {
            log.info("Initializing Qdrant Vector service");
            log.info("Using Qdrant EmbeddingStore for collection: {}",
                    qdrantProperties.getCollectionName());

            log.info("Qdrant Vector service initialized successfully");

        } catch (Exception e) {
            log.error("Failed to initialize Qdrant Vector service: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Qdrant Vector service", e);
        }
    }

    public String getCurrentCollectionName() {
        return qdrantProperties.getCollectionName();
    }

    public boolean isReady() {
        return embeddingStore != null;
    }

    public String getCollectionInfo() {
        return String.format("Collection: %s, Host: %s:%d (Qdrant via LangChain4j)",
                qdrantProperties.getCollectionName(),
                qdrantProperties.getHost(),
                qdrantProperties.getPort());
    }
}