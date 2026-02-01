package com.ratelimiter.service;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ConfigCacheManagerTest {

    private ConfigCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager = new ConfigCacheManager();
        // Set test properties
        ReflectionTestUtils.setField(cacheManager, "cacheTtlSeconds", 2);
        ReflectionTestUtils.setField(cacheManager, "maxCacheSize", 100);
        ReflectionTestUtils.setField(cacheManager, "enableStats", true);
        cacheManager.init();
    }

    @Test
    void shouldCacheConfigAndRetrieveIt() {
        // Given
        String key = "test:key";
        RateLimitConfig config = createTestConfig();

        // When
        cacheManager.put(key, config);

        // Then
        RateLimitConfig retrieved = cacheManager.getIfPresent(key);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getKeyPattern()).isEqualTo("test:*");
        assertThat(retrieved.getCapacity()).isEqualTo(100);
    }

    @Test
    void shouldReturnNullForMissingKey() {
        // When
        RateLimitConfig retrieved = cacheManager.getIfPresent("nonexistent:key");

        // Then
        assertThat(retrieved).isNull();
    }

    @Test
    void shouldLoadConfigUsingLoaderFunction() {
        // Given
        String key = "loader:key";
        RateLimitConfig config = createTestConfig();

        // When
        RateLimitConfig retrieved = cacheManager.get(key, k -> config);

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved).isEqualTo(config);
        
        // Verify it's cached
        RateLimitConfig cachedConfig = cacheManager.getIfPresent(key);
        assertThat(cachedConfig).isEqualTo(config);
    }

    @Test
    void shouldNotCacheNullValues() {
        // Given
        String key = "null:key";

        // When
        cacheManager.put(key, null);

        // Then
        RateLimitConfig retrieved = cacheManager.getIfPresent(key);
        assertThat(retrieved).isNull();
        assertThat(cacheManager.size()).isEqualTo(0);
    }

    @Test
    void shouldInvalidateSpecificKey() {
        // Given
        String key1 = "key1";
        String key2 = "key2";
        cacheManager.put(key1, createTestConfig());
        cacheManager.put(key2, createTestConfig());

        // When
        cacheManager.invalidate(key1);

        // Then
        assertThat(cacheManager.getIfPresent(key1)).isNull();
        assertThat(cacheManager.getIfPresent(key2)).isNotNull();
        assertThat(cacheManager.size()).isEqualTo(1);
    }

    @Test
    void shouldInvalidateAllKeys() {
        // Given
        cacheManager.put("key1", createTestConfig());
        cacheManager.put("key2", createTestConfig());
        cacheManager.put("key3", createTestConfig());

        // When
        cacheManager.invalidateAll();

        // Then
        assertThat(cacheManager.size()).isEqualTo(0);
        assertThat(cacheManager.getIfPresent("key1")).isNull();
        assertThat(cacheManager.getIfPresent("key2")).isNull();
        assertThat(cacheManager.getIfPresent("key3")).isNull();
    }

    @Test
    void shouldExpireEntriesAfterTTL() {
        // Given
        String key = "expire:key";
        cacheManager.put(key, createTestConfig());

        // Verify it's initially cached
        assertThat(cacheManager.getIfPresent(key)).isNotNull();

        // When - wait for TTL expiration (2 seconds + buffer)
        await().atMost(3, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .until(() -> cacheManager.getIfPresent(key) == null);

        // Then
        assertThat(cacheManager.getIfPresent(key)).isNull();
    }

    @Test
    void shouldTrackCacheSize() {
        // Given
        assertThat(cacheManager.size()).isEqualTo(0);

        // When
        cacheManager.put("key1", createTestConfig());
        cacheManager.put("key2", createTestConfig());
        cacheManager.put("key3", createTestConfig());

        // Then
        assertThat(cacheManager.size()).isEqualTo(3);
    }

    @Test
    void shouldProvideStatistics() {
        // Given
        String key = "stats:key";
        RateLimitConfig config = createTestConfig();
        cacheManager.put(key, config);

        // When - trigger cache hits and misses
        cacheManager.getIfPresent(key);  // hit
        cacheManager.getIfPresent(key);  // hit
        cacheManager.getIfPresent("missing:key");  // miss

        // Then
        CacheStats stats = cacheManager.getStats();
        assertThat(stats).isNotNull();
        assertThat(stats.hitCount()).isEqualTo(2);
        assertThat(stats.missCount()).isEqualTo(1);
        assertThat(stats.requestCount()).isEqualTo(3);
    }

    @Test
    void shouldCalculateHitRate() {
        // Given
        String key = "hitrate:key";
        RateLimitConfig config = createTestConfig();
        cacheManager.put(key, config);

        // When - trigger cache hits and misses
        cacheManager.getIfPresent(key);  // hit
        cacheManager.getIfPresent(key);  // hit
        cacheManager.getIfPresent(key);  // hit
        cacheManager.getIfPresent("missing:key");  // miss

        // Then
        double hitRate = cacheManager.getHitRate();
        assertThat(hitRate).isEqualTo(75.0);  // 3 hits out of 4 requests = 75%
    }

    @Test
    void shouldReturnZeroHitRateWhenNoRequests() {
        // When
        double hitRate = cacheManager.getHitRate();

        // Then
        assertThat(hitRate).isEqualTo(0.0);
    }

    @Test
    void shouldReturnNegativeHitRateWhenStatsDisabled() {
        // Given
        ConfigCacheManager noStatsManager = new ConfigCacheManager();
        ReflectionTestUtils.setField(noStatsManager, "cacheTtlSeconds", 60);
        ReflectionTestUtils.setField(noStatsManager, "maxCacheSize", 100);
        ReflectionTestUtils.setField(noStatsManager, "enableStats", false);
        noStatsManager.init();

        // When
        double hitRate = noStatsManager.getHitRate();

        // Then
        assertThat(hitRate).isEqualTo(-1.0);
    }

    @Test
    void shouldHandleLoaderReturningNull() {
        // Given
        String key = "null:loader:key";

        // When
        RateLimitConfig result = cacheManager.get(key, k -> null);

        // Then
        assertThat(result).isNull();
        // Note: Caffeine caches null values from loaders (size=1), but put(key, null) 
        // is explicitly prevented by ConfigCacheManager and doesn't cache (size=0)
        assertThat(cacheManager.size()).isEqualTo(1);
    }

    @Test
    void shouldOverwriteExistingCacheEntry() {
        // Given
        String key = "overwrite:key";
        RateLimitConfig config1 = createTestConfig();
        RateLimitConfig config2 = RateLimitConfig.builder()
                .keyPattern("different:*")
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .capacity(200)
                .refillRate(20.0)
                .refillPeriodSeconds(30)
                .build();

        // When
        cacheManager.put(key, config1);
        cacheManager.put(key, config2);

        // Then
        RateLimitConfig retrieved = cacheManager.getIfPresent(key);
        assertThat(retrieved).isEqualTo(config2);
        assertThat(retrieved.getCapacity()).isEqualTo(200);
        assertThat(cacheManager.size()).isEqualTo(1);
    }

    private RateLimitConfig createTestConfig() {
        return RateLimitConfig.builder()
                .keyPattern("test:*")
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(100)
                .refillRate(10.0)
                .refillPeriodSeconds(60)
                .build();
    }
}
