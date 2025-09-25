package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.Document;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.FileStorageService;
import com.smartdocumentchat.service.PdfProcessingService;
import com.smartdocumentchat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileManagementController {

    private final FileStorageService fileStorageService;
    private final PdfProcessingService pdfProcessingService;
    private final UserService userService;

    /**
     * הורדת קובץ באמצעות presigned URL
     */
    @GetMapping("/{documentId}/download")
    public ResponseEntity<?> downloadFile(
            @PathVariable Long documentId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // קבלת המסמך עם בדיקת הרשאות
            Optional<Document> documentOpt = pdfProcessingService.getDocumentById(documentId, currentUser);

            if (documentOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מסמך לא נמצא או אין הרשאה"
                ));
            }

            Document document = documentOpt.get();

            // בדיקה שהמסמך קיים ב-MinIO
            if (!fileStorageService.fileExists(document.getFileName())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "הקובץ לא נמצא באחסון"
                ));
            }

            // יצירת presigned URL להורדה
            String presignedUrl = fileStorageService.generatePresignedUrl(
                    document.getFileName(), Duration.ofMinutes(30));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "downloadUrl", presignedUrl,
                    "fileName", document.getOriginalFileName(),
                    "fileSize", document.getFileSizeFormatted(),
                    "expiresInMinutes", 30
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בהורדת קובץ: {}", documentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בהורדת הקובץ"
            ));
        }
    }

    /**
     * קבלת מידע על קובץ
     */
    @GetMapping("/{documentId}/info")
    public ResponseEntity<?> getFileInfo(
            @PathVariable Long documentId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            Optional<Document> documentOpt = pdfProcessingService.getDocumentById(documentId, currentUser);

            if (documentOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מסמך לא נמצא או אין הרשאה"
                ));
            }

            Document document = documentOpt.get();

            // קבלת metadata מ-MinIO
            Map<String, String> storageMetadata = fileStorageService.getFileMetadata(document.getFileName());
            long actualFileSize = fileStorageService.getFileSize(document.getFileName());
            boolean fileExists = fileStorageService.fileExists(document.getFileName());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileInfo", Map.ofEntries(
                            Map.entry("id", document.getId()),
                            Map.entry("originalFileName", document.getOriginalFileName()),
                            Map.entry("fileType", document.getFileType()),
                            Map.entry("fileSize", document.getFileSizeFormatted()),
                            Map.entry("actualFileSize", actualFileSize),
                            Map.entry("exists", fileExists),
                            Map.entry("processingStatus", document.getProcessingStatus()),
                            Map.entry("characterCount", document.getCharacterCount() != null ? document.getCharacterCount() : 0),
                            Map.entry("chunkCount", document.getChunkCount() != null ? document.getChunkCount() : 0),
                            Map.entry("uploadTime", document.getCreatedAt().format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))),
                            Map.entry("storageMetadata", storageMetadata)
                    )
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בקבלת מידע קובץ: {}", documentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת מידע הקובץ"
            ));
        }
    }

    /**
     * מחיקת קובץ מאחסון
     */
    @DeleteMapping("/{documentId}/storage")
    public ResponseEntity<?> deleteFileFromStorage(
            @PathVariable Long documentId,
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            Optional<Document> documentOpt = pdfProcessingService.getDocumentById(documentId, currentUser);

            if (documentOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "מסמך לא נמצא או אין הרשאה"
                ));
            }

            Document document = documentOpt.get();

            // מחיקה מ-MinIO
            boolean deleted = fileStorageService.deleteFile(document.getFileName());

            if (deleted) {
                log.info("קובץ {} נמחק מ-MinIO עבור משתמש {}",
                        document.getOriginalFileName(), currentUser.getUsername());

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "קובץ נמחק בהצלחה מהאחסון"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "לא ניתן למחוק את הקובץ מהאחסון"
                ));
            }

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה במחיקת קובץ: {}", documentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה במחיקת הקובץ"
            ));
        }
    }

    /**
     * בדיקת תקינות קבצים
     */
    @GetMapping("/validate")
    public ResponseEntity<?> validateUserFiles(
            @RequestParam(value = "userId", required = false) Long userId) {
        try {
            User currentUser = getCurrentUser(userId);

            // קבלת כל המסמכים של המשתמש
            var allDocuments = pdfProcessingService.getDocumentsByUser(currentUser);

            int totalFiles = allDocuments.size();
            int missingFiles = 0;
            int corruptFiles = 0;

            for (var document : allDocuments) {
                if (!fileStorageService.fileExists(document.getFileName())) {
                    missingFiles++;
                } else {
                    // בדיקה בסיסית של תקינות הקובץ
                    long actualSize = fileStorageService.getFileSize(document.getFileName());
                    if (actualSize != document.getFileSize()) {
                        corruptFiles++;
                    }
                }
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "validation", Map.of(
                            "totalFiles", totalFiles,
                            "validFiles", totalFiles - missingFiles - corruptFiles,
                            "missingFiles", missingFiles,
                            "corruptFiles", corruptFiles,
                            "healthScore", totalFiles > 0 ?
                                    (double)(totalFiles - missingFiles - corruptFiles) / totalFiles * 100 : 100
                    )
            ));

        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("שגיאה בבדיקת תקינות קבצים", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בבדיקת תקינות הקבצים"
            ));
        }
    }

    // Helper method
    private User getCurrentUser(Long userId) {
        if (userId != null && userId > 0) {
            Optional<User> userOpt = userService.findById(userId);
            if (userOpt.isPresent() && userOpt.get().getActive()) {
                return userOpt.get();
            } else {
                throw new SecurityException("משתמש לא נמצא או לא פעיל");
            }
        }

        return userService.getOrCreateDemoUser();
    }
}