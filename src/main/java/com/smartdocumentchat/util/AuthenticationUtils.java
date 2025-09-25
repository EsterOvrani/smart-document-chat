package com.smartdocumentchat.util;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.CustomUserDetailsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * כלים עזר לעבודה עם authentication ב-Spring Security
 */
@Slf4j
public class AuthenticationUtils {

    /**
     * קבלת המשתמש הנוכחי מה-Security Context
     */
    public static Optional<User> getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated() ||
                    "anonymousUser".equals(authentication.getPrincipal().toString())) {
                return Optional.empty();
            }

            if (authentication.getPrincipal() instanceof CustomUserDetailsService.CustomUserPrincipal) {
                CustomUserDetailsService.CustomUserPrincipal userPrincipal =
                        (CustomUserDetailsService.CustomUserPrincipal) authentication.getPrincipal();
                return Optional.of(userPrincipal.getUser());
            }

            return Optional.empty();

        } catch (Exception e) {
            log.error("שגיאה בקבלת משתמש נוכחי", e);
            return Optional.empty();
        }
    }

    /**
     * קבלת ID של המשתמש הנוכחי
     */
    public static Optional<Long> getCurrentUserId() {
        return getCurrentUser().map(User::getId);
    }

    /**
     * קבלת שם המשתמש הנוכחי
     */
    public static Optional<String> getCurrentUsername() {
        return getCurrentUser().map(User::getUsername);
    }

    /**
     * בדיקה אם יש משתמש מחובר
     */
    public static boolean isAuthenticated() {
        return getCurrentUser().isPresent();
    }

    /**
     * בדיקה אם המשתמש הנוכחי הוא בעל הID הנתון
     */
    public static boolean isCurrentUser(Long userId) {
        if (userId == null) {
            return false;
        }

        return getCurrentUserId()
                .map(currentId -> currentId.equals(userId))
                .orElse(false);
    }

    /**
     * בדיקה אם המשתמש הנוכחי הוא בעל שם המשתמש הנתון
     */
    public static boolean isCurrentUser(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }

        return getCurrentUsername()
                .map(currentUsername -> currentUsername.equalsIgnoreCase(username.trim()))
                .orElse(false);
    }

    /**
     * בדיקה אם המשתמש הנוכחי זהה למשתמש הנתון
     */
    public static boolean isCurrentUser(User user) {
        if (user == null) {
            return false;
        }

        return isCurrentUser(user.getId());
    }
}