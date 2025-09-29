package com.smartdocumentchat.config;

import com.smartdocumentchat.config.JwtAuthenticationFilter;
import com.smartdocumentchat.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.access.expression.WebExpressionAuthorizationManager;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        log.info("Creating BCryptPasswordEncoder with strength 10");
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder authBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);

        authBuilder
                .userDetailsService(userDetailsService)
                .passwordEncoder(passwordEncoder());

        return authBuilder.build();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("Configuring security filter chain with comprehensive JWT authentication and authorization");

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> {
                    configureEndpointAuthorization(authz);
                })
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hstsConfig -> hstsConfig
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true)
                        )
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        log.info("Security filter chain configured successfully with comprehensive authorization");
        return http.build();
    }

    /**
     * הגדרת הרשאות מפורטות לכל endpoints
     */
    private void configureEndpointAuthorization(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry authz) {
        authz
                // Public endpoints - אין צורך באימות
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/status").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/login", "/register", "/error").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()

                // Admin only endpoints - מנהלים בלבד
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // User management endpoints - רק המשתמש עצמו או מנהל
                .requestMatchers(HttpMethod.GET, "/api/users/{userId}")
                .access(new WebExpressionAuthorizationManager("hasRole('ADMIN') or @authenticationUtils.isCurrentUser(#userId)"))
                .requestMatchers(HttpMethod.PUT, "/api/users/{userId}")
                .access(new WebExpressionAuthorizationManager("hasRole('ADMIN') or @authenticationUtils.isCurrentUser(#userId)"))
                .requestMatchers(HttpMethod.PUT, "/api/users/{userId}/**")
                .access(new WebExpressionAuthorizationManager("hasRole('ADMIN') or @authenticationUtils.isCurrentUser(#userId)"))
                .requestMatchers(HttpMethod.DELETE, "/api/users/{userId}")
                .access(new WebExpressionAuthorizationManager("hasRole('ADMIN') or @authenticationUtils.isCurrentUser(#userId)"))
                .requestMatchers("/api/users/current").authenticated()

                // Session management - רק משתמשים מחוברים
                .requestMatchers("/api/sessions/**").authenticated()
                .requestMatchers("/api/session-switch/**").authenticated()
                .requestMatchers("/api/session-history/**").authenticated()

                // Document and file management - רק משתמשים מחוברים
                .requestMatchers("/api/files/**").authenticated()

                // User preferences - רק משתמשים מחוברים
                .requestMatchers("/api/preferences/**").authenticated()

                // Chat endpoints - רק משתמשים מחוברים
                .requestMatchers("/api/chat/**").authenticated()

                // Protected pages - דפים מוגנים
                .requestMatchers("/", "/home").authenticated()

                // All other API endpoints require authentication
                .requestMatchers("/api/**").authenticated()

                // All other requests
                .anyRequest().authenticated();
    }
    /**
     * הגדרת CORS מאובטח
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "https://localhost:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * Authentication Success Handler for form-based login (if needed)
     */
    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        return (request, response, authentication) -> {
            log.info("Authentication successful for user: {}", authentication.getName());

            response.setStatus(200);
            response.setContentType("application/json;charset=UTF-8");

            String jsonResponse = String.format(
                    "{\"success\": true, \"message\": \"התחברות בוצעה בהצלחה\", \"username\": \"%s\"}",
                    authentication.getName()
            );

            response.getWriter().write(jsonResponse);
        };
    }

    /**
     * Authentication Failure Handler for form-based login (if needed)
     */
    @Bean
    public AuthenticationFailureHandler authenticationFailureHandler() {
        return (request, response, exception) -> {
            log.warn("Authentication failed: {}", exception.getMessage());

            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");

            String jsonResponse = "{\"success\": false, \"error\": \"שם משתמש או סיסמה שגויים\"}";

            response.getWriter().write(jsonResponse);
        };
    }

    /**
     * Logout Success Handler
     */
    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) -> {
            String username = authentication != null ? authentication.getName() : "unknown";
            log.info("Logout successful for user: {}", username);

            response.setStatus(200);
            response.setContentType("application/json;charset=UTF-8");

            String jsonResponse = "{\"success\": true, \"message\": \"התנתקות בוצעה בהצלחה\"}";

            response.getWriter().write(jsonResponse);
        };
    }
}