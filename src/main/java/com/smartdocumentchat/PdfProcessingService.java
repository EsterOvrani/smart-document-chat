package com.smartdocumentchat;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PdfProcessingService {

    private final EmbeddingStoreIngestor embeddingStoreIngestor;

    // מעקב אחר הקובץ הנוכחי (קובץ אחד בלבד)
    private String currentFileId = null;
    private String currentFileName = null;
    private LocalDateTime currentUploadTime = null;

    /**
     * עיבוד קובץ PDF חדש - מחליף את הקובץ הקודם
     */
    public String processPdfFile(MultipartFile file) throws IOException {
        validateFile(file);

        String fileName = file.getOriginalFilename();
        String fileId = generateFileId(fileName);

        log.info("מתחיל עיבוד קובץ PDF: {} (יחליף את הקובץ הנוכחי אם קיים)", fileName);

        try (InputStream inputStream = file.getInputStream()) {
            // יצירת מסמך מהקובץ שהועלה
            Document document = createDocumentFromInputStream(inputStream, fileName, fileId);

            // הכנסת המסמך למסד הנתונים (מחליף את הקודם)
            embeddingStoreIngestor.ingest(document);

            // עדכון פרטי הקובץ הנוכחי
            currentFileId = fileId;
            currentFileName = fileName;
            currentUploadTime = LocalDateTime.now();

            log.info("הקובץ {} עובד בהצלחה עם מזהה: {}", fileName, fileId);
            return fileId;
        }
    }

    /**
     * קבלת מזהה הקובץ הנוכחי
     */
    public String getCurrentActiveFileId() {
        return currentFileId;
    }

    /**
     * קבלת שם הקובץ הנוכחי
     */
    public String getCurrentActiveFileName() {
        return currentFileName;
    }

    /**
     * קבלת זמן העלאה של הקובץ הנוכחי
     */
    public String getCurrentUploadTime() {
        if (currentUploadTime != null) {
            return currentUploadTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        }
        return null;
    }

    /**
     * קבלת מידע על הקובץ הנוכחי
     */
    public Map<String, String> getUploadedFiles() {
        Map<String, String> result = new ConcurrentHashMap<>();
        if (currentFileId != null) {
            result.put(currentFileId, currentFileName);
        }
        return result;
    }

    /**
     * קבלת מידע מפורט על הקובץ הנוכחי
     */
    public Map<String, Object> getCurrentFileInfo() {
        Map<String, Object> info = new ConcurrentHashMap<>();
        if (currentFileId != null) {
            info.put("fileId", currentFileId);
            info.put("fileName", currentFileName);
            info.put("uploadTime", getCurrentUploadTime());
            info.put("isActive", true);
        }
        return info;
    }

    /**
     * בדיקה אם יש קובץ במערכת
     */
    public boolean isFileExists(String fileId) {
        return currentFileId != null && currentFileId.equals(fileId);
    }

    /**
     * מחיקת הקובץ הנוכחי
     */
    public boolean deleteCurrentFile() {
        if (currentFileId != null) {
            String deletedFileName = currentFileName;
            currentFileId = null;
            currentFileName = null;
            currentUploadTime = null;
            log.info("המידע על הקובץ {} נמחק מהמעקב", deletedFileName);
            return true;
        }
        return false;
    }

    /**
     * בדיקת תקינות הקובץ
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("הקובץ ריק");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("הקובץ חייב להיות בפורמט PDF");
        }

        if (file.getSize() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("הקובץ גדול מדי. מקסימום 50MB");
        }
    }

    /**
     * יצירת מסמך מ-InputStream
     */
    private Document createDocumentFromInputStream(InputStream inputStream, String fileName, String fileId) throws IOException {
        ApachePdfBoxDocumentParser parser = new ApachePdfBoxDocumentParser();

        byte[] fileBytes = inputStream.readAllBytes();
        Document document = parser.parse(new java.io.ByteArrayInputStream(fileBytes));

        // הוספת metadata למסמך
        document.metadata().add("source", fileName);
        document.metadata().add("file_id", fileId);
        document.metadata().add("upload_time", LocalDateTime.now().toString());

        return document;
    }

    /**
     * יצירת מזהה ייחודי לקובץ
     */
    private String generateFileId(String fileName) {
        return java.util.UUID.randomUUID().toString().substring(0, 8) + "_" +
                fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
