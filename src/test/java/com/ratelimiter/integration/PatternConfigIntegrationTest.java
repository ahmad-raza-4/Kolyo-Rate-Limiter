package com.ratelimiter.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PatternConfigIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        // Clear Redis
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void shouldApplyPatternConfiguration() throws Exception {
        // Configure pattern for premium users
        RateLimitConfig premiumConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(100)
                .refillRate(100.0)
                .refillPeriodSeconds(60)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/user:premium:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(premiumConfig)))
                .andExpect(status().isCreated());

        // Test that premium user gets premium limits
        RateLimitRequest request = RateLimitRequest.builder()
                .key("user:premium:12345")
                .tokens(1)
                .build();

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remainingTokens").value(99));
    }

    @Test
    void shouldUsePriorityToResolveConflicts() throws Exception {
        // Configure generic pattern
        RateLimitConfig genericConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .capacity(10)
                .refillRate(10.0)
                .refillPeriodSeconds(60)
                .priority(10)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/user:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(genericConfig)))
                .andExpect(status().isCreated());

        // Configure specific pattern (higher priority)
        RateLimitConfig specificConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(50)
                .refillRate(50.0)
                .refillPeriodSeconds(60)
                .priority(50)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/user:premium:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(specificConfig)))
                .andExpect(status().isCreated());

        // Premium user should get specific config (higher priority)
        RateLimitRequest premiumRequest = RateLimitRequest.builder()
                .key("user:premium:123")
                .tokens(1)
                .build();

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(premiumRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"))
                .andExpect(jsonPath("$.remainingTokens").value(49));

        // Regular user should get generic config
        RateLimitRequest regularRequest = RateLimitRequest.builder()
                .key("user:regular:456")
                .tokens(1)
                .build();

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(regularRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("FIXED_WINDOW"))
                .andExpect(jsonPath("$.remainingTokens").value(9));
    }

    @Test
    void shouldSupportHotReload() throws Exception {
        // Configure initial pattern
        RateLimitConfig initialConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(5)
                .refillRate(5.0)
                .refillPeriodSeconds(60)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/api:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(initialConfig)))
                .andExpect(status().isCreated());

        // Update pattern
        RateLimitConfig updatedConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                .capacity(20)
                .refillRate(20.0)
                .refillPeriodSeconds(60)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/api:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updatedConfig)))
                .andExpect(status().isCreated());

        // Reload configurations
        mockMvc.perform(post("/api/ratelimit/config/reload"))
                .andExpect(status().isOk());

        // Verify new configuration is applied
        RateLimitRequest request = RateLimitRequest.builder()
                .key("api:v1:users")
                .tokens(1)
                .build();

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("SLIDING_WINDOW"))
                .andExpect(jsonPath("$.remainingTokens").value(19));
    }

    @Test
    void shouldListAllPatterns() throws Exception {
        // Create multiple patterns
        RateLimitConfig config1 = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(10)
                .refillRate(10.0)
                .refillPeriodSeconds(60)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/user:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(config1)))
                .andExpect(status().isCreated());

        RateLimitConfig config2 = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                .capacity(20)
                .refillRate(20.0)
                .refillPeriodSeconds(60)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/api:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(config2)))
                .andExpect(status().isCreated());

        // List all patterns
        mockMvc.perform(get("/api/ratelimit/config/patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void shouldDeletePattern() throws Exception {
        // Create pattern
        RateLimitConfig config = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(10)
                .refillRate(10.0)
                .refillPeriodSeconds(60)
                .build();

        mockMvc.perform(post("/api/ratelimit/config/patterns/temp:*")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(config)))
                .andExpect(status().isCreated());

        // Delete pattern
        mockMvc.perform(delete("/api/ratelimit/config/patterns/temp:*"))
                .andExpect(status().isNoContent());

        // Verify pattern is deleted
        mockMvc.perform(get("/api/ratelimit/config/patterns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.keyPattern == 'temp:*')]").doesNotExist());
    }
}