package com.smartdocumentchat.service;

import com.smartdocumentchat.config.MinioProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private final S3Client s3Client;
    private final MinioProperties minioProperties;
    private S3Presigner s3Presigner;

    @PostConstruct
    public void initialize() {
        createBucketIfNotExists();
        initializePresigner();
    }


    /**
     * אתחול S3Presigner
     */
    private void initializePresigner() {
        try {
            String endpoint = minioProperties.isUseSSL() ?
                    "https://" + minioProperties.getEndpoint() :
                    "http://" + minioProperties.getEndpoint();

            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    minioProperties.getAccessKey(),
                    minioProperties.getSecretKey()
            );

            this.s3Presigner = S3Presigner.builder()
                    .endpointOverride(URI.create(endpoint))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .region(Region.of(minioProperties.getRegion()))
                    .build();

            log.info("S3 Presigner initialized successfully");
        } catch (Exception e) {
            log.error("Failed to initialize S3 Presigner", e);
            throw new RuntimeException("Failed to initialize presigner", e);
        }
    }

    /**
     * יצירת presigned URL להורדת קובץ
     */
    public String generatePresignedUrl(String objectKey, Duration duration) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .key(objectKey)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(duration)
                    .getObjectRequest(getObjectRequest)
                    .build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String url = presignedRequest.url().toString();

            log.info("Generated presigned URL for: {} (valid for {} seconds)",
                    objectKey, duration.getSeconds());
            return url;

        } catch (Exception e) {
            log.error("Failed to generate presigned URL for: {}", objectKey, e);
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
    }

    /**
     * יצירת presigned URL עם זמן ברירת מחדל (1 שעה)
     */
    public String generatePresignedUrl(String objectKey) {
        return generatePresignedUrl(objectKey, Duration.ofHours(1));
    }

    /**
     * יצירת presigned URL קצר טווח (15 דקות)
     */
    public String generateShortLivedPresignedUrl(String objectKey) {
        return generatePresignedUrl(objectKey, Duration.ofMinutes(15));
    }

    /**
     * יצירת presigned URL ארוך טווח (7 ימים)
     */
    public String generateLongLivedPresignedUrl(String objectKey) {
        return generatePresignedUrl(objectKey, Duration.ofDays(7));
    }

    /**
     * יצירת bucket אם לא קיים
     */
    private void createBucketIfNotExists() {
        try {
            HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .build();

            s3Client.headBucket(headBucketRequest);
            log.info("Bucket '{}' already exists", minioProperties.getBucketName());

        } catch (NoSuchBucketException e) {
            log.info("Bucket '{}' does not exist. Creating...", minioProperties.getBucketName());

            CreateBucketRequest createBucketRequest = CreateBucketRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .build();

            s3Client.createBucket(createBucketRequest);
            log.info("Bucket '{}' created successfully", minioProperties.getBucketName());

        } catch (Exception e) {
            log.error("Error checking/creating bucket: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize MinIO bucket", e);
        }
    }

    /**
     * העלאת קובץ ל-MinIO
     */
    public String uploadFile(MultipartFile file, String objectKey) throws IOException {
        return uploadFile(file.getInputStream(), objectKey, file.getContentType(), file.getSize());
    }

    /**
     * העלאת קובץ עם metadata
     */
    public String uploadFile(InputStream inputStream, String objectKey, String contentType, long fileSize) {
        try {
            Map<String, String> metadata = new HashMap<>();
            metadata.put("upload-time", LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .key(objectKey)
                    .contentType(contentType)
                    .metadata(metadata)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(inputStream, fileSize));

            log.info("File uploaded successfully: {} (size: {} bytes)", objectKey, fileSize);
            return objectKey;

        } catch (Exception e) {
            log.error("Failed to upload file: {}", objectKey, e);
            throw new RuntimeException("Failed to upload file to MinIO", e);
        }
    }

    /**
     * קבלת קובץ מ-MinIO
     */
    public InputStream getFile(String objectKey) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .key(objectKey)
                    .build();

            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(getRequest);
            log.debug("Retrieved file: {}", objectKey);
            return response;

        } catch (Exception e) {
            log.error("Failed to retrieve file: {}", objectKey, e);
            throw new RuntimeException("Failed to retrieve file from MinIO", e);
        }
    }

    /**
     * מחיקת קובץ מ-MinIO
     */
    public boolean deleteFile(String objectKey) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .key(objectKey)
                    .build();

            s3Client.deleteObject(deleteRequest);
            log.info("File deleted successfully: {}", objectKey);
            return true;

        } catch (Exception e) {
            log.error("Failed to delete file: {}", objectKey, e);
            return false;
        }
    }

    /**
     * בדיקה אם קובץ קיים
     */
    public boolean fileExists(String objectKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .key(objectKey)
                    .build();

            s3Client.headObject(headRequest);
            return true;

        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            log.error("Error checking file existence: {}", objectKey, e);
            return false;
        }
    }

    /**
     * קבלת metadata של קובץ
     */
    public Map<String, String> getFileMetadata(String objectKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .key(objectKey)
                    .build();

            HeadObjectResponse response = s3Client.headObject(headRequest);
            return response.metadata();

        } catch (Exception e) {
            log.error("Failed to get file metadata: {}", objectKey, e);
            return new HashMap<>();
        }
    }

    /**
     * קבלת גודל קובץ
     */
    public long getFileSize(String objectKey) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(minioProperties.getBucketName())
                    .key(objectKey)
                    .build();

            HeadObjectResponse response = s3Client.headObject(headRequest);
            return response.contentLength();

        } catch (Exception e) {
            log.error("Failed to get file size: {}", objectKey, e);
            return -1;
        }
    }


}