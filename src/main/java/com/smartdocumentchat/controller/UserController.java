package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    /**
     * רישום משתמש חדש
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody UserRegistrationRequest request) {
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

            // יצירת המשתמש
            User newUser = userService.createUser(
                    request.getUsername().trim(),
                    request.getEmail().trim(),
                    request.getFirstName() != null ? request.getFirstName().trim() : null,
                    request.getLastName() != null ? request.getLastName().trim() : null
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
     * קבלת פרטי משתמש לפי ID
     */
    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserById(@PathVariable Long userId) {
        try {
            Optional<User> userOpt = userService.findById(userId);

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "משתמש לא נמצא"
                ));
            }

            User user = userOpt.get();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "email", user.getEmail(),
                            "firstName", user.getFirstName(),
                            "lastName", user.getLastName(),
                            "fullName", user.getFullName(),
                            "active", user.getActive(),
                            "createdAt", user.getCreatedAt().format(
                                    java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                            ),
                            "chatSessionsCount", user.getChatSessionCount()
                    )
            ));

        } catch (Exception e) {
            log.error("שגיאה בקבלת פרטי משתמש: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת פרטי המשתמש"
            ));
        }
    }

    /**
     * עדכון פרטי משתמש
     */
    @PutMapping("/{userId}")
    public ResponseEntity<?> updateUser(
            @PathVariable Long userId,
            @RequestBody UserUpdateRequest request) {
        try {
            // כרגע נעדכן רק שם פרטי ושם משפחה (בשלב 9 נוסיף authentication)
            User updatedUser = userService.updateUser(
                    userId,
                    request.getFirstName(),
                    request.getLastName()
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "פרטי המשתמש עודכנו בהצלחה",
                    "user", Map.of(
                            "id", updatedUser.getId(),
                            "username", updatedUser.getUsername(),
                            "firstName", updatedUser.getFirstName(),
                            "lastName", updatedUser.getLastName(),
                            "fullName", updatedUser.getFullName()
                    )
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (Exception e) {
            log.error("שגיאה בעדכון פרטי משתמש: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון פרטי המשתמש"
            ));
        }
    }

    /**
     * קבלת פרטי המשתמש הנוכחי (דמו - בשלב 9 נוסיף authentication אמיתי)
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentUser() {
        try {
            // כרגע נחזיר את המשתמש הדמו
            User currentUser = userService.getOrCreateDemoUser();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "user", Map.of(
                            "id", currentUser.getId(),
                            "username", currentUser.getUsername(),
                            "email", currentUser.getEmail(),
                            "firstName", currentUser.getFirstName(),
                            "lastName", currentUser.getLastName(),
                            "fullName", currentUser.getFullName(),
                            "chatSessionsCount", currentUser.getChatSessionCount()
                    )
            ));

        } catch (Exception e) {
            log.error("שגיאה בקבלת פרטי המשתמש הנוכחי", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בקבלת פרטי המשתמש"
            ));
        }
    }

    /**
     * השבתת משתמש
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deactivateUser(@PathVariable Long userId) {
        try {
            boolean deactivated = userService.deactivateUser(userId);

            if (!deactivated) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "משתמש לא נמצא"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "משתמש הושבת בהצלחה"
            ));

        } catch (Exception e) {
            log.error("שגיאה בהשבתת משתמש: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בהשבתת המשתמש"
            ));
        }
    }

    /**
     * בדיקת זמינות שם משתמש
     */
    @GetMapping("/check-username/{username}")
    public ResponseEntity<?> checkUsernameAvailability(@PathVariable String username) {
        try {
            Optional<User> existingUser = userService.findByUsername(username);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "available", existingUser.isEmpty(),
                    "message", existingUser.isEmpty() ?
                            "שם המשתמש זמין" : "שם המשתמש כבר תפוס"
            ));

        } catch (Exception e) {
            log.error("שגיאה בבדיקת זמינות שם משתמש: {}", username, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בבדיקת זמינות שם המשתמש"
            ));
        }
    }

    // Inner classes for request DTOs

    public static class UserRegistrationRequest {
        private String username;
        private String email;
        private String firstName;
        private String lastName;

        // Constructors
        public UserRegistrationRequest() {}

        public UserRegistrationRequest(String username, String email, String firstName, String lastName) {
            this.username = username;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
        }

        // Getters and setters
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }

    public static class UserUpdateRequest {
        private String firstName;
        private String lastName;

        // Constructors
        public UserUpdateRequest() {}

        public UserUpdateRequest(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }

        // Getters and setters
        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }
}