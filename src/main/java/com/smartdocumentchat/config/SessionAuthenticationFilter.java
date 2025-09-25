//package com.smartdocumentchat.config;
//
//import com.smartdocumentchat.service.UserService;
//import jakarta.servlet.FilterChain;
//import jakarta.servlet.ServletException;
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import jakarta.servlet.http.HttpSession;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
//import org.springframework.stereotype.Component;
//import org.springframework.web.filter.OncePerRequestFilter;
//
//import java.io.IOException;
//import java.util.ArrayList;
//
//@Component
//@RequiredArgsConstructor
//@Slf4j
//public class SessionAuthenticationFilter extends OncePerRequestFilter {
//
//    private final UserService userService;
//
//    @Override
//    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
//                                    FilterChain filterChain) throws ServletException, IOException {
//
//        // דילוג על endpoints שלא דורשים authentication
//        String requestPath = request.getRequestURI();
//        if (requestPath.startsWith("/api/auth/") ||
//                requestPath.equals("/api/status") ||
//                requestPath.startsWith("/actuator/")) {
//            filterChain.doFilter(request, response);
//            return;
//        }
//
//        try {
//            HttpSession session = request.getSession(false);
//
//            if (session != null) {
//                Long userId = (Long) session.getAttribute("userId");
//                String username = (String) session.getAttribute("username");
//
//                if (userId != null && username != null) {
//                    // וידוא שהמשתמש עדיין קיים ופעיל
//                    var userOpt = userService.findById(userId);
//
//                    if (userOpt.isPresent() && userOpt.get().getActive() &&
//                            userOpt.get().getUsername().equals(username)) {
//
//                        // יצירת authentication token
//                        UsernamePasswordAuthenticationToken authToken =
//                                new UsernamePasswordAuthenticationToken(username, null, new ArrayList<>());
//
//                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
//
//                        // הגדרת authentication ב-SecurityContext
//                        SecurityContextHolder.getContext().setAuthentication(authToken);
//
//                        log.debug("משתמש {} אומת באמצעות session", username);
//                    } else {
//                        log.warn("Session לא תקין עבור משתמש: {} (ID: {})", username, userId);
//                        session.invalidate();
//                    }
//                }
//            }
//
//        } catch (Exception e) {
//            log.error("שגיאה ב-SessionAuthenticationFilter", e);
//            SecurityContextHolder.clearContext();
//        }
//
//        filterChain.doFilter(request, response);
//    }
//}