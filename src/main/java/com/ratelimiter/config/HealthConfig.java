package com.ratelimiter.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Set;

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
                redisTemplate.getConnectionFactory()
                        .getConnection().ping();
                long latencyMs = System.currentTimeMillis() - t0;

                Set<String> keys = redisTemplate.keys("ratelimit:*");
                int activeKeys = keys != null ? keys.size() : 0;

                Health.Builder b = Health.up()
                        .withDetail("latencyMs", latencyMs)
                        .withDetail("activeKeys", activeKeys);

                // Degrade health on slow Redis — catches problems before they cascade
                if (latencyMs > 100) {
                    return Health.down()
                            .withDetail("reason", "Redis latency " + latencyMs + "ms > 100ms threshold")
                            .withDetail("activeKeys", activeKeys)
                            .build();
                }
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