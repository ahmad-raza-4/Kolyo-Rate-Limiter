package com.ratelimiter.algorithm;

import com.ratelimiter.config.RedisConfig;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {RedisConfig.class, TokenBucketAlgorithm.class})
@Testcontainers
class TokenBucketAlgorithmTest {

    @Container
    @ServiceConnection
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private RateLimitConfig testConfig;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Create test configuration
        testConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(10)
                .refillRate(10.0)
                .refillPeriodSeconds(60)
                .build();
    }

    @Test
    void shouldAllowRequestWithinLimit() {
        // Given
        String key = "test:user:1";

        // When
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 1, testConfig);

        // Then
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(9);
        assertThat(response.getAlgorithm()).isEqualTo("TOKEN_BUCKET");
    }

    @Test
    void shouldDenyRequestExceedingLimit() {
        // Given
        String key = "test:user:2";

        // When - consume all tokens
        for (int i = 0; i < 10; i++) {
            tokenBucketAlgorithm.checkLimit(key, 1, testConfig);
        }
        
        // Then - next request should be denied
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRemainingTokens()).isEqualTo(0);
        assertThat(response.getRetryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void shouldRefillTokensOverTime() throws InterruptedException {
        // Given
        String key = "test:user:3";
        RateLimitConfig quickRefillConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(5)
                .refillRate(5.0)  // 5 tokens per second
                .refillPeriodSeconds(1)
                .build();

        // When - consume all tokens
        for (int i = 0; i < 5; i++) {
            tokenBucketAlgorithm.checkLimit(key, 1, quickRefillConfig);
        }

        // Wait for refill
        Thread.sleep(1100);  // 1.1 seconds

        // Then - should have refilled approximately 5 tokens
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 1, quickRefillConfig);
        assertThat(response.isAllowed()).isTrue();
    }

    @Test
    void shouldHandleMultipleTokenRequests() {
        // Given
        String key = "test:user:4";

        // When
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 5, testConfig);

        // Then
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(5);
    }

    @Test
    void shouldNotAllowMoreTokensThanCapacity() {
        // Given
        String key = "test:user:5";

        // When
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 15, testConfig);

        // Then
        assertThat(response.isAllowed()).isFalse();
    }
}