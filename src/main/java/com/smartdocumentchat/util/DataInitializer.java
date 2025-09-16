package com.smartdocumentchat.util;

import com.smartdocumentchat.entity.ChatSession;
import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.UserRepository;
import com.smartdocumentchat.service.ChatSessionService;
import com.smartdocumentchat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final UserService userService;
    private final ChatSessionService chatSessionService;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        log.info("מתחיל אתחול נתונים בסיסיים...");

        initializeUsers();

        log.info("אתחול נתונים הושלם בהצלחה");
    }

    private void initializeUsers() {
        // יצירת משתמש דמו אם לא קיים
        createDemoUserIfNotExists();

        // יצירת משתמשים נוספים לדוגמה (אופציונלי)
        createSampleUsersIfNotExists();
    }

    private void createDemoUserIfNotExists() {
        Optional<User> existingUser = userRepository.findByUsername("demo_user");

        if (existingUser.isEmpty()) {
            log.info("יוצר משתמש דמו...");

            User demoUser = userService.createUser(
                    "demo_user",
                    "demo@smartdocumentchat.com",
                    "Demo",
                    "User"
            );

            // יצירת שיחה ראשונית
            ChatSession initialSession = chatSessionService.createSession(
                    demoUser,
                    "שיחה ראשונה",
                    "זוהי שיחה ראשונית שנוצרה אוטומטית"
            );

            log.info("משתמש דמו נוצר בהצלחה עם שיחה ראשונית: {}", initialSession.getId());
        } else {
            log.info("משתמש דמו כבר קיים: {}", existingUser.get().getUsername());
        }
    }

    private void createSampleUsersIfNotExists() {
        // משתמש מנהל לדוגמה
        createUserIfNotExists(
                "admin_user",
                "admin@smartdocumentchat.com",
                "Admin",
                "Administrator"
        );

        // משתמש בדיקה
        createUserIfNotExists(
                "test_user",
                "test@smartdocumentchat.com",
                "Test",
                "Tester"
        );
    }

    private void createUserIfNotExists(String username, String email, String firstName, String lastName) {
        Optional<User> existingUser = userRepository.findByUsername(username);

        if (existingUser.isEmpty()) {
            log.info("יוצר משתמש: {}", username);

            User user = userService.createUser(username, email, firstName, lastName);

            // יצירת שיחה ראשונית
            ChatSession session = chatSessionService.createSession(
                    user,
                    "שיחה ראשונה של " + firstName,
                    "שיחה ראשונית שנוצרה בזמן האתחול"
            );

            log.info("משתמש {} נוצר עם שיחה: {}", username, session.getId());
        }
    }
}