package com.smartdocumentchat.service;

import com.smartdocumentchat.config.MinioProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
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

    @PostConstruct
    public void initialize() {
        createBucketIfNotExists();
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