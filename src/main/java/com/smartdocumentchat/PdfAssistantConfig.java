package com.smartdocumentchat;

import dev.langchain4j.chain.ConversationalRetrievalChain;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.retriever.EmbeddingStoreRetriever;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.cassandra.AstraDbEmbeddingConfiguration;
import dev.langchain4j.store.embedding.cassandra.AstraDbEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class PdfAssistantConfig {

    @Value("${ASTRA_TOKEN}")
    private String astraToken;

    @Value("${ASTRA_DATABASE_ID}")
    private String databaseId;

    @Value("${OPENAI_API_KEY}")
    private String openaiApiKey;



    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(openaiApiKey)
                .modelName("text-embedding-3-large")
                .build();
    }

    @Bean
    public AstraDbEmbeddingStore astraDbEmbeddingStore() {
        return new AstraDbEmbeddingStore(AstraDbEmbeddingConfiguration
                .builder()
                .token(astraToken)
                .databaseId(databaseId)
                .databaseRegion("us-east1")
                .keyspace("pdfassistant")
                .table("pdfchat_openai")
                .dimension(3072)
                .build());
    }

    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor() {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1200, 200))
                .embeddingModel(embeddingModel())
                .embeddingStore(astraDbEmbeddingStore())
                .build();
    }

    @Bean
    public ConversationalRetrievalChain conversationalRetrievalChain() {
        return ConversationalRetrievalChain.builder()
                .chatLanguageModel(OpenAiChatModel.withApiKey(openaiApiKey))
                .retriever(EmbeddingStoreRetriever.from(astraDbEmbeddingStore(), embeddingModel()))
                .build();
    }
}