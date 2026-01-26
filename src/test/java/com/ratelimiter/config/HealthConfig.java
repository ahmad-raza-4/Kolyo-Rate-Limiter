package com.ratelimiter.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@RequiredArgsConstructor
public class HealthConfig {

    private final RedisTemplate<String, Object> redisTemplate;

    @Bean
    public HealthIndicator rateLimiterHealthIndicator() {
        return () -> {
            try {
                // Test Redis connectivity
                redisTemplate.getConnectionFactory()
                    .getConnection()
                    .serverCommands()
                    .ping();
                
                return Health.up()
                        .withDetail("redis", "connected")
                        .build();
            } catch (Exception e) {
                return Health.down()
                        .withDetail("redis", "disconnected")
                        .withException(e)
                        .build();
            }
        };
    }
}