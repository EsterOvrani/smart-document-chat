package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;

    /**
     * יצירת שיחה חדשה
     */
    public ChatSession createSession(User user, String title, String description) {
        ChatSession session = new ChatSession();
        session.setUser(user);
        session.setTitle(title != null ? title : "שיחה חדשה");
        session.setDescription(description);
        session.setActive(true);
        session.setLastActivityAt(LocalDateTime.now());

        ChatSession savedSession = chatSessionRepository.save(session);
        log.info("שיחה חדשה נוצרה: {} עבור משתמש: {}", savedSession.getId(), user.getUsername());
        return savedSession;
    }

    /**
     * קבלת שיחה לפי ID
     */
    public Optional<ChatSession> findById(Long sessionId) {
        return chatSessionRepository.findById(sessionId);
    }

    /**
     * קבלת כל השיחות של משתמש
     */
    public List<ChatSession> getUserSessions(User user) {
        return chatSessionRepository.findByUserAndActiveTrueOrderByUpdatedAtDesc(user);
    }

    /**
     * קבלת השיחה האחרונה של משתמש
     */
    public Optional<ChatSession> getLastSession(User user) {
        return chatSessionRepository.findFirstByUserAndActiveTrueOrderByUpdatedAtDesc(user);
    }

    /**
     * יצירה או קבלה של שיחה ברירת מחדל למשתמש
     */
    public ChatSession getOrCreateDefaultSession(User user) {
        // נסה למצוא שיחה קיימת
        Optional<ChatSession> existingSession = getLastSession(user);

        if (existingSession.isPresent()) {
            ChatSession session = existingSession.get();
            // עדכן זמן פעילות אחרונה
            session.setLastActivityAt(LocalDateTime.now());
            return chatSessionRepository.save(session);
        }

        // צור שיחה חדשה
        return createSession(user, "שיחה ראשונה", "שיחת ברירת מחדל");
    }

    /**
     * עדכון פעילות אחרונה של שיחה
     */
    public void updateLastActivity(Long sessionId) {
        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(sessionId);
        if (sessionOpt.isPresent()) {
            ChatSession session = sessionOpt.get();
            session.setLastActivityAt(LocalDateTime.now());
            chatSessionRepository.save(session);
        }
    }

    /**
     * עדכון כותרת ותיאור שיחה
     */
    public ChatSession updateSession(Long sessionId, String title, String description, User user) {
        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(sessionId);

        if (sessionOpt.isEmpty()) {
            throw new IllegalArgumentException("שיחה לא נמצאה");
        }

        ChatSession session = sessionOpt.get();

        // בדיקת הרשאות
        if (!session.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("אין הרשאה לעדכן שיחה זו");
        }

        session.setTitle(title);
        session.setDescription(description);

        return chatSessionRepository.save(session);
    }

    /**
     * מחיקת שיחה (soft delete)
     */
    public boolean deleteSession(Long sessionId, User user) {
        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(sessionId);

        if (sessionOpt.isEmpty()) {
            return false;
        }

        ChatSession session = sessionOpt.get();

        // בדיקת הרשאות
        if (!session.getUser().getId().equals(user.getId())) {
            log.warn("משתמש {} מנסה למחוק שיחה {} של משתמש אחר",
                    user.getId(), sessionId);
            return false;
        }

        session.setActive(false);
        chatSessionRepository.save(session);

        log.info("שיחה {} נמחקה בהצלחה", sessionId);
        return true;
    }

    /**
     * ספירת שיחות פעילות של משתמש
     */
    public long countUserSessions(User user) {
        return chatSessionRepository.countByUserAndActiveTrue(user);
    }

    /**
     * בדיקה אם השיחה שייכת למשתמש
     */
    public boolean isSessionOwnedByUser(Long sessionId, User user) {
        Optional<ChatSession> sessionOpt = chatSessionRepository.findById(sessionId);
        return sessionOpt.isPresent() &&
                sessionOpt.get().getUser().getId().equals(user.getId()) &&
                sessionOpt.get().getActive();
    }

    /**
     * חיפוש שיחות לפי כותרת
     */
    public List<ChatSession> searchSessions(User user, String searchTerm) {
        return chatSessionRepository.findByUserAndTitleContaining(user, searchTerm);
    }
}