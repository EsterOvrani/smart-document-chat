package com.smartdocumentchat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

@Service
@Slf4j
public class QuestionHashService {

    /**
     * יצירת hash עבור שאלה כדי לזהות שאלות זהות
     */
    public String generateQuestionHash(String question, List<String> documentIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // נוסיף את השאלה
            String normalizedQuestion = normalizeQuestion(question);
            digest.update(normalizedQuestion.getBytes(StandardCharsets.UTF_8));

            // נוסיף את רשימת המסמכים (ממוינת)
            documentIds.stream()
                    .sorted()
                    .forEach(docId -> digest.update(docId.getBytes(StandardCharsets.UTF_8)));

            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();

            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            String hash = hexString.toString().substring(0, 16); // קצר לחסכון
            log.debug("Generated question hash: {} for question: '{}'", hash,
                    normalizedQuestion.length() > 50 ?
                            normalizedQuestion.substring(0, 50) + "..." : normalizedQuestion);

            return hash;

        } catch (NoSuchAlgorithmException e) {
            log.error("Error generating question hash", e);
            // fallback - השתמש בhashCode פשוט
            return String.valueOf((question + String.join(",", documentIds)).hashCode());
        }
    }

    /**
     * נרמול השאלה - הסרת רווחים מיותרים, המרה לאותיות קטנות וכו'
     */
    private String normalizeQuestion(String question) {
        if (question == null) return "";

        return question.trim()
                .toLowerCase()
                .replaceAll("\\s+", " ") // החלפת רווחים מרובים ברווח אחד
                .replaceAll("[\\p{Punct}&&[^?]]", "") // הסרת סימני פיסוק חוץ מסימן שאלה
                .trim();
    }
}