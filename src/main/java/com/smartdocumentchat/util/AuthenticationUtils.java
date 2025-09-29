package com.smartdocumentchat.util;

import com.smartdocumentchat.entity.User;
import com.smartdocumentchat.service.CustomUserDetailsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * כלים עזר לעבודה עם authentication ב-Spring Security עם JWT
 */
@Slf4j
@Component
public class AuthenticationUtils {

    private final JwtUtil jwtUtil;

    public AuthenticationUtils(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

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
     * בדיקה אם המשתמש הנוכחי זהה למשתמש הנתון
     */
    public static boolean isCurrentUser(User user) {
        if (user == null) {
            return false;
        }

        return isCurrentUser(user.getId());
    }

    /**
     * קבלת JWT token מה-request הנוכחי
     */
    public Optional<String> getCurrentJwtToken() {
        try {
            ServletRequestAttributes requestAttributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (requestAttributes == null) {
                return Optional.empty();
            }

            HttpServletRequest request = requestAttributes.getRequest();
            return Optional.ofNullable(extractJwtFromRequest(request));

        } catch (Exception e) {
            log.error("שגיאה בקבלת JWT token נוכחי", e);
            return Optional.empty();
        }
    }

    /**
     * חילוץ JWT token מ-request
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        // מ-Authorization header
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader != null) {
            return jwtUtil.extractTokenFromHeader(authorizationHeader);
        }

        // מ-Cookie כ-fallback
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("JWT_TOKEN".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    /**
     * קבלת מידע על token הנוכחי
     */
    public Optional<java.util.Map<String, Object>> getCurrentTokenInfo() {
        return getCurrentJwtToken()
                .map(token -> jwtUtil.getTokenInfo(token));
    }

    /**
     * בדיקה אם token הנוכחי יפוג בקרוב (בחצי שעה הקרובה)
     */
    public boolean isTokenExpiringSoon() {
        return getCurrentJwtToken()
                .map(token -> {
                    try {
                        var tokenInfo = jwtUtil.getTokenInfo(token);
                        var expiration = (java.util.Date) tokenInfo.get("expiration");
                        if (expiration == null) return false;

                        long timeUntilExpiration = expiration.getTime() - System.currentTimeMillis();
                        return timeUntilExpiration < (30 * 60 * 1000); // 30 דקות
                    } catch (Exception e) {
                        log.warn("שגיאה בבדיקת זמן פקיעת token", e);
                        return true; // במקרה של ספק, נחשיב שהוא יפוג
                    }
                })
                .orElse(false);
    }

    /**
     * בדיקה אם המשתמש הנוכחי הוא admin (להרחבה עתידית)
     */
    public static boolean isCurrentUserAdmin() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()) {
                return false;
            }

            return authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));

        } catch (Exception e) {
            log.error("שגיאה בבדיקת הרשאות admin", e);
            return false;
        }
    }

    /**
     * קבלת רשימת תפקידים של המשתמש הנוכחי
     */
    public static java.util.List<String> getCurrentUserRoles() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()) {
                return java.util.Collections.emptyList();
            }

            return authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .collect(java.util.stream.Collectors.toList());

        } catch (Exception e) {
            log.error("שגיאה בקבלת תפקידי משתמש", e);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * בדיקת הרשאת admin למשתמש הנוכחי
     */
    public static boolean hasAdminRole() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication == null || !authentication.isAuthenticated()) {
                return false;
            }

            return authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));

        } catch (Exception e) {
            log.error("שגיאה בבדיקת הרשאות admin", e);
            return false;
        }
    }

    /**
     * בדיקה אם המשתמש הנוכחי יכול לגשת למשאב של משתמש אחר
     */
    public static boolean canAccessUserResource(Long userId) {
        return hasAdminRole() || isCurrentUser(userId);
    }

    /**
     * בדיקת הרשאה לפי Path Variable
     */
    public boolean isCurrentUser(String userIdStr) {
        try {
            Long userId = Long.parseLong(userIdStr);
            return isCurrentUser(userId);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}