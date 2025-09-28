package com.smartdocumentchat.config;


import com.smartdocumentchat.service.CustomUserDetailsService;
import com.smartdocumentchat.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;
    private static final String REFRESH_TOKEN_HEADER = "X-Refresh-Token";


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        try {
            // בדיקה אם כבר יש authentication
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                filterChain.doFilter(request, response);
                return;
            }

            // חילוץ JWT token מהheader
            String jwt = extractJwtFromRequest(request);
            if (jwt == null) {
                filterChain.doFilter(request, response);
                return;
            }

            // בדיקת פורמט בסיסי של הtoken
            if (!jwtUtil.isValidJwtFormat(jwt)) {
                log.debug("Invalid JWT format");
                filterChain.doFilter(request, response);
                return;
            }

            // חילוץ username מהtoken
            String username = jwtUtil.extractUsername(jwt);
            if (username == null) {
                log.debug("Could not extract username from JWT");
                filterChain.doFilter(request, response);
                return;
            }

            // בדיקת תוקף הtoken
            if (jwtUtil.isTokenExpired(jwt)) {
                log.debug("JWT token is expired for user: {}", username);

                // ניסיון רענון אוטומטי אם יש refresh token
                String refreshToken = extractRefreshTokenFromRequest(request);
                if (refreshToken != null && attemptTokenRefresh(refreshToken, response)) {
                    log.debug("Token refreshed automatically for user: {}", username);
                    // נמשיך עם הtoken החדש שנשמר ב-response headers
                } else {
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // בדיקה שזה לא refresh token (access tokens בלבד)
            if (jwtUtil.isRefreshToken(jwt)) {
                log.debug("Refresh token provided instead of access token");
                filterChain.doFilter(request, response);
                return;
            }

            // טעינת פרטי המשתמש
            UserDetails userDetails;
            try {
                userDetails = userDetailsService.loadUserByUsername(username);
            } catch (UsernameNotFoundException e) {
                log.debug("User not found: {}", username);
                filterChain.doFilter(request, response);
                return;
            }

            // validation נוסף של הtoken עם פרטי המשתמש
            if (jwtUtil.validateToken(jwt, userDetails.getUsername())) {
                // יצירת authentication object
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                // הוספת פרטי הrequest
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // הגדרת authentication בcontext
                SecurityContextHolder.getContext().setAuthentication(authToken);

                // לוג הצלחה
                log.debug("User {} authenticated successfully via JWT", username);
            } else {
                log.debug("JWT token validation failed for user: {}", username);
            }

        } catch (Exception e) {
            log.error("Cannot set user authentication in JWT filter", e);
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    /**
     * חילוץ refresh token מהrequest
     */
    private String extractRefreshTokenFromRequest(HttpServletRequest request) {
        // נחפש בheader מיוחד
        String refreshToken = request.getHeader(REFRESH_TOKEN_HEADER);
        if (refreshToken != null) {
            return refreshToken;
        }

        // נחפש גם בcookie
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("REFRESH_TOKEN".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    /**
     * ניסיון רענון אוטומטי של token
     */
    private boolean attemptTokenRefresh(String refreshToken, HttpServletResponse response) {
        try {
            JwtUtil.RefreshResult result = jwtUtil.refreshAccessToken(refreshToken);

            if (result.success) {
                // הוספת הtoken החדש ל-response headers
                response.setHeader("X-New-Access-Token", result.accessToken);
                response.setHeader("X-Token-Refreshed", "true");

                log.debug("Token refreshed automatically");
                return true;
            }

        } catch (Exception e) {
            log.debug("Automatic token refresh failed: {}", e.getMessage());
        }

        return false;
    }


    /**
     * חילוץ JWT token מה-Authorization header
     */
    private String extractJwtFromRequest(HttpServletRequest request) {
        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader != null) {
            return jwtUtil.extractTokenFromHeader(authorizationHeader);
        }

        // חיפוש גם בcookie כ-fallback
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
     * קביעה אילו endpoints לא זקוקים לauthentication
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getServletPath();

        // Endpoints שלא דורשים authentication
        return path.startsWith("/api/auth/") ||
                path.equals("/api/status") ||
                path.startsWith("/actuator/") ||
                path.equals("/login") ||
                path.equals("/error") ||
                path.startsWith("/css/") ||
                path.startsWith("/js/") ||
                path.startsWith("/images/");
    }
}