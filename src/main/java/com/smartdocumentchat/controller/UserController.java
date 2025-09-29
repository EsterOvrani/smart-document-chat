package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.UserService;
import com.smartdocumentchat.util.AuthenticationUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@PreAuthorize("hasRole('ADMIN') or @authenticationUtils.isCurrentUser(#userId)")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;


    /**
     * רישום משתמש חדש - מועבר ל-AuthController
     */
    @Deprecated
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody UserRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .body(Map.of(
                        "success", false,
                        "error", "נא להשתמש ב-/api/auth/register",
                        "redirectTo", "/api/auth/register"
                ));
    }

    /**
     * קבלת פרטי משתמש לפי ID - עם בדיקת הרשאות
     */
    @GetMapping("/{userId}")
    @PreAuthorize("@authenticationUtils.canAccessUserResource(#userId)")
    public ResponseEntity<?> getUserById(@PathVariable Long userId) {
        try {
            // Additional security check
            if (!AuthenticationUtils.isCurrentUser(userId)) {
                log.warn("משתמש {} ניסה לגשת לפרטי משתמש {} אחר",
                        AuthenticationUtils.getCurrentUserId().orElse(-1L), userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לצפות בפרטי משתמש אחר"
                ));
            }

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
     * עדכון פרטי משתמש - עם בדיקת הרשאות
     */
    @PutMapping("/{userId}")
    @PreAuthorize("@authenticationUtils.canAccessUserResource(#userId)")
    public ResponseEntity<?> updateUser(@PathVariable Long userId, @RequestBody UserUpdateRequest request) {
        try {
            // Additional security check
            if (!AuthenticationUtils.isCurrentUser(userId)) {
                log.warn("משתמש {} ניסה לעדכן פרטי משתמש {} אחר",
                        AuthenticationUtils.getCurrentUserId().orElse(-1L), userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לעדכן פרטי משתמש אחר"
                ));
            }

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
     * קבלת פרטי המשתמש הנוכחי - משתמש ב-Spring Security
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentUser() {
        try {
            Optional<User> currentUserOpt = AuthenticationUtils.getCurrentUser();

            if (currentUserOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "success", false,
                        "error", "משתמש לא מחובר"
                ));
            }

            User currentUser = currentUserOpt.get();

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
     * השבתת משתמש - רק המשתמש עצמו יכול להשבית את עצמו
     */
    @DeleteMapping("/{userId}")
    @PreAuthorize("@authenticationUtils.isCurrentUser(#userId)")
    public ResponseEntity<?> deactivateUser(@PathVariable Long userId) {
        try {
            // Additional security check
            if (!AuthenticationUtils.isCurrentUser(userId)) {
                log.warn("משתמש {} ניסה להשבית משתמש {} אחר",
                        AuthenticationUtils.getCurrentUserId().orElse(-1L), userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה להשבית משתמש אחר"
                ));
            }

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
     * בדיקת זמינות שם משתמש - מוזז ל-AuthController
     */
    @Deprecated
    @GetMapping("/check-username/{username}")
    public ResponseEntity<?> checkUsernameAvailability(@PathVariable String username) {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .body(Map.of(
                        "success", false,
                        "error", "נא להשתמש ב-/api/auth/check-username/{username}",
                        "redirectTo", "/api/auth/check-username/" + username
                ));
    }

    /**
     * עדכון אימייל - עם בדיקת הרשאות
     */
    @PutMapping("/{userId}/email")
    @PreAuthorize("@authenticationUtils.isCurrentUser(#userId)")
    public ResponseEntity<?> updateEmail(
            @PathVariable Long userId,
            @RequestBody EmailUpdateRequest request) {
        try {
            // Additional security check
            if (!AuthenticationUtils.isCurrentUser(userId)) {
                log.warn("משתמש {} ניסה לעדכן אימייל של משתמש {} אחר",
                        AuthenticationUtils.getCurrentUserId().orElse(-1L), userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לעדכן אימייל של משתמש אחר"
                ));
            }

            if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "אימייל הוא שדה חובה"
                ));
            }

            User updatedUser = userService.updateEmail(userId, request.getEmail());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "אימייל עודכן בהצלחה",
                    "user", Map.of(
                            "id", updatedUser.getId(),
                            "username", updatedUser.getUsername(),
                            "email", updatedUser.getEmail()
                    )
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (Exception e) {
            log.error("שגיאה בעדכון אימייל למשתמש: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בעדכון האימייל"
            ));
        }
    }

    /**
     * שינוי סיסמה - עם בדיקת הרשאות
     */
    @PutMapping("/{userId}/password")
    @PreAuthorize("@authenticationUtils.isCurrentUser(#userId)")
    public ResponseEntity<?> changePassword(
            @PathVariable Long userId,
            @RequestBody PasswordChangeRequest request) {
        try {
            // Additional security check
            if (!AuthenticationUtils.isCurrentUser(userId)) {
                log.warn("משתמש {} ניסה לשנות סיסמה של משתמש {} אחר",
                        AuthenticationUtils.getCurrentUserId().orElse(-1L), userId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "error", "אין הרשאה לשנות סיסמה של משתמש אחר"
                ));
            }

            if (request.getOldPassword() == null || request.getNewPassword() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "סיסמה ישנה וחדשה הן שדות חובה"
                ));
            }

            boolean changed = userService.changePassword(
                    userId,
                    request.getOldPassword(),
                    request.getNewPassword()
            );

            if (!changed) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "סיסמה ישנה שגויה"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "סיסמה שונתה בהצלחה"
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));

        } catch (Exception e) {
            log.error("שגיאה בשינוי סיסמה למשתמש: {}", userId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בשינוי הסיסמה"
            ));
        }
    }

    // Request DTOs

    public static class UserRegistrationRequest {
        private String username;
        private String email;
        private String firstName;
        private String lastName;

        public UserRegistrationRequest() {}

        public UserRegistrationRequest(String username, String email, String firstName, String lastName) {
            this.username = username;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
        }

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

        public UserUpdateRequest() {}

        public UserUpdateRequest(String firstName, String lastName) {
            this.firstName = firstName;
            this.lastName = lastName;
        }

        public String getFirstName() { return firstName; }
        public void setFirstName(String firstName) { this.firstName = firstName; }

        public String getLastName() { return lastName; }
        public void setLastName(String lastName) { this.lastName = lastName; }
    }

    public static class EmailUpdateRequest {
        private String email;

        public EmailUpdateRequest() {}

        public EmailUpdateRequest(String email) {
            this.email = email;
        }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    public static class PasswordChangeRequest {
        private String oldPassword;
        private String newPassword;

        public PasswordChangeRequest() {}

        public PasswordChangeRequest(String oldPassword, String newPassword) {
            this.oldPassword = oldPassword;
            this.newPassword = newPassword;
        }

        public String getOldPassword() { return oldPassword; }
        public void setOldPassword(String oldPassword) { this.oldPassword = oldPassword; }

        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }
}
