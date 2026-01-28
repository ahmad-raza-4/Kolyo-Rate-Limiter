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

@SpringBootTest(classes = {RedisConfig.class, SlidingWindowAlgorithm.class})
@Testcontainers
class SlidingWindowAlgorithmTest {

    @Container
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private SlidingWindowAlgorithm slidingWindowAlgorithm;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private RateLimitConfig testConfig;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        testConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .capacity(10)  // 10 requests per window
                .refillRate(10.0)
                .refillPeriodSeconds(60)  // 60-second window
                .build();
    }

    @Test
    void shouldAllowRequestWithinLimit() {
        String key = "test:sliding:1";

        RateLimitResponse response = slidingWindowAlgorithm.checkLimit(key, 1, testConfig);

        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(9);
        assertThat(response.getAlgorithm()).isEqualTo("SLIDING_WINDOW");
    }

    @Test
    void shouldDenyRequestExceedingLimit() {
        String key = "test:sliding:2";

        // Consume all allowed requests
        for (int i = 0; i < 10; i++) {
            RateLimitResponse response = slidingWindowAlgorithm.checkLimit(key, 1, testConfig);
            assertThat(response.isAllowed()).isTrue();
        }
        
        // Next request should be denied
        RateLimitResponse response = slidingWindowAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRemainingTokens()).isEqualTo(0);
    }

    @Test
    void shouldAllowRequestsAfterWindowExpires() throws InterruptedException {
        String key = "test:sliding:3";
        RateLimitConfig shortWindowConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .capacity(3)
                .refillRate(3.0)
                .refillPeriodSeconds(2)  // 2-second window
                .build();

        // Consume all requests
        for (int i = 0; i < 3; i++) {
            slidingWindowAlgorithm.checkLimit(key, 1, shortWindowConfig);
        }

        // Should be denied
        RateLimitResponse deniedResponse = slidingWindowAlgorithm.checkLimit(key, 1, shortWindowConfig);
        assertThat(deniedResponse.isAllowed()).isFalse();

        // Wait for window to expire
        Thread.sleep(2100);

        // Should be allowed again
        RateLimitResponse allowedResponse = slidingWindowAlgorithm.checkLimit(key, 1, shortWindowConfig);
        assertThat(allowedResponse.isAllowed()).isTrue();
    }

    @Test
    void shouldProvideAccurateRemainingCount() {
        String key = "test:sliding:4";

        // Make 3 requests
        for (int i = 0; i < 3; i++) {
            slidingWindowAlgorithm.checkLimit(key, 1, testConfig);
        }

        // Check remaining
        RateLimitResponse response = slidingWindowAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(6);  // 10 - 4 = 6
    }

    @Test
    void shouldHandleHighConcurrency() throws InterruptedException {
        String key = "test:sliding:5";
        int threadCount = 20;
        int requestsPerThread = 1;
        
        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < requestsPerThread; j++) {
                    slidingWindowAlgorithm.checkLimit(key, 1, testConfig);
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Verify state
        // Should have allowed only 10 requests (capacity)
        RateLimitResponse response = slidingWindowAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isFalse();
    }
}