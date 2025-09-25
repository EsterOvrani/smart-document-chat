package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.CustomUserDetailsService;
import com.smartdocumentchat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;

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
     * התחברות משתמש - עדכן לשימוש ב-Spring Security
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

            // יצירת authentication token
            UsernamePasswordAuthenticationToken authRequest =
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername().trim(),
                            request.getPassword()
                    );

            // ביצוע authentication
            Authentication authentication = authenticationManager.authenticate(authRequest);

            // הגדרת authentication בcontext
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // קבלת המשתמש מה-authentication
            CustomUserDetailsService.CustomUserPrincipal userPrincipal =
                    (CustomUserDetailsService.CustomUserPrincipal) authentication.getPrincipal();
            User user = userPrincipal.getUser();

            // יצירת session
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());
            session.setMaxInactiveInterval(30 * 60); // 30 דקות

            log.info("משתמש {} התחבר בהצלחה באמצעות Spring Security", user.getUsername());

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

        } catch (BadCredentialsException e) {
            log.warn("ניסיון התחברות עם credentials שגויים: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "שם משתמש או סיסמה שגויים"
            ));

        } catch (UsernameNotFoundException e) {
            log.warn("ניסיון התחברות עם משתמש לא קיים: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", "שם משתמש או סיסמה שגויים"
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
     * התנתקות משתמש - עדכן לשימוש ב-Spring Security
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = "unknown";

            if (auth != null && auth.getPrincipal() instanceof CustomUserDetailsService.CustomUserPrincipal) {
                CustomUserDetailsService.CustomUserPrincipal userPrincipal =
                        (CustomUserDetailsService.CustomUserPrincipal) auth.getPrincipal();
                username = userPrincipal.getUsername();
            }

            // ביצוע logout באמצעות Spring Security
            if (auth != null) {
                new SecurityContextLogoutHandler().logout(request, response, auth);
            }

            // ניקוי session
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }

            log.info("משתמש {} התנתק בהצלחה", username);

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
     * בדיקת סטטוס התחברות - עדכן לשימוש ב-Spring Security
     */
    @GetMapping("/status")
    public ResponseEntity<?> getAuthStatus() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth == null || !auth.isAuthenticated() ||
                    "anonymousUser".equals(auth.getPrincipal().toString())) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "authenticated", false,
                        "message", "לא מחובר"
                ));
            }

            if (auth.getPrincipal() instanceof CustomUserDetailsService.CustomUserPrincipal) {
                CustomUserDetailsService.CustomUserPrincipal userPrincipal =
                        (CustomUserDetailsService.CustomUserPrincipal) auth.getPrincipal();
                User user = userPrincipal.getUser();

                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "authenticated", true,
                        "user", Map.of(
                                "id", user.getId(),
                                "username", user.getUsername(),
                                "email", user.getEmail(),
                                "fullName", user.getFullName()
                        ),
                        "authorities", auth.getAuthorities()
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "authenticated", true,
                    "username", auth.getName(),
                    "authorities", auth.getAuthorities()
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