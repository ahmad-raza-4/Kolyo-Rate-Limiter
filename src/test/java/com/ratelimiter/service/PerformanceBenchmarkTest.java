package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.mockito.Mockito.*;

public class PerformanceBenchmarkTest {

    // ─────────────────────────────────────────────────────────────
    // 1. Benchmark: Pattern Matching (Regex compilation vs pre-compiled)
    // ─────────────────────────────────────────────────────────────
    @Test
    void benchmarkPatternMatching() {
        int iterations = 1_000_000;
        String patternStr = "user:premium:*";
        String regexStr = "^" + patternStr.replace("*", ".*") + "$";
        String testKey = "user:premium:987654";

        // Warmup
        for (int i = 0; i < 50_000; i++) {
            testKey.matches(regexStr);
            Pattern.compile(regexStr).matcher(testKey).matches();
        }

        // BEFORE approach: String.matches(regex) recompiles regex on every invocation
        long startBefore = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            boolean ignored = testKey.matches(regexStr);
        }
        long durationBeforeNs = System.nanoTime() - startBefore;

        // AFTER approach: pre-compiled Pattern
        Pattern precompiled = Pattern.compile(regexStr);
        long startAfter = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            boolean ignored = precompiled.matcher(testKey).matches();
        }
        long durationAfterNs = System.nanoTime() - startAfter;

        double beforeMs = durationBeforeNs / 1_000_000.0;
        double afterMs = durationAfterNs / 1_000_000.0;
        double speedup = (double) durationBeforeNs / durationAfterNs;

        System.out.println("=============================================================");
        System.out.println("BENCHMARK 1: PATTERN MATCHING (" + iterations + " operations)");
        System.out.println("-------------------------------------------------------------");
        System.out.printf("Before (String.matches - per-call compile): %8.2f ms (%8.2f ns/op)%n", beforeMs, (double) durationBeforeNs / iterations);
        System.out.printf("After  (Pre-compiled Pattern.matcher)     : %8.2f ms (%8.2f ns/op)%n", afterMs, (double) durationAfterNs / iterations);
        System.out.printf("Speedup factor                             : %8.2fx faster%n", speedup);
        System.out.println("=============================================================");
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Benchmark: Pattern Resolution (Old Redis fetch vs New in-memory config)
    // ─────────────────────────────────────────────────────────────
    @Test
    void benchmarkPatternResolutionWithMockRedis() {
        int iterations = 50_000;
        String key = "user:premium:42";

        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("config:pattern:user:premium:*")).thenReturn(Map.of(
                "algorithm", "TOKEN_BUCKET",
                "capacity", 100,
                "refillRate", 100.0,
                "refillPeriodSeconds", 60,
                "priority", 50
        ));

        PatternMatcher matcher = new PatternMatcher();
        PatternMatcher.CompiledPattern compiled = matcher.compile("user:premium:*", 50);

        // Simulated BEFORE: find best match in list, then call Redis hash
        List<PatternMatcher.CompiledPattern> oldPatterns = List.of(compiled);
        long startBefore = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            PatternMatcher.CompiledPattern best = matcher.findBestMatch(key, oldPatterns);
            if (best != null) {
                // Redis round trip mock simulation
                Map<Object, Object> hash = redisTemplate.opsForHash().entries("config:pattern:" + best.getPattern());
                RateLimitConfig config = RateLimitConfig.builder()
                        .algorithm(RateLimitAlgorithm.valueOf((String) hash.get("algorithm")))
                        .capacity((Integer) hash.get("capacity"))
                        .refillRate(((Number) hash.get("refillRate")).doubleValue())
                        .refillPeriodSeconds((Integer) hash.get("refillPeriodSeconds"))
                        .priority((Integer) hash.get("priority"))
                        .build();
            }
        }
        long durationBeforeNs = System.nanoTime() - startBefore;

        // Simulated AFTER: pattern entry stores compiled pattern and config directly in memory
        record PatternEntry(PatternMatcher.CompiledPattern compiled, RateLimitConfig config) {}
        RateLimitConfig cachedConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(100)
                .refillRate(100.0)
                .refillPeriodSeconds(60)
                .priority(50)
                .build();
        List<PatternEntry> newPatterns = List.of(new PatternEntry(compiled, cachedConfig));

        long startAfter = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            RateLimitConfig res = newPatterns.stream()
                    .filter(e -> e.compiled().matches(key))
                    .max(java.util.Comparator.comparingInt(e -> e.compiled().getPriority()))
                    .map(PatternEntry::config)
                    .orElse(null);
        }
        long durationAfterNs = System.nanoTime() - startAfter;

        double beforeMs = durationBeforeNs / 1_000_000.0;
        double afterMs = durationAfterNs / 1_000_000.0;
        double speedup = (double) durationBeforeNs / durationAfterNs;

        System.out.println("=============================================================");
        System.out.println("BENCHMARK 2: PATTERN CONFIG RESOLUTION (" + iterations + " operations)");
        System.out.println("-------------------------------------------------------------");
        System.out.printf("Before (Match + Mock Redis Hash Fetch)    : %8.2f ms (%8.2f ns/op)%n", beforeMs, (double) durationBeforeNs / iterations);
        System.out.printf("After  (Match + In-memory Config Cache)   : %8.2f ms (%8.2f ns/op)%n", afterMs, (double) durationAfterNs / iterations);
        System.out.printf("Speedup factor (even against mock Redis!) : %8.2fx faster%n", speedup);
        System.out.println("=============================================================");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Benchmark: KEYS vs SCAN on real Redis container
    // ─────────────────────────────────────────────────────────────
    @Test
    void benchmarkRedisKeysVsScan() {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("localhost", 6379);
        factory.afterPropertiesSet();

        RedisTemplate<String, Object> realRedis = new RedisTemplate<>();
        realRedis.setConnectionFactory(factory);
        realRedis.setKeySerializer(new org.springframework.data.redis.serializer.StringRedisSerializer());
        realRedis.afterPropertiesSet();

        try {
            realRedis.getConnectionFactory().getConnection().ping();
        } catch (Exception e) {
            System.out.println("Redis not reachable on 6379, skipping KEYS vs SCAN live test");
            return;
        }

        int keyCount = 5_000;
        System.out.println("Populating " + keyCount + " keys in Redis for KEYS vs SCAN comparison...");
        for (int i = 0; i < keyCount; i++) {
            realRedis.opsForValue().set("ratelimit:benchmark:" + i, "val");
        }

        int iterations = 50;

        // BEFORE: redis.keys()
        long startKeys = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            java.util.Set<String> keys = realRedis.keys("ratelimit:benchmark:*");
            assert keys != null && keys.size() == keyCount;
        }
        long durationKeysNs = System.nanoTime() - startKeys;

        // AFTER: scan with count=100
        long startScan = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            int count = 0;
            org.springframework.data.redis.core.ScanOptions options =
                    org.springframework.data.redis.core.ScanOptions.scanOptions().match("ratelimit:benchmark:*").count(100).build();
            try (org.springframework.data.redis.core.Cursor<String> cursor = realRedis.scan(options)) {
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            }
            assert count == keyCount;
        }
        long durationScanNs = System.nanoTime() - startScan;

        // Cleanup
        for (int i = 0; i < keyCount; i++) {
            realRedis.delete("ratelimit:benchmark:" + i);
        }

        double keysMsPerCall = (durationKeysNs / 1_000_000.0) / iterations;
        double scanMsPerCall = (durationScanNs / 1_000_000.0) / iterations;

        System.out.println("=============================================================");
        System.out.println("BENCHMARK 3: REDIS KEYS vs SCAN (" + keyCount + " keys, " + iterations + " runs)");
        System.out.println("-------------------------------------------------------------");
        System.out.printf("Before (redis.keys() - blocking O(N)) : %8.2f ms / call%n", keysMsPerCall);
        System.out.printf("After  (redis.scan() - non-blocking)  : %8.2f ms / call%n", scanMsPerCall);
        System.out.println("Operational impact: KEYS blocks all concurrent Redis traffic for " + String.format("%.2f", keysMsPerCall) + "ms;");
        System.out.println("SCAN runs in discrete chunks of 100, leaving Redis responsive to other threads.");
        System.out.println("=============================================================");

        factory.destroy();
    }
}
