package com.ratelimiter.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MultiAlgorithmIntegrationTest {

        @Container
        private static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
                        .withExposedPorts(6379);

        @DynamicPropertySource
        static void redisProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.data.redis.host", redis::getHost);
                registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        }

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Test
        void shouldWorkWithTokenBucketAlgorithm() throws Exception {
                String key = "test:token:bucket:1";

                // Configure Token Bucket
                RateLimitConfig config = RateLimitConfig.builder()
                                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                                .capacity(5)
                                .refillRate(5.0)
                                .refillPeriodSeconds(60)
                                .build();

                mockMvc.perform(post("/api/ratelimit/config/keys/" + key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(config)))
                                .andExpect(status().isCreated());

                // Test rate limiting
                RateLimitRequest request = RateLimitRequest.builder()
                                .key(key)
                                .tokens(1)
                                .build();

                // Should allow 5 requests
                for (int i = 0; i < 5; i++) {
                        mockMvc.perform(post("/api/ratelimit/check")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.allowed").value(true))
                                        .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"));
                }

                // 6th request should be denied
                mockMvc.perform(post("/api/ratelimit/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isTooManyRequests())
                                .andExpect(jsonPath("$.allowed").value(false));
        }

        @Test
        void shouldWorkWithSlidingWindowAlgorithm() throws Exception {
                String key = "test:sliding:window:1";

                // Configure Sliding Window
                RateLimitConfig config = RateLimitConfig.builder()
                                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                                .capacity(3)
                                .refillRate(3.0)
                                .refillPeriodSeconds(5)
                                .build();

                mockMvc.perform(post("/api/ratelimit/config/keys/" + key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(config)))
                                .andExpect(status().isCreated());

                RateLimitRequest request = RateLimitRequest.builder()
                                .key(key)
                                .tokens(1)
                                .build();

                // Should allow 3 requests
                for (int i = 0; i < 3; i++) {
                        mockMvc.perform(post("/api/ratelimit/check")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.algorithm").value("SLIDING_WINDOW"));
                }

                // 4th request should be denied
                mockMvc.perform(post("/api/ratelimit/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isTooManyRequests());
        }

        @Test
        void shouldWorkWithFixedWindowAlgorithm() throws Exception {
                String key = "test:fixed:window:1";

                // Configure Fixed Window
                RateLimitConfig config = RateLimitConfig.builder()
                                .algorithm(RateLimitAlgorithm.FIXED_WINDOW)
                                .capacity(4)
                                .refillRate(4.0)
                                .refillPeriodSeconds(10)
                                .build();

                mockMvc.perform(post("/api/ratelimit/config/keys/" + key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(config)))
                                .andExpect(status().isCreated());

                RateLimitRequest request = RateLimitRequest.builder()
                                .key(key)
                                .tokens(1)
                                .build();

                // Should allow 4 requests
                for (int i = 0; i < 4; i++) {
                        mockMvc.perform(post("/api/ratelimit/check")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(request)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.algorithm").value("FIXED_WINDOW"));
                }

                // 5th request should be denied
                mockMvc.perform(post("/api/ratelimit/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isTooManyRequests());
        }

        @Test
        void shouldSwitchBetweenAlgorithms() throws Exception {
                String key = "test:switch:algorithms";

                // Start with Token Bucket
                RateLimitConfig tokenBucket = RateLimitConfig.builder()
                                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                                .capacity(10)
                                .refillRate(10.0)
                                .refillPeriodSeconds(60)
                                .build();

                mockMvc.perform(post("/api/ratelimit/config/keys/" + key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(tokenBucket)))
                                .andExpect(status().isCreated());

                RateLimitRequest request = RateLimitRequest.builder()
                                .key(key)
                                .tokens(1)
                                .build();

                // Make request with Token Bucket
                mockMvc.perform(post("/api/ratelimit/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"));

                // Switch to Sliding Window
                RateLimitConfig slidingWindow = RateLimitConfig.builder()
                                .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                                .capacity(5)
                                .refillRate(5.0)
                                .refillPeriodSeconds(30)
                                .build();

                mockMvc.perform(post("/api/ratelimit/config/keys/" + key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(slidingWindow)))
                                .andExpect(status().isCreated());

                // Make request with Sliding Window
                mockMvc.perform(post("/api/ratelimit/check")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.algorithm").value("SLIDING_WINDOW"));
        }
}