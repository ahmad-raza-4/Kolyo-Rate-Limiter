package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final PatternMatcher patternMatcher;

    @Value("${ratelimiter.default.capacity}")
    private Integer defaultCapacity;

    @Value("${ratelimiter.default.refill-rate}")
    private Integer defaultRefillRate;

    @Value("${ratelimiter.default.refill-period-seconds}")
    private Integer defaultRefillPeriod;

    // Simple in-memory cache for performance
    private final Map<String, RateLimitConfig> configCache = new ConcurrentHashMap<>();
    private final Map<String, PatternMatcher.CompiledPattern> patternCache = new ConcurrentHashMap<>();

    /**
     * Load all patterns from Redis on startup to avoid repeated Redis calls.
     * This eliminates the expensive getAllPatterns() call in findMatchingPattern().
     */
    @PostConstruct
    public void init() {
        log.info("Loading pattern configurations from Redis...");
        List<RateLimitConfig> patterns = getAllPatterns();
        for (RateLimitConfig config : patterns) {
            PatternMatcher.CompiledPattern compiled = patternMatcher.compile(
                    config.getKeyPattern(),
                    config.getPriority());
            patternCache.put(config.getKeyPattern(), compiled);
        }
        log.info("Loaded {} pattern configurations into cache", patterns.size());
    }

    public RateLimitConfig getConfig(String key) {
        // Check cache first
        RateLimitConfig cached = configCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Try exact key match
        RateLimitConfig config = getConfigFromRedis("config:key:" + key);
        if (config != null) {
            configCache.put(key, config);
            return config;
        }

        // Try pattern matching
        config = findMatchingPattern(key);
        if (config != null) {
            configCache.put(key, config);
            return config;
        }

        // Return default configuration
        RateLimitConfig defaultConfig = getDefaultConfig();
        configCache.put(key, defaultConfig);
        return defaultConfig;
    }

    public void saveKeyConfig(String key, RateLimitConfig config) {
        config.validate();
        config.setKeyPattern(key);
        config.setUpdatedAt(Instant.now());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(Instant.now());
        }

        String redisKey = "config:key:" + key;
        saveConfigToRedis(redisKey, config);

        // Invalidate cache
        configCache.remove(key);

        log.info("Saved key configuration: {}", key);
    }

    public void savePatternConfig(String pattern, RateLimitConfig config) {
        config.validate();
        config.setKeyPattern(pattern);

        // Calculate priority if not set
        if (config.getPriority() == null) {
            config.setPriority(patternMatcher.calculatePriority(pattern));
        }

        config.setUpdatedAt(Instant.now());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(Instant.now());
        }

        String redisKey = "config:pattern:" + pattern;
        saveConfigToRedis(redisKey, config);

        // Update pattern cache
        PatternMatcher.CompiledPattern compiled = patternMatcher.compile(pattern, config.getPriority());
        patternCache.put(pattern, compiled);

        // Clear config cache since patterns changed
        configCache.clear();

        log.info("Saved pattern configuration: {} with priority {}", pattern, config.getPriority());
    }

    public void deleteKeyConfig(String key) {
        String redisKey = "config:key:" + key;
        redisTemplate.delete(redisKey);
        configCache.remove(key);
        log.info("Deleted key configuration: {}", key);
    }

    public void deletePatternConfig(String pattern) {
        String redisKey = "config:pattern:" + pattern;
        redisTemplate.delete(redisKey);
        patternCache.remove(pattern);
        configCache.clear(); // Clear all since pattern matching changed
        log.info("Deleted pattern configuration: {}", pattern);
    }

    public List<RateLimitConfig> getAllPatterns() {
        List<RateLimitConfig> patterns = new ArrayList<>();

        var keys = redisTemplate.keys("config:pattern:*");
        if (keys != null) {
            for (String redisKey : keys) {
                RateLimitConfig config = getConfigFromRedis(redisKey);
                if (config != null) {
                    patterns.add(config);
                }
            }
        }

        return patterns;
    }

    public void reloadConfigurations() {
        log.info("Reloading configurations...");
        configCache.clear();
        patternCache.clear();
        patternMatcher.clearCache();

        // Rebuild pattern cache
        List<RateLimitConfig> patterns = getAllPatterns();
        for (RateLimitConfig config : patterns) {
            PatternMatcher.CompiledPattern compiled = patternMatcher.compile(config.getKeyPattern(),
                    config.getPriority());
            patternCache.put(config.getKeyPattern(), compiled);
        }

        log.info("Reloaded {} pattern configurations", patterns.size());
    }

    private RateLimitConfig findMatchingPattern(String key) {
        // Use in-memory pattern cache (loaded at startup)
        List<PatternMatcher.CompiledPattern> patterns = new ArrayList<>(patternCache.values());

        if (patterns.isEmpty()) {
            log.warn("No patterns loaded in cache - this shouldn't happen after init()");
            return null;
        }

        PatternMatcher.CompiledPattern bestMatch = patternMatcher.findBestMatch(key, patterns);

        if (bestMatch != null) {
            String redisKey = "config:pattern:" + bestMatch.getPattern();
            return getConfigFromRedis(redisKey);
        }

        return null;
    }

    private void saveConfigToRedis(String redisKey, RateLimitConfig config) {
        redisTemplate.opsForHash().put(redisKey, "algorithm", config.getAlgorithm().name());
        redisTemplate.opsForHash().put(redisKey, "capacity", config.getCapacity());
        redisTemplate.opsForHash().put(redisKey, "refillRate", config.getRefillRate());
        redisTemplate.opsForHash().put(redisKey, "refillPeriodSeconds", config.getRefillPeriodSeconds());

        if (config.getKeyPattern() != null) {
            redisTemplate.opsForHash().put(redisKey, "keyPattern", config.getKeyPattern());
        }

        if (config.getPriority() != null) {
            redisTemplate.opsForHash().put(redisKey, "priority", config.getPriority());
        }

        redisTemplate.expire(redisKey, 30, TimeUnit.DAYS);
    }

    private RateLimitConfig getConfigFromRedis(String redisKey) {
        try {
            // Directly fetch hash - if key doesn't exist, entries() returns empty map
            // This saves 1 Redis round trip vs checking hasKey() first
            Map<Object, Object> hash = redisTemplate.opsForHash().entries(redisKey);

            if (hash.isEmpty()) {
                return null;
            }

            // Add null checks before casting to prevent NullPointerException
            Object algorithmObj = hash.get("algorithm");
            Object capacityObj = hash.get("capacity");
            Object refillRateObj = hash.get("refillRate");
            Object refillPeriodObj = hash.get("refillPeriodSeconds");

            if (algorithmObj == null || capacityObj == null || refillRateObj == null || refillPeriodObj == null) {
                log.warn("Invalid config in Redis - missing required fields for key: {}", redisKey);
                return null;
            }

            String algorithmStr = (String) algorithmObj;
            Integer capacity = (Integer) capacityObj;
            Double refillRate = ((Number) refillRateObj).doubleValue();
            Integer refillPeriod = (Integer) refillPeriodObj;
            
            // Handle optional priority field with null check
            Integer priority = 0;
            if (hash.containsKey("priority")) {
                Object priorityObj = hash.get("priority");
                priority = priorityObj != null ? (Integer) priorityObj : 0;
            }

            // Extract keyPattern from hash or derive from Redis key (optional field)
            // The keyPattern can be null if not stored in hash, in which case it's derived from redisKey
            String keyPattern = (String) hash.get("keyPattern");
            if (keyPattern == null) {
                // Fallback: extract from Redis key (e.g., "config:pattern:api:*" -> "api:*")
                if (redisKey.startsWith("config:pattern:")) {
                    keyPattern = redisKey.substring("config:pattern:".length());
                } else if (redisKey.startsWith("config:key:")) {
                    keyPattern = redisKey.substring("config:key:".length());
                }
            }

            return RateLimitConfig.builder()
                    .keyPattern(keyPattern)
                    .algorithm(RateLimitAlgorithm.valueOf(algorithmStr))
                    .capacity(capacity)
                    .refillRate(refillRate)
                    .refillPeriodSeconds(refillPeriod)
                    .priority(priority)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load config from Redis key: {}", redisKey, e);
            return null;
        }
    }

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