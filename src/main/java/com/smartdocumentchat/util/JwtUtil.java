package com.smartdocumentchat.util;

import com.smartdocumentchat.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtUtil {

    private final JwtProperties jwtProperties;

    // רשימת refresh tokens פעילים (בסביבת production נשמור ב-Redis)
    private final Set<String> activeRefreshTokens = ConcurrentHashMap.newKeySet();

    /**
     * יצירת secret key מה-string שבהגדרות
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * יצירת access token למשתמש
     */
    public String generateAccessToken(String username, Long userId, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("email", email);
        claims.put("tokenType", "access");

        return createToken(claims, username, jwtProperties.getAccessTokenExpirationMillis());
    }

    /**
     * יצירת refresh token למשתמש
     */
    public String generateRefreshToken(String username, Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("tokenType", "refresh");
        claims.put("jti", generateTokenId()); // JWT ID for tracking

        String refreshToken = createToken(claims, username, jwtProperties.getRefreshTokenExpirationMillis());

        // הוספה לרשימת tokens פעילים
        activeRefreshTokens.add(refreshToken);

        log.debug("Generated refresh token for user: {}", username);
        return refreshToken;
    }

    /**
     * רענון access token באמצעות refresh token
     */
    public RefreshResult refreshAccessToken(String refreshToken) {
        try {
            // בדיקת תקינות refresh token
            if (!isValidRefreshToken(refreshToken)) {
                return RefreshResult.error("Refresh token is invalid or expired");
            }

            // חילוץ פרטי משתמש
            String username = extractUsername(refreshToken);
            Long userId = extractUserId(refreshToken);

            if (username == null || userId == null) {
                return RefreshResult.error("Invalid token data");
            }

            // יצירת access token חדש
            String newAccessToken = generateAccessToken(username, userId, extractEmail(refreshToken));

            log.info("Access token refreshed successfully for user: {}", username);
            return RefreshResult.success(newAccessToken, refreshToken);

        } catch (ExpiredJwtException e) {
            log.debug("Refresh token expired: {}", e.getMessage());
            return RefreshResult.error("Refresh token expired");
        } catch (Exception e) {
            log.error("Error refreshing access token", e);
            return RefreshResult.error("Token refresh failed");
        }
    }

    /**
     * בדיקת תקינות refresh token
     */
    public boolean isValidRefreshToken(String refreshToken) {
        try {
            // בדיקה שזה refresh token
            if (!isRefreshToken(refreshToken)) {
                log.debug("Token is not a refresh token");
                return false;
            }

            // בדיקת פקיעה
            if (isTokenExpired(refreshToken)) {
                log.debug("Refresh token is expired");
                revokeRefreshToken(refreshToken);
                return false;
            }

            // בדיקה ברשימת tokens פעילים
            if (!activeRefreshTokens.contains(refreshToken)) {
                log.debug("Refresh token not in active list");
                return false;
            }

            return true;

        } catch (Exception e) {
            log.debug("Refresh token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * ביטול refresh token
     */
    public void revokeRefreshToken(String refreshToken) {
        if (refreshToken != null) {
            activeRefreshTokens.remove(refreshToken);
            log.debug("Refresh token revoked");
        }
    }

    /**
     * ביטול כל ה-refresh tokens של משתמש
     */
    public void revokeAllUserRefreshTokens(String username) {
        activeRefreshTokens.removeIf(token -> {
            try {
                String tokenUsername = extractUsername(token);
                return username.equals(tokenUsername);
            } catch (Exception e) {
                return false;
            }
        });
        log.info("All refresh tokens revoked for user: {}", username);
    }

    /**
     * חילוץ email מ-token
     */
    public String extractEmail(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return (String) claims.get("email");
        } catch (Exception e) {
            log.warn("לא ניתן לחלץ email מהtoken: {}", e.getMessage());
            return null;
        }
    }

    /**
     * יצירת token עם claims ספציפיים
     */
    private String createToken(Map<String, Object> claims, String subject, long expirationTimeMillis) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationTimeMillis);

        try {
            return Jwts.builder()
                    .setClaims(claims)
                    .setSubject(subject)
                    .setIssuedAt(now)
                    .setExpiration(expiryDate)
                    .setIssuer(jwtProperties.getIssuer())
                    .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                    .compact();

        } catch (Exception e) {
            log.error("שגיאה ביצירת JWT token עבור משתמש: {}", subject, e);
            throw new RuntimeException("Failed to create JWT token", e);
        }
    }

    /**
     * יצירת מזהה ייחודי ל-token
     */
    private String generateTokenId() {
        return java.util.UUID.randomUUID().toString();
    }

    // הפונקציות הקיימות נשארות כפי שהן...

    /**
     * חילוץ username מה-token
     */
    public String extractUsername(String token) {
        try {
            return extractClaim(token, Claims::getSubject);
        } catch (Exception e) {
            log.warn("לא ניתן לחלץ username מהtoken: {}", e.getMessage());
            return null;
        }
    }

    /**
     * חילוץ user ID מה-token
     */
    public Long extractUserId(String token) {
        try {
            Claims claims = extractAllClaims(token);
            Object userIdObj = claims.get("userId");
            if (userIdObj instanceof Number) {
                return ((Number) userIdObj).longValue();
            }
            return null;
        } catch (Exception e) {
            log.warn("לא ניתן לחלץ userId מהtoken: {}", e.getMessage());
            return null;
        }
    }

    /**
     * חילוץ תאריך פקיעה מה-token
     */
    public Date extractExpiration(String token) {
        try {
            return extractClaim(token, Claims::getExpiration);
        } catch (Exception e) {
            log.warn("לא ניתן לחלץ expiration מהtoken: {}", e.getMessage());
            return null;
        }
    }

    /**
     * חילוץ claim ספציפי מהtoken
     */
    public <T> T extractClaim(String token, java.util.function.Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * חילוץ כל ה-claims מהtoken
     */
    private Claims extractAllClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("JWT token פג תוקף: {}", e.getMessage());
            throw e;
        } catch (MalformedJwtException e) {
            log.warn("JWT token לא תקין: {}", e.getMessage());
            throw e;
        } catch (UnsupportedJwtException e) {
            log.warn("JWT token לא נתמך: {}", e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("JWT token ריק או לא חוקי: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("שגיאה כללית בפרסור JWT token: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * בדיקה אם token פג תוקף
     */
    public Boolean isTokenExpired(String token) {
        try {
            final Date expiration = extractExpiration(token);
            return expiration != null && expiration.before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            log.warn("שגיאה בבדיקת פקיעת token: {}", e.getMessage());
            return true; // במקרה של שגיאה נחשיב שהtoken פג תוקף
        }
    }

    /**
     * בדיקה אם token תקף עבור משתמש ספציפי
     */
    public Boolean validateToken(String token, String username) {
        try {
            final String extractedUsername = extractUsername(token);
            return extractedUsername != null &&
                    extractedUsername.equals(username) &&
                    !isTokenExpired(token);
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * בדיקה אם token הוא refresh token
     */
    public Boolean isRefreshToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            String tokenType = (String) claims.get("tokenType");
            return "refresh".equals(tokenType);
        } catch (Exception e) {
            log.debug("לא ניתן לבדוק סוג token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * חילוץ token מ-header (הסרת prefix)
     */
    public String extractTokenFromHeader(String authorizationHeader) {
        if (authorizationHeader != null &&
                authorizationHeader.startsWith(jwtProperties.getHeaderPrefix())) {
            return authorizationHeader.substring(jwtProperties.getHeaderPrefix().length());
        }
        return null;
    }

    /**
     * יצירת authorization header מtoken
     */
    public String createAuthorizationHeader(String token) {
        return jwtProperties.getHeaderPrefix() + token;
    }

    /**
     * קבלת מידע על token (לdebug)
     */
    public Map<String, Object> getTokenInfo(String token) {
        try {
            Claims claims = extractAllClaims(token);
            Map<String, Object> info = new HashMap<>();
            info.put("subject", claims.getSubject());
            info.put("userId", claims.get("userId"));
            info.put("email", claims.get("email"));
            info.put("tokenType", claims.get("tokenType"));
            info.put("jti", claims.get("jti"));
            info.put("issuedAt", claims.getIssuedAt());
            info.put("expiration", claims.getExpiration());
            info.put("issuer", claims.getIssuer());
            info.put("isExpired", isTokenExpired(token));

            return info;
        } catch (Exception e) {
            log.warn("לא ניתן לקבל מידע על token: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * בדיקה בסיסית של token ללא validation מלא
     */
    public boolean isValidJwtFormat(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        // JWT token should have 3 parts separated by dots
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        try {
            // Try to decode the header and payload (without verification)
            // This is just a format check
            java.util.Base64.getDecoder().decode(parts[0]);
            java.util.Base64.getDecoder().decode(parts[1]);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ניקוי tokens שפגו (לתחזוקה)
     */
    public int cleanupExpiredTokens() {
        int removedCount = 0;
        activeRefreshTokens.removeIf(token -> {
            try {
                if (isTokenExpired(token)) {
                    return true;
                }
                return false;
            } catch (Exception e) {
                return true; // remove invalid tokens
            }
        });

        if (removedCount > 0) {
            log.info("Cleaned up {} expired refresh tokens", removedCount);
        }

        return removedCount;
    }

    // Inner class for refresh result
    public static class RefreshResult {
        public final boolean success;
        public final String accessToken;
        public final String refreshToken;
        public final String error;

        private RefreshResult(boolean success, String accessToken, String refreshToken, String error) {
            this.success = success;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.error = error;
        }

        public static RefreshResult success(String accessToken, String refreshToken) {
            return new RefreshResult(true, accessToken, refreshToken, null);
        }

        public static RefreshResult error(String error) {
            return new RefreshResult(false, null, null, error);
        }
    }
}