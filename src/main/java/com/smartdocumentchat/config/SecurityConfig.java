package com.smartdocumentchat.config;

import com.smartdocumentchat.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
//    private final SessionAuthenticationFilter sessionAuthenticationFilter;

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
        log.info("Configuring security filter chain with basic authentication");

        http
                .csrf(csrf -> csrf.disable()) // נזהר מזה בproduction
                .authorizeHttpRequests(authz -> authz
                        // Authentication endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/status").permitAll()
                        .requestMatchers("/actuator/**").permitAll()

                        // Login page and static resources
                        .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**").permitAll()

                        // All other requests require authentication
                        .anyRequest().authenticated()
                )

                // Form login configuration
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/api/auth/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(authenticationSuccessHandler())
                        .failureHandler(authenticationFailureHandler())
                        .permitAll()
                )

                // Logout configuration
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler(logoutSuccessHandler())
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID", "SMARTDOC_SESSION")
                        .permitAll()
                )

                // Session management
                .sessionManagement(session -> session
                        .maximumSessions(1)
                        .maxSessionsPreventsLogin(false)
                        .sessionRegistry(sessionRegistry())
                )

                // Security headers
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                );


        log.info("Security filter chain configured successfully with form-based authentication");
        return http.build();
    }

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

    @Bean
    public AuthenticationFailureHandler authenticationFailureHandler() {
        return (request, response, exception) -> {
            log.warn("Authentication failed: {}", exception.getMessage());

            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");

            String jsonResponse = String.format(
                    "{\"success\": false, \"error\": \"שם משתמש או סיסמה שגויים\"}");

            response.getWriter().write(jsonResponse);
        };
    }

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

    @Bean
    public org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }
}