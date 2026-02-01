package com.ratelimiter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.ratelimiter.model.RateLimitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * High-performance cache manager for rate limit configurations using Caffeine.
 * Provides TTL-based expiration and cache statistics for monitoring.
 */
@Slf4j
@Component
public class ConfigCacheManager {

    @Value("${ratelimiter.cache.config-ttl-seconds:60}")
    private int cacheTtlSeconds;

    @Value("${ratelimiter.cache.max-size:10000}")
    private int maxCacheSize;

    @Value("${ratelimiter.cache.enable-stats:true}")
    private boolean enableStats;

    private Cache<String, RateLimitConfig> cache;

    @PostConstruct
    public void init() {
        Caffeine<Object, Object> cacheBuilder = Caffeine.newBuilder()
                .maximumSize(maxCacheSize)
                .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS);

        if (enableStats) {
            cacheBuilder.recordStats();
        }

        this.cache = cacheBuilder.build();

        log.info("Initialized config cache: maxSize={}, ttl={}s, stats={}",
                maxCacheSize, cacheTtlSeconds, enableStats);
    }

    /**
     * Get config from cache or load it using the provided loader function.
     *
     * @param key    the cache key
     * @param loader function to load the config if not in cache
     * @return the config, or null if loader returns null
     */
    public RateLimitConfig get(String key, Function<String, RateLimitConfig> loader) {
        return cache.get(key, loader);
    }

    /**
     * Get config from cache without loading if missing.
     *
     * @param key the cache key
     * @return the cached config, or null if not present
     */
    public RateLimitConfig getIfPresent(String key) {
        return cache.getIfPresent(key);
    }

    /**
     * Put a config into the cache.
     *
     * @param key    the cache key
     * @param config the config to cache
     */
    public void put(String key, RateLimitConfig config) {
        if (config != null) {
            cache.put(key, config);
        }
    }

    /**
     * Invalidate a specific cache entry.
     *
     * @param key the cache key to invalidate
     */
    public void invalidate(String key) {
        cache.invalidate(key);
        log.debug("Invalidated cache entry for key: {}", key);
    }

    /**
     * Invalidate all cache entries.
     */
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("Invalidated all cache entries");
    }

    /**
     * Get cache statistics.
     *
     * @return cache stats, or null if stats not enabled
     */
    public CacheStats getStats() {
        return cache.stats();
    }

    /**
     * Get current cache size.
     *
     * @return number of entries in cache
     */
    public long size() {
        return cache.estimatedSize();
    }

    /**
     * Get cache hit rate as a percentage.
     *
     * @return hit rate (0.0 to 100.0), or -1 if stats not enabled
     */
    public double getHitRate() {
        if (!enableStats) {
            return -1.0;
        }
        CacheStats stats = cache.stats();
        long total = stats.requestCount();
        if (total == 0) {
            return 0.0;
        }
        return (stats.hitCount() * 100.0) / total;
    }
}
