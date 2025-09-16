package com.smartdocumentchat.service;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    // Validation patterns
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    );

    private static final Pattern USERNAME_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9_]{3,20}$"
    );

    // Password requirements (basic for now)
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final int MAX_PASSWORD_LENGTH = 100;

    /**
     * יצירת משתמש חדש עם validation מלא
     */
    public User createUser(String username, String email, String firstName, String lastName) {
        return createUserWithPassword(username, email, firstName, lastName, "defaultPassword123");
    }

    /**
     * יצירת משתמש חדש עם סיסמה
     */
    public User createUserWithPassword(String username, String email, String firstName,
                                       String lastName, String password) {
        log.info("מתחיל יצירת משתמש חדש: {}", username);

        // Validation
        validateUserInput(username, email, password);
        validateUserUniqueness(username, email);

        // Hash password
        String hashedPassword = hashPassword(password);

        // Create user entity
        User user = new User();
        user.setUsername(normalizeUsername(username));
        user.setEmail(normalizeEmail(email));
        user.setFirstName(normalizeDisplayName(firstName));
        user.setLastName(normalizeDisplayName(lastName));
        user.setPasswordHash(hashedPassword);
        user.setActive(true);

        User savedUser = userRepository.save(user);
        log.info("משתמש חדש נוצר בהצלחה: {} (ID: {})", savedUser.getUsername(), savedUser.getId());
        return savedUser;
    }

    /**
     * מציאת משתמש לפי ID עם validation
     */
    public Optional<User> findById(Long userId) {
        if (userId == null || userId <= 0) {
            log.warn("ניסיון לחפש משתמש עם ID לא תקין: {}", userId);
            return Optional.empty();
        }

        return userRepository.findById(userId);
    }

    /**
     * מציאת משתמש לפי שם משתמש עם validation
     */
    public Optional<User> findByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            log.warn("ניסיון לחפש משתמש עם שם משתמש ריק");
            return Optional.empty();
        }

        return userRepository.findByUsername(normalizeUsername(username));
    }

    /**
     * מציאת משתמש לפי אימייל
     */
    public Optional<User> findByEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            log.warn("ניסיון לחפש משתמש עם אימייל ריק");
            return Optional.empty();
        }

        return userRepository.findByEmail(normalizeEmail(email));
    }

    /**
     * יצירת משתמש דמה לבדיקות (משופר)
     */
    public User getOrCreateDemoUser() {
        Optional<User> existingUser = userRepository.findByUsername("demo_user");

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (!user.getActive()) {
                log.info("מפעיל מחדש משתמש דמו שהיה מושבת");
                user.setActive(true);
                return userRepository.save(user);
            }
            return user;
        }

        log.info("יוצר משתמש דמו חדש");
        return createUserWithPassword("demo_user", "demo@example.com", "Demo", "User", "demo123");
    }

    /**
     * עדכון פרטי משתמש עם validation
     */
    public User updateUser(Long userId, String firstName, String lastName) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("מזהה משתמש לא תקין");
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("משתמש לא נמצא");
        }

        User user = userOpt.get();

        if (!user.getActive()) {
            throw new IllegalArgumentException("לא ניתן לעדכן משתמש לא פעיל");
        }

        // Validate and normalize names
        user.setFirstName(normalizeDisplayName(firstName));
        user.setLastName(normalizeDisplayName(lastName));

        User updatedUser = userRepository.save(user);
        log.info("פרטי משתמש {} עודכנו בהצלחה", user.getUsername());
        return updatedUser;
    }

    /**
     * עדכון אימייל משתמש
     */
    public User updateEmail(Long userId, String newEmail) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("מזהה משתמש לא תקין");
        }

        validateEmail(newEmail);
        String normalizedEmail = normalizeEmail(newEmail);

        // Check if email is already in use by another user
        Optional<User> existingUser = userRepository.findByEmail(normalizedEmail);
        if (existingUser.isPresent() && !existingUser.get().getId().equals(userId)) {
            throw new IllegalArgumentException("האימייל כבר בשימוש");
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("משתמש לא נמצא");
        }

        User user = userOpt.get();
        user.setEmail(normalizedEmail);

        User updatedUser = userRepository.save(user);
        log.info("אימייל של משתמש {} עודכן ל: {}", user.getUsername(), normalizedEmail);
        return updatedUser;
    }

    /**
     * שינוי סיסמה (בסיסי)
     */
    public boolean changePassword(Long userId, String oldPassword, String newPassword) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("מזהה משתמש לא תקין");
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("משתמש לא נמצא");
        }

        User user = userOpt.get();

        // Verify old password (basic check for now)
        if (!verifyPassword(oldPassword, user.getPasswordHash())) {
            log.warn("ניסיון שינוי סיסמה עם סיסמה ישנה שגויה למשתמש: {}", user.getUsername());
            return false;
        }

        // Validate new password
        validatePassword(newPassword);

        // Hash new password
        String newHashedPassword = hashPassword(newPassword);
        user.setPasswordHash(newHashedPassword);

        userRepository.save(user);
        log.info("סיסמה של משתמש {} שונתה בהצלחה", user.getUsername());
        return true;
    }

    /**
     * השבתת משתמש (soft delete) עם validation
     */
    public boolean deactivateUser(Long userId) {
        if (userId == null || userId <= 0) {
            log.warn("ניסיון השבתת משתמש עם ID לא תקין: {}", userId);
            return false;
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();

            if (!user.getActive()) {
                log.info("משתמש {} כבר מושבת", user.getUsername());
                return true;
            }

            user.setActive(false);
            userRepository.save(user);
            log.info("משתמש {} הושבת בהצלחה", user.getUsername());
            return true;
        }

        log.warn("ניסיון השבתת משתמש שלא קיים: {}", userId);
        return false;
    }

    /**
     * הפעלת משתמש מחדש
     */
    public boolean reactivateUser(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setActive(true);
            userRepository.save(user);
            log.info("משתמש {} הופעל מחדש", user.getUsername());
            return true;
        }
        return false;
    }

    /**
     * בדיקת זמינות שם משתמש
     */
    public boolean isUsernameAvailable(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }

        try {
            validateUsername(username);
            return !userRepository.existsByUsername(normalizeUsername(username));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * בדיקת זמינות אימייל
     */
    public boolean isEmailAvailable(String email) {
        if (email == null || email.trim().isEmpty()) {
            return false;
        }

        try {
            validateEmail(email);
            return !userRepository.existsByEmail(normalizeEmail(email));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Private validation methods

    private void validateUserInput(String username, String email, String password) {
        validateUsername(username);
        validateEmail(email);
        validatePassword(password);
    }

    private void validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("שם משתמש הוא שדה חובה");
        }

        String normalizedUsername = normalizeUsername(username);

        if (!USERNAME_PATTERN.matcher(normalizedUsername).matches()) {
            throw new IllegalArgumentException(
                    "שם משתמש חייב להכיל 3-20 תווים: אותיות אנגליות, מספרים ו-_ בלבד"
            );
        }
    }

    private void validateEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("אימייל הוא שדה חובה");
        }

        String normalizedEmail = normalizeEmail(email);

        if (!EMAIL_PATTERN.matcher(normalizedEmail).matches()) {
            throw new IllegalArgumentException("פורמט האימייל לא תקין");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("סיסמה היא שדה חובה");
        }

        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("סיסמה חייבת להכיל לפחות " + MIN_PASSWORD_LENGTH + " תווים");
        }

        if (password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("סיסמה ארוכה מדי (מקסימום " + MAX_PASSWORD_LENGTH + " תווים)");
        }
    }

    private void validateUserUniqueness(String username, String email) {
        String normalizedUsername = normalizeUsername(username);
        String normalizedEmail = normalizeEmail(email);

        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new IllegalArgumentException("שם משתמש כבר קיים: " + normalizedUsername);
        }

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new IllegalArgumentException("אימייל כבר קיים: " + normalizedEmail);
        }
    }

    // Private normalization methods

    private String normalizeUsername(String username) {
        return username != null ? username.trim().toLowerCase() : null;
    }

    private String normalizeEmail(String email) {
        return email != null ? email.trim().toLowerCase() : null;
    }

    private String normalizeDisplayName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }

        return name.trim()
                .replaceAll("\\s+", " ") // Replace multiple spaces with single space
                .substring(0, Math.min(name.trim().length(), 50)); // Limit length
    }

    // Basic password handling (will be enhanced in Phase 9)

    private String hashPassword(String password) {
        try {
            // Generate salt
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            random.nextBytes(salt);

            // Hash password with salt
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] hashedPassword = md.digest(password.getBytes());

            // Combine salt and hash
            byte[] combined = new byte[salt.length + hashedPassword.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(hashedPassword, 0, combined, salt.length, hashedPassword.length);

            return Base64.getEncoder().encodeToString(combined);

        } catch (NoSuchAlgorithmException e) {
            log.error("שגיאה בhashing של סיסמה", e);
            throw new RuntimeException("שגיאה פנימית בעיבוד הסיסמה");
        }
    }

    private boolean verifyPassword(String password, String hashedPassword) {
        try {
            byte[] combined = Base64.getDecoder().decode(hashedPassword);

            // Extract salt (first 16 bytes)
            byte[] salt = new byte[16];
            System.arraycopy(combined, 0, salt, 0, 16);

            // Extract hash (remaining bytes)
            byte[] storedHash = new byte[combined.length - 16];
            System.arraycopy(combined, 16, storedHash, 0, storedHash.length);

            // Hash provided password with stored salt
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] computedHash = md.digest(password.getBytes());

            // Compare hashes
            return MessageDigest.isEqual(storedHash, computedHash);

        } catch (Exception e) {
            log.error("שגיאה באימות סיסמה", e);
            return false;
        }
    }
}