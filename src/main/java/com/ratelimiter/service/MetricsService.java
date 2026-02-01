package com.ratelimiter.service;

import com.ratelimiter.dto.RateLimitResponse;
import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry registry;
    private final AtomicInteger activeKeys = new AtomicInteger(0);

    // ─── Core check: tagged by algorithm + result ────────────────────
    public void recordCheck(RateLimitResponse response, long latencyMicros, String algorithm) {
        String result = response.isAllowed() ? "allowed" : "denied";

        registry.counter("ratelimit.checks.total",
                "algorithm", algorithm,
                "result", result)
                .increment();

        registry.timer("ratelimit.check.duration",
                "algorithm", algorithm)
                .record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    // ─── Pattern resolution ───────────────────────────────────────────
    public void recordPatternMatch(String pattern) {
        registry.counter("ratelimit.pattern.hits",
                "pattern", pattern)
                .increment();
    }

    public void recordPatternMiss() {
        registry.counter("ratelimit.pattern.misses").increment();
    }

    // ─── Redis layer ──────────────────────────────────────────────────
    public void recordRedisOp(String operation, long latencyMicros, boolean success) {
        registry.counter("ratelimit.redis.ops",
                "operation", operation,
                "status", success ? "ok" : "error")
                .increment();

        registry.timer("ratelimit.redis.duration",
                "operation", operation)
                .record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    // ─── Config cache ─────────────────────────────────────────────────
    public void recordCacheHit() {
        registry.counter("ratelimit.cache.hits").increment();
    }

    public void recordCacheMiss() {
        registry.counter("ratelimit.cache.misses").increment();
    }

    // ─── Errors & circuit breaker ─────────────────────────────────────
    public void recordError() {
        registry.counter("ratelimit.errors").increment();
    }

    public void recordCircuitBreakerTrip() {
        registry.counter("ratelimit.cb.trips").increment();
    }

    // ─── Live gauge ───────────────────────────────────────────────────
    public void updateActiveKeys(int count) {
        activeKeys.set(count);
        registry.gauge("ratelimit.keys.active", activeKeys);
    }
}