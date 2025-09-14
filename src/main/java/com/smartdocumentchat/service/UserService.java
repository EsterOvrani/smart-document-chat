package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    /**
     * יצירת משתמש חדש (בסיסי - ללא authentication)
     */
    public User createUser(String username, String email, String firstName, String lastName) {
        // בדיקה אם משתמש כבר קיים
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("שם משתמש כבר קיים: " + username);
        }

        if (userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("אימייל כבר קיים: " + email);
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(firstName);
        user.setLastName(lastName);
        user.setPasswordHash("temp_password"); // זמני - בשלב 9 נוסיף אבטחה אמיתית
        user.setActive(true);

        User savedUser = userRepository.save(user);
        log.info("משתמש חדש נוצר: {}", savedUser.getUsername());
        return savedUser;
    }

    /**
     * מציאת משתמש לפי ID
     */
    public Optional<User> findById(Long userId) {
        return userRepository.findById(userId);
    }

    /**
     * מציאת משתמש לפי שם משתמש
     */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * יצירת משתמש דמה לבדיקות (זמני)
     */
    public User getOrCreateDemoUser() {
        Optional<User> existingUser = userRepository.findByUsername("demo_user");

        if (existingUser.isPresent()) {
            return existingUser.get();
        }

        return createUser("demo_user", "demo@example.com", "Demo", "User");
    }

    /**
     * עדכון פרטי משתמש
     */
    public User updateUser(Long userId, String firstName, String lastName) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("משתמש לא נמצא");
        }

        User user = userOpt.get();
        user.setFirstName(firstName);
        user.setLastName(lastName);

        return userRepository.save(user);
    }

    /**
     * השבתת משתמש (soft delete)
     */
    public boolean deactivateUser(Long userId) {
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setActive(false);
            userRepository.save(user);
            log.info("משתמש {} הושבת", user.getUsername());
            return true;
        }
        return false;
    }
}