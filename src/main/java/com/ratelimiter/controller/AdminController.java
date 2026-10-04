package com.ratelimiter.controller;

import com.ratelimiter.service.ConfigService;
import com.ratelimiter.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Administrative operations")
public class AdminController {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RateLimitService rateLimitService;
    private final ConfigService configService;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KeyInfo {
        private String key;
        private String type; // bucket, sliding, fixed, leaky, swc
        private Object state;
        private Long ttl;
    }

    @Data
    @Builder
    public static class SystemStats {
        private long totalKeys;
        private long bucketKeys;
        private long slidingKeys;
        private long fixedKeys;
        private long leakyKeys;
        private long swcKeys;
        private long configKeys;
    }

    @GetMapping("/keys")
    @Operation(summary = "List all keys", description = "Get all active rate limit keys")
    public ResponseEntity<List<KeyInfo>> listAllKeys(
            @RequestParam(defaultValue = "100") int limit) {

        List<KeyInfo> keyInfos = new ArrayList<>();
        // Change 2: use SCAN instead of KEYS to avoid blocking Redis
        List<String> keys = scanKeys("ratelimit:*", limit);

        keys.forEach(key -> {
            Long ttl = redisTemplate.getExpire(key);
            String type = extractType(key);
            keyInfos.add(KeyInfo.builder()
                    .key(key)
                    .type(type)
                    .ttl(ttl)
                    .build());
        });

        return ResponseEntity.ok(keyInfos);
    }

    @GetMapping("/stats")
    @Operation(summary = "Get system statistics", description = "Get statistics about rate limiter usage")
    public ResponseEntity<SystemStats> getSystemStats() {
        // Change 2: use SCAN-based counts instead of KEYS
        long total  = countByPattern("ratelimit:*");
        long bucket = countByPattern("ratelimit:bucket:*");
        long sliding = countByPattern("ratelimit:sliding:*");
        long fixed  = countByPattern("ratelimit:fixed:*");
        long leaky  = countByPattern("ratelimit:leaky:*");
        long swc    = countByPattern("ratelimit:swc:*");
        long config = countByPattern("config:*");

        SystemStats stats = SystemStats.builder()
                .totalKeys(total)
                .bucketKeys(bucket)
                .slidingKeys(sliding)
                .fixedKeys(fixed)
                .leakyKeys(leaky)
                .swcKeys(swc)
                .configKeys(config)
                .build();

        return ResponseEntity.ok(stats);
    }

    @DeleteMapping("/keys/{keyPattern}")
    @Operation(summary = "Reset keys", description = "Delete keys matching pattern (use with caution)")
    public ResponseEntity<Void> resetKeys(@PathVariable String keyPattern) {
        // Change 2: use SCAN to find keys, then delete in batches
        List<String> keys = scanKeys(keyPattern, Integer.MAX_VALUE);

        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Deleted {} keys matching pattern: {}", keys.size(), keyPattern);
        }

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/keys")
    @Operation(summary = "Reset specific key", description = "Reset rate limit for a specific key")
    public ResponseEntity<Void> resetKey(@RequestParam String key) {
        rateLimitService.resetLimit(key);
        log.info("Reset rate limit for key: {}", key);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cache/clear")
    @Operation(summary = "Clear configuration cache", description = "Clear in-memory configuration cache")
    public ResponseEntity<Void> clearCache() {
        configService.reloadConfigurations();
        log.info("Configuration cache cleared");
        return ResponseEntity.ok().build();
    }

    private String extractType(String key) {
        if (key.contains(":bucket:"))  return "TOKEN_BUCKET";
        if (key.contains(":sliding:")) return "SLIDING_WINDOW";
        if (key.contains(":fixed:"))   return "FIXED_WINDOW";
        if (key.contains(":leaky:"))   return "LEAKY_BUCKET";
        if (key.contains(":swc:"))     return "SLIDING_WINDOW_COUNTER";
        return "UNKNOWN";
    }

    /**
     * SCAN-based key lookup — non-blocking, safe for production.
     * Returns at most {@code limit} keys matching the given pattern.
     */
    private List<String> scanKeys(String pattern, int limit) {
        List<String> result = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext() && result.size() < limit) {
                result.add(cursor.next());
            }
        }
        return result;
    }

    /** SCAN-based count — no KEYS command. */
    private long countByPattern(String pattern) {
        return scanKeys(pattern, Integer.MAX_VALUE).size();
    }
}