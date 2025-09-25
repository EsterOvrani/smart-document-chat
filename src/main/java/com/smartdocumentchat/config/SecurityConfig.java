package com.smartdocumentchat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import lombok.extern.slf4j.Slf4j;

@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        log.info("Creating BCryptPasswordEncoder");
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        log.info("Configuring security filter chain - basic authentication setup");

        http
                .csrf(csrf -> csrf.disable()) // נזהר מזה בproduction
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/api/auth/**").permitAll() // נאפשר גישה לendpoints של authentication
                        .requestMatchers("/api/status").permitAll() // נאפשר גישה לstatus check
                        .requestMatchers("/actuator/**").permitAll() // לניטור
                        .requestMatchers("/api/users/current").permitAll() // temporary - לתאימות
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .maximumSessions(1) // משתמש אחד יכול להיות מחובר רק ממקום אחד
                        .maxSessionsPreventsLogin(false) // אפשר login חדש (יבטל את הישן)
                )
                .headers(headers -> headers
                        .frameOptions(frameOptions -> frameOptions.sameOrigin())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer ->
                                referrer.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                );

        log.info("Security filter chain configured successfully");
        return http.build();
    }

    /**
     * הגדרת session registry
     */
    @Bean
    public org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }
}