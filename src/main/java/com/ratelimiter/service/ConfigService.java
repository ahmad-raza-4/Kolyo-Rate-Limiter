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
    // Change 1: wire the Caffeine-backed cache instead of a raw ConcurrentHashMap
    private final ConfigCacheManager configCacheManager;

    @Value("${ratelimiter.default.capacity}")
    private Integer defaultCapacity;

    @Value("${ratelimiter.default.refill-rate}")
    private Integer defaultRefillRate;

    @Value("${ratelimiter.default.refill-period-seconds}")
    private Integer defaultRefillPeriod;

    // Pattern cache holds compiled pattern + its full config to avoid an extra Redis round-trip on each match
    private record PatternEntry(PatternMatcher.CompiledPattern compiled, RateLimitConfig config) {}
    private final Map<String, PatternEntry> patternCache = new ConcurrentHashMap<>();

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
            patternCache.put(config.getKeyPattern(), new PatternEntry(compiled, config));
        }
        log.info("Loaded {} pattern configurations into cache", patterns.size());
    }

    public RateLimitConfig getConfig(String key) {
        // Check Caffeine cache first (TTL-based, bounded)
        RateLimitConfig cached = configCacheManager.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        // Try exact key match in Redis
        RateLimitConfig config = getConfigFromRedis("config:key:" + key);
        if (config != null) {
            configCacheManager.put(key, config);
            return config;
        }

        // Try pattern matching (no extra Redis call — config is embedded in PatternEntry)
        config = findMatchingPattern(key);
        if (config != null) {
            configCacheManager.put(key, config);
            return config;
        }

        // Return default configuration
        RateLimitConfig defaultConfig = getDefaultConfig();
        configCacheManager.put(key, defaultConfig);
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

        // Invalidate the Caffeine cache entry so the next request picks up the new config
        configCacheManager.invalidate(key);

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

        // Update pattern cache — store the compiled pattern AND the config together
        PatternMatcher.CompiledPattern compiled = patternMatcher.compile(pattern, config.getPriority());
        patternCache.put(pattern, new PatternEntry(compiled, config));

        // Clear all Caffeine-cached configs since pattern changes affect any matching key
        configCacheManager.invalidateAll();

        log.info("Saved pattern configuration: {} with priority {}", pattern, config.getPriority());
    }

    public void deleteKeyConfig(String key) {
        String redisKey = "config:key:" + key;
        redisTemplate.delete(redisKey);
        configCacheManager.invalidate(key);
        log.info("Deleted key configuration: {}", key);
    }

    public void deletePatternConfig(String pattern) {
        String redisKey = "config:pattern:" + pattern;
        redisTemplate.delete(redisKey);
        patternCache.remove(pattern);
        configCacheManager.invalidateAll(); // pattern removal affects all matching keys
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
        configCacheManager.invalidateAll();
        patternCache.clear();
        patternMatcher.clearCache();

        // Rebuild pattern cache with full PatternEntry (compiled + config)
        List<RateLimitConfig> patterns = getAllPatterns();
        for (RateLimitConfig config : patterns) {
            PatternMatcher.CompiledPattern compiled = patternMatcher.compile(config.getKeyPattern(),
                    config.getPriority());
            patternCache.put(config.getKeyPattern(), new PatternEntry(compiled, config));
        }

        log.info("Reloaded {} pattern configurations", patterns.size());
    }

    private RateLimitConfig findMatchingPattern(String key) {
        List<PatternEntry> entries = new ArrayList<>(patternCache.values());

        if (entries.isEmpty()) {
            return null;
        }

        // Find the highest-priority entry whose compiled pattern matches this key
        return entries.stream()
                .filter(e -> e.compiled().matches(key))
                .max(java.util.Comparator.comparingInt(e -> e.compiled().getPriority()))
                .map(PatternEntry::config)
                .orElse(null);
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

            String algorithmStr = (String) hash.get("algorithm");
            Integer capacity = (Integer) hash.get("capacity");
            Double refillRate = ((Number) hash.get("refillRate")).doubleValue();
            Integer refillPeriod = (Integer) hash.get("refillPeriodSeconds");
            Integer priority = hash.containsKey("priority") ? (Integer) hash.get("priority") : 0;

            // Extract keyPattern from hash or derive from Redis key
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