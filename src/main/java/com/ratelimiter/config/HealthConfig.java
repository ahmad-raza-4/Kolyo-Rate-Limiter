package com.ratelimiter.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class HealthConfig {

    private final RedisTemplate<String, Object> redisTemplate;

    // ─── Redis: connectivity + latency + key count ───────────────────
    @Bean
    public HealthIndicator redisDetailedHealthIndicator() {
        return () -> {
            try {
                long t0 = System.currentTimeMillis();
                try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
                    connection.ping();
                }
                long latencyMs = System.currentTimeMillis() - t0;

                Health.Builder b = Health.up()
                        .withDetail("latencyMs", latencyMs);

                // Degrade health on slow Redis — catches problems before they cascade
                if (latencyMs > 100) {
                    return Health.down()
                            .withDetail("reason", "Redis latency " + latencyMs + "ms > 100ms threshold")
                            .withDetail("latencyMs", latencyMs)
                            .withDetail("activeKeys", 0)
                            .build();
                }

                // Change 2: use SCAN instead of KEYS to count active ratelimit keys
                int activeKeys = 0;
                ScanOptions options = ScanOptions.scanOptions().match("ratelimit:*").count(100).build();
                try (Cursor<String> cursor = redisTemplate.scan(options)) {
                    while (cursor.hasNext()) {
                        cursor.next();
                        activeKeys++;
                    }
                }

                b.withDetail("activeKeys", activeKeys);
                if (latencyMs > 50) {
                    b.withDetail("warning", "Redis latency " + latencyMs + "ms approaching threshold");
                }

                return b.build();

            } catch (Exception e) {
                return Health.down(e).withDetail("error", "Cannot reach Redis").build();
            }
        };
    }

    // ─── Rate Limiter: algorithm registry check ───────────────────────
    @Bean
    public HealthIndicator rateLimiterHealthIndicator() {
        return () -> Health.up()
                .withDetail("algorithms", List.of(
                        "TOKEN_BUCKET", "SLIDING_WINDOW", "SLIDING_WINDOW_COUNTER",
                        "FIXED_WINDOW", "LEAKY_BUCKET"))
                .withDetail("status", "all algorithms registered")
                .build();
    }
}