package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
// service for managing rate limit configurations
public class ConfigService {

    // redis template for data operations
    private final RedisTemplate<String, Object> redisTemplate;

    // default configuration values from application properties
    @Value("${ratelimiter.default.capacity}")
    private Integer defaultCapacity;

    @Value("${ratelimiter.default.refill-rate}")
    private Integer defaultRefillRate;

    @Value("${ratelimiter.default.refill-period-seconds}")
    private Integer defaultRefillPeriod;

    // retrieves the rate limit configuration for the given key
    public RateLimitConfig getConfig(String key) {
        // Try exact key match first
        RateLimitConfig config = getConfigFromRedis("config:key:" + key);
        if (config != null) {
            return config;
        }

        // Try pattern matching (simplified for now)
        // TODO: Implement proper pattern matching in Phase 3
        
        // Return default configuration
        return getDefaultConfig();
    }

    // saves the rate limit configuration for the given key in redis
    public void saveConfig(String key, RateLimitConfig config) {
        config.validate();
        config.setUpdatedAt(Instant.now());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(Instant.now());
        }

        String redisKey = "config:key:" + key;
        redisTemplate.opsForHash().put(redisKey, "algorithm", config.getAlgorithm().name());
        redisTemplate.opsForHash().put(redisKey, "capacity", config.getCapacity());
        redisTemplate.opsForHash().put(redisKey, "refillRate", config.getRefillRate());
        redisTemplate.opsForHash().put(redisKey, "refillPeriodSeconds", config.getRefillPeriodSeconds());
        redisTemplate.expire(redisKey, 30, TimeUnit.DAYS);

        log.info("Saved configuration for key: {}", key);
    }

    // deletes the rate limit configuration for the given key from redis
    public void deleteConfig(String key) {
        String redisKey = "config:key:" + key;
        redisTemplate.delete(redisKey);
        log.info("Deleted configuration for key: {}", key);
    }

    // loads the rate limit configuration from redis for the given key
    private RateLimitConfig getConfigFromRedis(String redisKey) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
            return null;
        }

        try {
            String algorithmStr = (String) redisTemplate.opsForHash().get(redisKey, "algorithm");
            Integer capacity = (Integer) redisTemplate.opsForHash().get(redisKey, "capacity");
            Double refillRate = (Double) redisTemplate.opsForHash().get(redisKey, "refillRate");
            Integer refillPeriod = (Integer) redisTemplate.opsForHash().get(redisKey, "refillPeriodSeconds");

            return RateLimitConfig.builder()
                    .algorithm(RateLimitAlgorithm.valueOf(algorithmStr))
                    .capacity(capacity)
                    .refillRate(refillRate)
                    .refillPeriodSeconds(refillPeriod)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load config from Redis key: {}", redisKey, e);
            return null;
        }
    }

    // returns the default rate limit configuration
    private RateLimitConfig getDefaultConfig() {
        return RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(defaultCapacity)
                .refillRate(defaultRefillRate.doubleValue())
                .refillPeriodSeconds(defaultRefillPeriod)
                .priority(0)
                .build();
    }
}