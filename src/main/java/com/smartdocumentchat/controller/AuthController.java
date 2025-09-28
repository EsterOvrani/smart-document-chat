package com.smartdocumentchat.controller;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.CustomUserDetailsService;
import com.smartdocumentchat.service.UserService;
import com.smartdocumentchat.util.AuthenticationUtils;
import com.smartdocumentchat.util.JwtUtil;
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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    /**
     * רענון access token באמצעות refresh token - גרסה משופרת
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        try {
            String refreshToken = request.getRefreshToken();

            if (refreshToken == null || refreshToken.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Refresh token חסר"
                ));
            }

            // שימוש בפונקציה החדשה של JwtUtil
            JwtUtil.RefreshResult result = jwtUtil.refreshAccessToken(refreshToken);

            if (!result.success) {
                log.warn("Refresh token validation failed: {}", result.error);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "success", false,
                        "error", result.error
                ));
            }

            // בדיקה שהמשתמש עדיין קיים ופעיל
            String username = jwtUtil.extractUsername(result.accessToken);
            Optional<User> userOpt = userService.findByUsername(username);

            if (userOpt.isEmpty() || !userOpt.get().getActive()) {
                // ביטול ה-refresh token אם המשתמש לא קיים/פעיל
                jwtUtil.revokeRefreshToken(refreshToken);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "success", false,
                        "error", "משתמש לא קיים או לא פעיל"
                ));
            }

            User user = userOpt.get();

            log.info("Access token refreshed successfully for user: {}", username);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Token רוענן בהצלחה",
                    "accessToken", result.accessToken,
                    "refreshToken", result.refreshToken, // אותו refresh token
                    "tokenType", "Bearer",
                    "expiresIn", jwtUtil.getTokenInfo(result.accessToken).get("expiration"),
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "email", user.getEmail(),
                            "fullName", user.getFullName()
                    )
            ));

        } catch (Exception e) {
            log.error("שגיאה ברענון token", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה ברענון הtoken"
            ));
        }
    }

    /**
     * ביטול refresh token
     */
    @PostMapping("/revoke")
    public ResponseEntity<?> revokeRefreshToken(@RequestBody RefreshTokenRequest request) {
        try {
            String refreshToken = request.getRefreshToken();

            if (refreshToken == null || refreshToken.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "error", "Refresh token חסר"
                ));
            }

            // ביטול הtoken
            jwtUtil.revokeRefreshToken(refreshToken);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Refresh token בוטל בהצלחה"
            ));

        } catch (Exception e) {
            log.error("שגיאה בביטול refresh token", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בביטול הtoken"
            ));
        }
    }

    /**
     * ביטול כל ה-refresh tokens של המשתמש הנוכחי
     */
    @PostMapping("/revoke-all")
    public ResponseEntity<?> revokeAllRefreshTokens() {
        try {
            Optional<User> currentUserOpt = AuthenticationUtils.getCurrentUser();

            if (currentUserOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "success", false,
                        "error", "משתמש לא מחובר"
                ));
            }

            User currentUser = currentUserOpt.get();

            // ביטול כל ה-refresh tokens של המשתמש
            jwtUtil.revokeAllUserRefreshTokens(currentUser.getUsername());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "כל ה-refresh tokens בוטלו בהצלחה"
            ));

        } catch (Exception e) {
            log.error("שגיאה בביטול כל ה-refresh tokens", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", "שגיאה בביטול הtokens"
            ));
        }
    }

    /**
     * התנתקות משתמש - עדכן לביטול refresh token
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response,
                                    @RequestBody(required = false) LogoutRequest logoutRequest) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String username = "unknown";

            if (auth != null && auth.getPrincipal() instanceof CustomUserDetailsService.CustomUserPrincipal) {
                CustomUserDetailsService.CustomUserPrincipal userPrincipal =
                        (CustomUserDetailsService.CustomUserPrincipal) auth.getPrincipal();
                username = userPrincipal.getUsername();
            }

            // ביטול refresh token אם סופק
            if (logoutRequest != null && logoutRequest.getRefreshToken() != null) {
                jwtUtil.revokeRefreshToken(logoutRequest.getRefreshToken());
                log.debug("Refresh token revoked during logout for user: {}", username);
            }

            // ביצוע logout באמצעות Spring Security
            if (auth != null) {
                new SecurityContextLogoutHandler().logout(request, response, auth);
            }

            // מחיקת JWT cookie
            Cookie jwtCookie = new Cookie("JWT_TOKEN", "");
            jwtCookie.setHttpOnly(true);
            jwtCookie.setMaxAge(0);
            jwtCookie.setPath("/");
            response.addCookie(jwtCookie);

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
     * רישום משתמש חדש - עדכן לJWT
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

            // יצירת JWT tokens למשתמש החדש
            String accessToken = jwtUtil.generateAccessToken(
                    newUser.getUsername(),
                    newUser.getId(),
                    newUser.getEmail()
            );

            String refreshToken = jwtUtil.generateRefreshToken(
                    newUser.getUsername(),
                    newUser.getId()
            );

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
                    ),
                    "accessToken", accessToken,
                    "refreshToken", refreshToken
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
     * התחברות משתמש - עדכן לJWT
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   HttpServletResponse response) {
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

            // קבלת המשתמש מה-authentication
            CustomUserDetailsService.CustomUserPrincipal userPrincipal =
                    (CustomUserDetailsService.CustomUserPrincipal) authentication.getPrincipal();
            User user = userPrincipal.getUser();

            // יצירת JWT tokens
            String accessToken = jwtUtil.generateAccessToken(
                    user.getUsername(),
                    user.getId(),
                    user.getEmail()
            );

            String refreshToken = jwtUtil.generateRefreshToken(
                    user.getUsername(),
                    user.getId()
            );

            // הוספת JWT לcookie (אופציונלי)
            Cookie jwtCookie = new Cookie("JWT_TOKEN", accessToken);
            jwtCookie.setHttpOnly(true);
            jwtCookie.setMaxAge(60 * 60); // 1 hour
            jwtCookie.setPath("/");
            response.addCookie(jwtCookie);

            log.info("משתמש {} התחבר בהצלחה באמצעות JWT", user.getUsername());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "התחברות בוצעה בהצלחה",
                    "user", Map.of(
                            "id", user.getId(),
                            "username", user.getUsername(),
                            "email", user.getEmail(),
                            "fullName", user.getFullName()
                    ),
                    "accessToken", accessToken,
                    "refreshToken", refreshToken,
                    "tokenType", "Bearer",
                    "expiresIn", jwtUtil.getTokenInfo(accessToken).get("expiration")
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
     * בדיקת סטטוס התחברות - עדכן לJWT
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
                        "authorities", auth.getAuthorities(),
                        "authType", "JWT"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "authenticated", true,
                    "username", auth.getName(),
                    "authorities", auth.getAuthorities(),
                    "authType", "JWT"
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
        public RegisterRequest() {
        }

        // Getters and setters
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }
    }

    public static class LoginRequest {
        private String username; // יכול להיות גם email
        private String password;

        // Constructors
        public LoginRequest() {
        }

        // Getters and setters
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class RefreshTokenRequest {
        private String refreshToken;

        public RefreshTokenRequest() {
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }
    }

    public static class LogoutRequest {
        private String refreshToken;

        public LogoutRequest() {}

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }
}