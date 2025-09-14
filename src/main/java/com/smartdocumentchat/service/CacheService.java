package com.smartdocumentchat.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;

    // Default TTL configurations
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final Duration QA_CACHE_TTL = Duration.ofMinutes(30);
    private static final Duration DOCUMENT_METADATA_TTL = Duration.ofHours(6);

    /**
     * Basic get operation
     */
    public Object get(String key) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                log.debug("Cache HIT for key: {}", key);
            } else {
                log.debug("Cache MISS for key: {}", key);
            }
            return value;
        } catch (Exception e) {
            log.error("Error getting value from cache for key: {}", key, e);
            return null;
        }
    }

    /**
     * Basic set operation with default TTL
     */
    public void set(String key, Object value) {
        set(key, value, DEFAULT_TTL);
    }

    /**
     * Set operation with custom TTL
     */
    public void set(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
            log.debug("Cached value for key: {} with TTL: {} seconds", key, ttl.toSeconds());
        } catch (Exception e) {
            log.error("Error setting value in cache for key: {}", key, e);
        }
    }

    /**
     * Delete operation
     */
    public void delete(String key) {
        try {
            Boolean deleted = redisTemplate.delete(key);
            if (Boolean.TRUE.equals(deleted)) {
                log.debug("Deleted cache entry for key: {}", key);
            } else {
                log.debug("Cache entry not found for deletion: {}", key);
            }
        } catch (Exception e) {
            log.error("Error deleting value from cache for key: {}", key, e);
        }
    }

    /**
     * Check if key exists
     */
    public boolean exists(String key) {
        try {
            Boolean exists = redisTemplate.hasKey(key);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("Error checking if key exists in cache: {}", key, e);
            return false;
        }
    }

    /**
     * Get TTL for a key (in seconds)
     */
    public long getTTL(String key) {
        try {
            Long ttl = redisTemplate.getExpire(key);
            return ttl != null ? ttl : -1;
        } catch (Exception e) {
            log.error("Error getting TTL for key: {}", key, e);
            return -1;
        }
    }

    /**
     * Session-specific caching methods
     */
    public void cacheSessionData(String sessionId, Object data) {
        String key = "session:" + sessionId;
        set(key, data, SESSION_TTL);
    }

    public Object getSessionData(String sessionId) {
        String key = "session:" + sessionId;
        return get(key);
    }

    public void invalidateSession(String sessionId) {
        String key = "session:" + sessionId;
        delete(key);
    }

    /**
     * Q&A caching methods
     */
    public void cacheQAResult(String questionHash, String answer) {
        String key = "qa:" + questionHash;
        set(key, answer, QA_CACHE_TTL);
    }

    public String getCachedQAResult(String questionHash) {
        String key = "qa:" + questionHash;
        Object result = get(key);
        return result != null ? result.toString() : null;
    }

    /**
     * Document metadata caching
     */
    public void cacheDocumentMetadata(String documentId, Object metadata) {
        String key = "doc_meta:" + documentId;
        set(key, metadata, DOCUMENT_METADATA_TTL);
    }

    public Object getDocumentMetadata(String documentId) {
        String key = "doc_meta:" + documentId;
        return get(key);
    }

    /**
     * Utility methods
     */
    public void clearAllCache() {
        try {
            redisTemplate.getConnectionFactory().getConnection().flushAll();
            log.info("Cleared all cache entries");
        } catch (Exception e) {
            log.error("Error clearing all cache", e);
        }
    }

    public String generateCacheKey(String prefix, String... parts) {
        StringBuilder key = new StringBuilder(prefix);
        for (String part : parts) {
            key.append(":").append(part);
        }
        return key.toString();
    }
}