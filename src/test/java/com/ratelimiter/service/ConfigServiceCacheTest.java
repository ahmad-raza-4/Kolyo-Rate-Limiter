package com.ratelimiter.service;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceCacheTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private PatternMatcher patternMatcher;
    private ConfigCacheManager configCacheManager;
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        patternMatcher = new PatternMatcher();

        configCacheManager = new ConfigCacheManager();
        ReflectionTestUtils.setField(configCacheManager, "cacheTtlSeconds", 60);
        ReflectionTestUtils.setField(configCacheManager, "maxCacheSize", 1000);
        ReflectionTestUtils.setField(configCacheManager, "enableStats", true);
        configCacheManager.init();

        configService = new ConfigService(redisTemplate, patternMatcher, configCacheManager);
        ReflectionTestUtils.setField(configService, "defaultCapacity", 10);
        ReflectionTestUtils.setField(configService, "defaultRefillRate", 10);
        ReflectionTestUtils.setField(configService, "defaultRefillPeriod", 60);
    }

    @Test
    void shouldCacheConfigInCaffeineAndHitCacheOnSubsequentCalls() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("config:key:user:vip")).thenReturn(Map.of(
                "algorithm", "TOKEN_BUCKET",
                "capacity", 50,
                "refillRate", 50.0,
                "refillPeriodSeconds", 60,
                "priority", 100
        ));

        // First call: misses cache, loads from Redis
        RateLimitConfig config1 = configService.getConfig("user:vip");
        assertThat(config1).isNotNull();
        assertThat(config1.getCapacity()).isEqualTo(50);
        verify(hashOperations, times(1)).entries("config:key:user:vip");

        // Second call: must hit Caffeine cache without calling Redis again
        RateLimitConfig config2 = configService.getConfig("user:vip");
        assertThat(config2).isNotNull();
        assertThat(config2.getCapacity()).isEqualTo(50);
        verify(hashOperations, times(1)).entries("config:key:user:vip"); // still 1!

        // Verify Caffeine cache stats confirm a hit
        CacheStats stats = configCacheManager.getStats();
        assertThat(stats.hitCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldInvalidateCaffeineCacheWhenKeyConfigIsSaved() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("config:key:user:dynamic")).thenReturn(Map.of(
                "algorithm", "TOKEN_BUCKET",
                "capacity", 10,
                "refillRate", 10.0,
                "refillPeriodSeconds", 60
        ));

        // Warm up cache
        configService.getConfig("user:dynamic");
        assertThat(configCacheManager.getIfPresent("user:dynamic")).isNotNull();

        // Save new config -> must invalidate Caffeine cache entry
        RateLimitConfig updated = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(200)
                .refillRate(200.0)
                .refillPeriodSeconds(60)
                .build();
        configService.saveKeyConfig("user:dynamic", updated);

        // Verify cache entry was invalidated
        assertThat(configCacheManager.getIfPresent("user:dynamic")).isNull();
    }
}
