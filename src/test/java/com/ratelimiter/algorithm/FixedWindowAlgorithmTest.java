package com.ratelimiter.algorithm;

import com.ratelimiter.config.RedisConfig;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {RedisConfig.class, FixedWindowAlgorithm.class})
@Testcontainers
class FixedWindowAlgorithmTest {

    @Container
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private FixedWindowAlgorithm fixedWindowAlgorithm;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private RateLimitConfig testConfig;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        testConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .capacity(10)
                .refillRate(10.0)
                .refillPeriodSeconds(60)  // 60-second window
                .build();
    }

    @Test
    void shouldAllowRequestWithinLimit() {
        String key = "test:fixed:1";

        RateLimitResponse response = fixedWindowAlgorithm.checkLimit(key, 1, testConfig);

        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(9);
        assertThat(response.getAlgorithm()).isEqualTo("FIXED_WINDOW");
    }

    @Test
    void shouldDenyRequestExceedingLimit() {
        String key = "test:fixed:2";

        // Consume all allowed requests
        for (int i = 0; i < 10; i++) {
            RateLimitResponse response = fixedWindowAlgorithm.checkLimit(key, 1, testConfig);
            assertThat(response.isAllowed()).isTrue();
        }
        
        // Next request should be denied
        RateLimitResponse response = fixedWindowAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRetryAfterSeconds()).isNotNull();
    }

    @Test
    void shouldResetCounterAfterWindowExpires() throws InterruptedException {
        String key = "test:fixed:3";
        RateLimitConfig shortWindowConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .capacity(5)
                .refillRate(5.0)
                .refillPeriodSeconds(2)  // 2-second window
                .build();

        // Consume all requests
        for (int i = 0; i < 5; i++) {
            fixedWindowAlgorithm.checkLimit(key, 1, shortWindowConfig);
        }

        // Should be denied
        RateLimitResponse deniedResponse = fixedWindowAlgorithm.checkLimit(key, 1, shortWindowConfig);
        assertThat(deniedResponse.isAllowed()).isFalse();

        // Wait for window to expire (wait for boundary)
        Thread.sleep(2100);

        // Should be allowed in new window
        RateLimitResponse allowedResponse = fixedWindowAlgorithm.checkLimit(key, 1, shortWindowConfig);
        assertThat(allowedResponse.isAllowed()).isTrue();
        assertThat(allowedResponse.getRemainingTokens()).isEqualTo(4);
    }

    @Test
    void shouldHandleWindowBoundaryCorrectly() throws InterruptedException {
        String key = "test:fixed:4";
        RateLimitConfig config = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .capacity(3)
                .refillRate(3.0)
                .refillPeriodSeconds(3)
                .build();

        // Use up capacity in current window
        for (int i = 0; i < 3; i++) {
            fixedWindowAlgorithm.checkLimit(key, 1, config);
        }

        // Wait until we're definitely in next window
        Thread.sleep(3100);

        // Should have fresh capacity
        RateLimitResponse response = fixedWindowAlgorithm.checkLimit(key, 1, config);
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(2);
    }

    @Test
    void shouldBeMostMemoryEfficient() {
        String key = "test:fixed:5";

        // Make requests
        for (int i = 0; i < 5; i++) {
            fixedWindowAlgorithm.checkLimit(key, 1, testConfig);
        }

        // Fixed window should only have 1 key in Redis
        // (compared to Sliding Window which stores every request)
        // This is a simplified check - in production you'd measure actual memory
        assertThat(redisTemplate.keys("ratelimit:fixed:" + key + ":*"))
                .hasSize(1);
    }
}