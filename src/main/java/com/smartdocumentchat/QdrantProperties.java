package com.smartdocumentchat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "qdrant")
public class QdrantProperties {

    private String host = "localhost";
    private int port = 6333;
    private String collectionName = "smart_documents";
    private boolean useTls = false;
    private String apiKey;

    // Embedding configuration
    private int dimension = 3072; // OpenAI text-embedding-3-large dimension
    private String distance = "Cosine"; // Distance metric for similarity search
}