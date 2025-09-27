package com.smartdocumentchat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * JWT signing secret key - should be at least 32 bytes for HS256
     */
    private String secret = "mySecretKeyForSmartDocumentChatApplication2024!@#$%^&*()_+";

    /**
     * JWT issuer
     */
    private String issuer = "smart-document-chat";

    /**
     * Access token expiration time
     */
    private Duration accessTokenExpiration = Duration.ofHours(1);

    /**
     * Refresh token expiration time
     */
    private Duration refreshTokenExpiration = Duration.ofDays(7);

    /**
     * Token type
     */
    private String tokenType = "Bearer";

    /**
     * HTTP header name for JWT token
     */
    private String headerName = "Authorization";

    /**
     * Prefix for the JWT token in the header
     */
    private String headerPrefix = "Bearer ";

    // Helper methods

    public long getAccessTokenExpirationMillis() {
        return accessTokenExpiration.toMillis();
    }

    public long getRefreshTokenExpirationMillis() {
        return refreshTokenExpiration.toMillis();
    }

    public boolean isTokenExpired(long tokenIssuedAt) {
        long currentTime = System.currentTimeMillis();
        return (currentTime - tokenIssuedAt) > getAccessTokenExpirationMillis();
    }

    public boolean isRefreshTokenExpired(long tokenIssuedAt) {
        long currentTime = System.currentTimeMillis();
        return (currentTime - tokenIssuedAt) > getRefreshTokenExpirationMillis();
    }
}