package com.smartdocumentchat.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class MinioConfig {

    private final MinioProperties minioProperties;

    @Bean
    public S3Client s3Client() {
        log.info("Initializing MinIO S3 client - Endpoint: {}, Bucket: {}",
                minioProperties.getEndpoint(), minioProperties.getBucketName());

        String endpoint = minioProperties.isUseSSL() ?
                "https://" + minioProperties.getEndpoint() :
                "http://" + minioProperties.getEndpoint();

        AwsBasicCredentials credentials = AwsBasicCredentials.create(
                minioProperties.getAccessKey(),
                minioProperties.getSecretKey()
        );

        S3Client s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .region(Region.of(minioProperties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();

        log.info("MinIO S3 client initialized successfully");
        return s3Client;
    }
}