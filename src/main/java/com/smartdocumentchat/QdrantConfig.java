package com.smartdocumentchat;

import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.retriever.EmbeddingStoreRetriever;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
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

    @Bean
    public QdrantClient qdrantClient() {
        log.info("Creating Qdrant client - Host: {}, Port: {}",
                qdrantProperties.getHost(), qdrantProperties.getPort());

        return new QdrantClient(
                QdrantGrpcClient.newBuilder(
                        qdrantProperties.getHost(),
                        qdrantProperties.getPort(),
                        qdrantProperties.isUseTls()
                ).build()
        );
    }

    @Bean
    public InMemoryEmbeddingStore embeddingStore() {
        log.info("Creating embedding store (temporary in-memory until custom Qdrant implementation)");
        log.info("Target Qdrant config - Collection: {}", qdrantProperties.getCollectionName());

        // כרגע in-memory, אבל בשלב 4 נחליף בQdrant wrapper אמיתי
        return new InMemoryEmbeddingStore();
    }

    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor() {
        log.info("Creating embedding store ingestor with chunk size: 1200, overlap: 200");
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1200, 200))
                .embeddingModel(embeddingModel())
                .embeddingStore(embeddingStore())
                .build();
    }

    @Bean
    public ConversationalRetrievalChain conversationalRetrievalChain() {
        log.info("Creating conversational retrieval chain");
        return ConversationalRetrievalChain.builder()
                .chatLanguageModel(OpenAiChatModel.withApiKey(openaiApiKey))
                .retriever(EmbeddingStoreRetriever.from(embeddingStore(), embeddingModel()))
                .build();
    }
}