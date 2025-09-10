package com.smartdocumentchat;

import dev.langchain4j.chain.ConversationalRetrievalChain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ConversationalRetrievalChain conversationalRetrievalChain;
    private final PdfProcessingService pdfProcessingService;
    private final QdrantVectorService qdrantVectorService;

    /**
     * העלאה ועיבוד קובץ PDF חדש
     */
    @PostMapping("/upload-pdf")
    public ResponseEntity<?> uploadPdf(@RequestParam("file") MultipartFile file) {
        try {
            String fileId = pdfProcessingService.processPdfFile(file);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "הקובץ הועלה ועובד בהצלחה",
                    "fileId", fileId,
                    "fileName", file.getOriginalFilename(),
                    "uploadTime", pdfProcessingService.getCurrentUploadTime()
            ));

        } catch (IllegalArgumentException e) {
            log.warn("שגיאה בתקינות הקובץ: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (IOException e) {
            log.error("שגיאה בעיבוד הקובץ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעיבוד הקובץ"
            ));

        } catch (Exception e) {
            log.error("שגיאה כללית בהעלאת הקובץ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה לא צפויה בהעלאת הקובץ"
            ));
        }
    }

    /**
     * שאילת שאלה על הקובץ הנוכחי - עם עיבוד חכם של השאלה
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chatWithPdf(@RequestBody ChatRequest request) {
        try {
            if (request.getText() == null || request.getText().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "השאלה לא יכולה להיות ריקה"
                ));
            }

            String activeFileId = pdfProcessingService.getCurrentActiveFileId();
            if (activeFileId == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "לא הועלה קובץ עדיין. אנא העלה קובץ PDF תחילה"
                ));
            }

            // עיבוד השאלה לשיפור הדיוק
            String enhancedQuestion = enhanceQuestion(request.getText(), pdfProcessingService.getCurrentActiveFileName());

            // שימוש ב-ConversationalRetrievalChain עם השאלה המשופרת
            String answer = conversationalRetrievalChain.execute(enhancedQuestion);
            log.debug("Enhanced question: {} | Answer for file {} - {}", enhancedQuestion, activeFileId, answer);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer,
                    "originalQuestion", request.getText(),
                    "enhancedQuestion", enhancedQuestion,
                    "activeFileId", activeFileId,
                    "activeFileName", pdfProcessingService.getCurrentActiveFileName()
            ));

        } catch (Exception e) {
            log.error("שגיאה בביצוע שיחה", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעיבוד השאלה: " + e.getMessage()
            ));
        }
    }

    /**
     * שיפור השאלה לדיוק טוב יותר
     */
    private String enhanceQuestion(String originalQuestion, String fileName) {
        // הוספת הקשר למסמך הספציפי
        String enhancedQuestion = String.format(
                "בהתבסס על המסמך '%s' שהועלה למערכת, ענה על השאלה הבאה: %s\n\n" +
                        "חשוב: ענה רק על בסיס המידע שמופיע במסמך הזה בלבד.",
                fileName, originalQuestion
        );

        // שיפורים ספציפיים לסוגי שאלות נפוצות
        String lowerQuestion = originalQuestion.toLowerCase();

        if (lowerQuestion.contains("שם") || lowerQuestion.contains("name")) {
            enhancedQuestion += "\n\nחפש שמות של אנשים, חברות, פרויקטים או טכנולוגיות המוזכרים במסמך.";
        } else if (lowerQuestion.contains("שפ") || lowerQuestion.contains("language")) {
            enhancedQuestion += "\n\nחפש מידע על שפות תכנות, שפות דיבור או כל שפה אחרת המוזכרת במסמך.";
        } else if (lowerQuestion.contains("פרויקט") || lowerQuestion.contains("project")) {
            enhancedQuestion += "\n\nחפש מידע על פרויקטים, עבודות או התפתחות מקצועית המוזכרת במסמך.";
        } else if (lowerQuestion.contains("טכנולוגי") || lowerQuestion.contains("technolog")) {
            enhancedQuestion += "\n\nחפש מידע על טכנולוגיות, כלים או מיומנויות טכניות המוזכרות במסמך.";
        } else if (lowerQuestion.contains("ניסיון") || lowerQuestion.contains("experience")) {
            enhancedQuestion += "\n\nחפש מידע על ניסיון מקצועי, עבודות קודמות או הישגים המוזכרים במסמך.";
        }

        return enhancedQuestion;
    }

    /**
     * קבלת מידע על הקובץ הנוכחי
     */
    @GetMapping("/current-file")
    public ResponseEntity<?> getCurrentFile() {
        String activeFileId = pdfProcessingService.getCurrentActiveFileId();
        if (activeFileId == null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "currentFile", null,
                    "message", "אין קובץ במערכת כרגע"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "fileInfo", pdfProcessingService.getCurrentFileInfo()
        ));
    }

    /**
     * קבלת רשימת הקבצים
     */
    @GetMapping("/files")
    public ResponseEntity<?> getUploadedFiles() {
        Map<String, String> files = pdfProcessingService.getUploadedFiles();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "files", files,
                "totalFiles", files.size(),
                "currentFileInfo", pdfProcessingService.getCurrentFileInfo()
        ));
    }

    /**
     * מחיקת הקובץ הנוכחי
     */
    @DeleteMapping("/current-file")
    public ResponseEntity<?> deleteCurrentFile() {
        try {
            boolean deleted = pdfProcessingService.deleteCurrentFile();
            if (deleted) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "הקובץ הנוכחי נמחק בהצלחה"
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "אין קובץ למחיקה"
                ));
            }
        } catch (Exception e) {
            log.error("שגיאה במחיקת קובץ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה במחיקת הקובץ"
            ));
        }
    }

    /**
     * בדיקת סטטוס המערכת עם פרטים על הביצועים
     */
    @GetMapping("/status")
    public ResponseEntity<?> getSystemStatus() {
        try {
            Map<String, Object> response = Map.of(
                    "success", true,
                    "systemStatus", "active",
                    "hasActiveFile", pdfProcessingService.getCurrentActiveFileId() != null,
                    "activeFileName", pdfProcessingService.getCurrentActiveFileName() != null ?
                            pdfProcessingService.getCurrentActiveFileName() : "אין קובץ",
                    "uploadTime", pdfProcessingService.getCurrentUploadTime() != null ?
                            pdfProcessingService.getCurrentUploadTime() : "לא הועלה קובץ",
                    "qdrantCollection", qdrantVectorService.getCurrentCollectionName(),
                    "chunkingSettings", Map.of(
                            "chunkSize", 1200,
                            "chunkOverlap", 200,
                            "maxResults", 5
                    )
            );
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("שגיאה בקבלת סטטוס המערכת", e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת סטטוס המערכת: " + e.getMessage()
            ));
        }
    }

    /**
     * מחלקה פנימית לבקשת צ'אט
     */
    public static class ChatRequest {
        private String text;

        public ChatRequest() {}

        public ChatRequest(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}