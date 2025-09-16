package com.smartdocumentchat.config;

import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.retriever.EmbeddingStoreRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {

    private final QdrantProperties qdrantProperties;

    @Value("${OPENAI_API_KEY}")
    private String openaiApiKey;

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("Creating OpenAI embedding model");
        return OpenAiEmbeddingModel.builder()
                .apiKey(openaiApiKey)
                .modelName("text-embedding-3-large")
                .build();
    }

    /**
     * Default embedding store - משמש כברירת מחדל וכ-fallback
     */
    @Bean(name = "defaultEmbeddingStore")
    public EmbeddingStore<TextSegment> defaultEmbeddingStore() {
        log.info("Creating default Qdrant embedding store - Host: {}, Port: {}, Collection: {}",
                qdrantProperties.getHost(),
                qdrantProperties.getPort(),
                qdrantProperties.getCollectionName());

        return QdrantEmbeddingStore.builder()
                .host(qdrantProperties.getHost())
                .port(qdrantProperties.getPort())
                .collectionName(qdrantProperties.getCollectionName())
                .build();
    }

    /**
     * Embedding store ingestor עם session awareness
     */
    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor() {
        log.info("Creating embedding store ingestor with chunk size: 1200, overlap: 200");
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1200, 200))
                .embeddingModel(embeddingModel())
                .embeddingStore(defaultEmbeddingStore()) // Uses default store for general ingestion
                .build();
    }

    /**
     * Session-aware embedding store ingestor factory
     */
    @Bean
    public SessionAwareIngestorFactory sessionAwareIngestorFactory() {
        return new SessionAwareIngestorFactory();
    }

    /**
     * Conversational retrieval chain - עודכן לתמיכה בsessions
     */
    @Bean
    public ConversationalRetrievalChain conversationalRetrievalChain() {
        log.info("Creating conversational retrieval chain with default embedding store");
        return ConversationalRetrievalChain.builder()
                .chatLanguageModel(OpenAiChatModel.withApiKey(openaiApiKey))
                .retriever(EmbeddingStoreRetriever.from(defaultEmbeddingStore(), embeddingModel()))
                .build();
    }

    /**
     * Factory class for creating session-specific ingestors
     */
    public class SessionAwareIngestorFactory {

        /**
         * יצירת ingestor עבור embedding store ספציפי
         */
        public EmbeddingStoreIngestor createIngestorForStore(EmbeddingStore<TextSegment> embeddingStore) {
            log.debug("Creating embedding store ingestor for specific session store");

            return EmbeddingStoreIngestor.builder()
                    .documentSplitter(DocumentSplitters.recursive(1200, 200))
                    .embeddingModel(embeddingModel())
                    .embeddingStore(embeddingStore)
                    .build();
        }

        /**
         * יצירת retrieval chain עבור session ספציפי
         */
        public ConversationalRetrievalChain createChainForStore(EmbeddingStore<TextSegment> embeddingStore) {
            log.debug("Creating conversational retrieval chain for specific session store");

            return ConversationalRetrievalChain.builder()
                    .chatLanguageModel(OpenAiChatModel.withApiKey(openaiApiKey))
                    .retriever(EmbeddingStoreRetriever.from(embeddingStore, embeddingModel()))
                    .build();
        }

        /**
         * יצירת retriever עבור session ספציפי
         */
        public EmbeddingStoreRetriever createRetrieverForStore(EmbeddingStore<TextSegment> embeddingStore) {
            return createRetrieverForStore(embeddingStore, 5); // Default max results
        }

        /**
         * יצירת retriever עבור session ספציפי עם הגדרת max results
         */
        public EmbeddingStoreRetriever createRetrieverForStore(EmbeddingStore<TextSegment> embeddingStore,
                                                               int maxResults) {
            log.debug("Creating embedding store retriever for specific session store (maxResults: {})", maxResults);

            return EmbeddingStoreRetriever.from(embeddingStore, embeddingModel(), maxResults);
        }

    }
}