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

@SpringBootTest(classes = {RedisConfig.class, LeakyBucketAlgorithm.class})
@Testcontainers
class LeakyBucketAlgorithmTest {

    @Container
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private LeakyBucketAlgorithm leakyBucketAlgorithm;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private RateLimitConfig testConfig;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Leaky bucket: capacity 10, leaks at 5 req/sec
        testConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.LEAKY_BUCKET)
                .capacity(10)
                .refillRate(5.0)
                .refillPeriodSeconds(1)
                .build();
    }

    @Test
    void shouldAllowRequestWhenQueueNotFull() {
        String key = "test:leaky:1";

        RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 1, testConfig);

        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getAlgorithm()).isEqualTo("LEAKY_BUCKET");
        // After adding 1 request, 9 slots remain
        assertThat(response.getRemainingTokens()).isEqualTo(9);
    }

    @Test
    void shouldDenyRequestWhenQueueFull() {
        String key = "test:leaky:2";

        // Fill the queue to capacity (10 requests)
        for (int i = 0; i < 10; i++) {
            RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 1, testConfig);
            assertThat(response.isAllowed()).isTrue();
        }

        // 11th request should be denied
        RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRetryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void shouldLeakAtConstantRate() throws InterruptedException {
        String key = "test:leaky:3";
        RateLimitConfig config = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.LEAKY_BUCKET)
                .capacity(5)
                .refillRate(2.0)  // 2 requests per second
                .refillPeriodSeconds(1)
                .build();

        // Fill queue
        for (int i = 0; i < 5; i++) {
            leakyBucketAlgorithm.checkLimit(key, 1, config);
        }

        // Queue is full, next should be denied
        RateLimitResponse deniedResponse = leakyBucketAlgorithm.checkLimit(key, 1, config);
        assertThat(deniedResponse.isAllowed()).isFalse();

        // Wait 1 second (should leak 2 requests)
        Thread.sleep(1100);

        // Now we should be able to add 2 more requests
        RateLimitResponse response1 = leakyBucketAlgorithm.checkLimit(key, 1, config);
        assertThat(response1.isAllowed()).isTrue();

        RateLimitResponse response2 = leakyBucketAlgorithm.checkLimit(key, 1, config);
        assertThat(response2.isAllowed()).isTrue();

        // 3rd should still be denied (queue full again)
        RateLimitResponse response3 = leakyBucketAlgorithm.checkLimit(key, 1, config);
        assertThat(response3.isAllowed()).isFalse();
    }

    @Test
    void shouldProvideConstantOutputRate() {
        String key = "test:leaky:4";
        
        // Add multiple requests at once
        for (int i = 0; i < 5; i++) {
            leakyBucketAlgorithm.checkLimit(key, 1, testConfig);
        }

        // All are "allowed" but queued
        // They will be processed at leak rate (5 req/sec)
        // This is the key difference from Token Bucket
        RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isTrue();
        
        // Remaining capacity should account for queued items
        assertThat(response.getRemainingTokens()).isLessThan(10);
    }

    @Test
    void shouldHandleMultiTokenRequests() {
        String key = "test:leaky:5";

        // Request 3 tokens at once
        RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 3, testConfig);

        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(7);  // 10 - 3 = 7
    }

    @Test
    void shouldRejectRequestExceedingCapacity() {
        String key = "test:leaky:6";

        // Try to add more than capacity at once
        RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 15, testConfig);

        assertThat(response.isAllowed()).isFalse();
    }

    @Test
    void shouldNotAllowBursts() throws InterruptedException {
        String key = "test:leaky:7";
        RateLimitConfig config = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.LEAKY_BUCKET)
                .capacity(3)
                .refillRate(1.0)  // 1 request per second
                .refillPeriodSeconds(1)
                .build();

        // Fill queue
        for (int i = 0; i < 3; i++) {
            leakyBucketAlgorithm.checkLimit(key, 1, config);
        }

        // Wait 3 seconds (should leak all 3 requests)
        Thread.sleep(3100);

        // Even though queue is empty, we can't burst more than capacity
        for (int i = 0; i < 3; i++) {
            RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 1, config);
            assertThat(response.isAllowed()).isTrue();
        }

        // 4th request should be denied (no burst allowance)
        RateLimitResponse response = leakyBucketAlgorithm.checkLimit(key, 1, config);
        assertThat(response.isAllowed()).isFalse();
    }
}