package com.jobai.knowledge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobai.common.JobAiProperties.RetrievalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalConfigService {

    private static final String REDIS_KEY = "retrieval:config";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RetrievalProperties defaultConfig;

    /** Get current config from Redis, or null if no override is set. */
    public RetrievalProperties getActiveConfig() {
        String json = redis.opsForValue().get(REDIS_KEY);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, RetrievalProperties.class);
        } catch (Exception e) {
            log.warn("Failed to parse retrieval config from Redis, using defaults", e);
            return null;
        }
    }

    /** Save config to Redis (persisted). */
    public void saveConfig(RetrievalProperties config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            redis.opsForValue().set(REDIS_KEY, json, 365, TimeUnit.DAYS);
            log.info("Retrieval config saved to Redis");
        } catch (Exception e) {
            throw new RuntimeException("Failed to save retrieval config", e);
        }
    }

    /** Reset to application.yml defaults. */
    public void resetConfig() {
        redis.delete(REDIS_KEY);
        log.info("Retrieval config reset to defaults");
    }
}
