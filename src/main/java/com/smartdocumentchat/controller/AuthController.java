package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserService userService;

    /**
     * רישום משתמש חדש
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            // בדיקת תקינות הנתונים
            if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שם משתמש הוא שדה חובה"
                ));
            }

            if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "אימייל הוא שדה חובה"
                ));
            }

            if (request.getPassword() == null || request.getPassword().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "סיסמה היא שדה חובה"
                ));
            }

            // יצירת המשתמש
            User newUser = userService.createUserWithPassword(
                    request.getUsername().trim(),
                    request.getEmail().trim(),
                    request.getFirstName() != null ? request.getFirstName().trim() : null,
                    request.getLastName() != null ? request.getLastName().trim() : null,
                    request.getPassword()
            );

            log.info("משתמש חדש נרשם: {}", newUser.getUsername());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "משתמש נרשם בהצלחה",
                    "user", Map.of(
                            "id", newUser.getId(),
                            "username", newUser.getUsername(),
                            "email", newUser.getEmail(),
                            "fullName", newUser.getFullName(),
                            "createdAt", newUser.getCreatedAt().format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                            )
                    )
            ));

        } catch (IllegalArgumentException e) {
            log.warn("שגיאה ברישום משתמש: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (Exception e) {
            log.error("שגיאה כללית ברישום משתמש", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה לא צפויה ברישום המשתמש"
            ));
        }
    }

    /**
     * התחברות משתמש
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        try {
            // בדיקת תקינות הנתונים
            if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שם משתמש או אימייל הוא שדה חובה"
                ));
            }

            if (request.getPassword() == null || request.getPassword().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "סיסמה היא שדה חובה"
                ));
            }

            // חיפוש משתמש לפי שם משתמש או אימייל
            Optional<User> userOpt = userService.findByUsername(request.getUsername());
            if (userOpt.isEmpty()) {
                userOpt = userService.findByEmail(request.getUsername());
            }

            if (userOpt.isEmpty()) {
                log.warn("ניסיון התחברות עם משתמש לא קיים: {}", request.getUsername());
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שם משתמש או סיסמה שגויים"
                ));
            }

            User user = userOpt.get();

            // בדיקה שהמשתמש פעיל
            if (!user.getActive()) {
                log.warn("ניסיון התחברות עם משתמש לא פעיל: {}", user.getUsername());
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "משתמש לא פעיל"
                ));
            }

            // אימות סיסמה
            if (!userService.verifyPassword(request.getPassword(), user.getPasswordHash())) {
                log.warn("ניסיון התחברות עם סיסמה שגויה למשתמש: {}", user.getUsername());
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "שם משתמש או סיסמה שגויים"
                ));
            }

            // יצירת session
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute("userId", user.getId());
            session.setAttribute("username", user.getUsername());
            session.setMaxInactiveInterval(30 * 60); // 30 דקות

            log.info("משתמש {} התחבר בהצלחה", user.getUsername());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "התחברות בוצעה בהצלחה",
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "email", user.getEmail(),
                            "fullName", user.getFullName(),
                            "sessionId", session.getId()
                    )
            ));

        } catch (Exception e) {
            log.error("שגיאה כללית בהתחברות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה לא צפויה בהתחברות"
            ));
        }
    }

    /**
     * התנתקות משתמש
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);

            if (session != null) {
                String username = (String) session.getAttribute("username");
                session.invalidate();
                log.info("משתמש {} התנתק בהצלחה", username);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "התנתקות בוצעה בהצלחה"
            ));

        } catch (Exception e) {
            log.error("שגיאה בהתנתקות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בהתנתקות"
            ));
        }
    }

    /**
     * בדיקת סטטוס התחברות
     */
    @GetMapping("/status")
    public ResponseEntity<?> getAuthStatus(HttpServletRequest request) {
        try {
            HttpSession session = request.getSession(false);

            if (session == null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "authenticated", false,
                        "message", "לא מחובר"
                ));
            }

            Long userId = (Long) session.getAttribute("userId");
            String username = (String) session.getAttribute("username");

            if (userId == null || username == null) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "authenticated", false,
                        "message", "session לא תקין"
                ));
            }

            // וידוא שהמשתמש עדיין קיים ופעיל
            Optional<User> userOpt = userService.findById(userId);
            if (userOpt.isEmpty() || !userOpt.get().getActive()) {
                session.invalidate();
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "authenticated", false,
                        "message", "משתמש לא קיים או לא פעיל"
                ));
            }

            User user = userOpt.get();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "authenticated", true,
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "email", user.getEmail(),
                            "fullName", user.getFullName()
                    ),
                    "sessionId", session.getId()
            ));

        } catch (Exception e) {
            log.error("שגיאה בבדיקת סטטוס התחברות", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בבדיקת סטטוס"
            ));
        }
    }

    /**
     * בדיקת זמינות שם משתמש
     */
    @GetMapping("/check-username/{username}")
    public ResponseEntity<?> checkUsernameAvailability(@PathVariable String username) {
        try {
            boolean available = userService.isUsernameAvailable(username);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "available", available,
                    "message", available ? "שם המשתמש זמין" : "שם המשתמש כבר תפוס"
            ));

        } catch (Exception e) {
            log.error("שגיאה בבדיקת זמינות שם משתמש: {}", username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בבדיקת זמינות שם המשתמש"
            ));
        }
    }

    /**
     * בדיקת זמינות אימייל
     */
    @GetMapping("/check-email/{email}")
    public ResponseEntity<?> checkEmailAvailability(@PathVariable String email) {
        try {
            boolean available = userService.isEmailAvailable(email);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "available", available,
                    "message", available ? "האימייל זמין" : "האימייל כבר בשימוש"
            ));

        } catch (Exception e) {
            log.error("שגיאה בבדיקת זמינות אימייל: {}", email, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בבדיקת זמינות האימייל"
            ));
        }
    }

    // Request DTOs

    public static class RegisterRequest {
        private String username;
        private String email;
        private String password;
        private String firstName;
        private String lastName;

        // Constructors
        public RegisterRequest() {}

        // Getters and setters
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }

    public static class LoginRequest {
        private String username; // יכול להיות גם email
        private String password;

        // Constructors
        public LoginRequest() {}

        // Getters and setters
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}