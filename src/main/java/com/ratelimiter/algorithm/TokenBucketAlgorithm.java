package com.ratelimiter.algorithm;

import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketAlgorithm {

    private final RedisTemplate<String, Object> redisTemplate;
    private RedisScript<List> tokenBucketScript;

    @Value("${ratelimiter.performance.detailed-logging:false}")
    private boolean detailedLogging;

    @PostConstruct
    public void init() throws IOException {
        // Load Lua script
        ClassPathResource resource = new ClassPathResource("lua/token_bucket.lua");
        String scriptContent = new String(
            resource.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        this.tokenBucketScript = RedisScript.of(scriptContent, List.class);
        log.info("Token Bucket Lua script loaded successfully");
    }

    public RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config) {
        long startTime = System.nanoTime();
        
        try {
            String bucketKey = "ratelimit:bucket:" + key;
            long nowMillis = System.currentTimeMillis();
            
            // Execute Lua script
            List<Object> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(bucketKey),
                requestedTokens,
                config.getCapacity(),
                config.getRefillRate() / config.getRefillPeriodSeconds(), // tokens per second
                nowMillis,
                3600  // TTL: 1 hour
            );

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Invalid response from Redis Lua script");
            }

            // Parse results
            int allowed = ((Number) result.get(0)).intValue();
            double remaining = ((Number) result.get(1)).doubleValue();
            double retryAfter = ((Number) result.get(2)).doubleValue();

            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            
            RateLimitResponse response;
            if (allowed == 1) {
                response = RateLimitResponse.allowed(
                    (long) remaining,
                    calculateResetTime(config),
                    "TOKEN_BUCKET"
                );
            } else {
                response = RateLimitResponse.denied(
                    (long) remaining,
                    calculateResetTime(config),
                    (long) Math.ceil(retryAfter),
                    "TOKEN_BUCKET"
                );
            }

            // Add metadata
            response.setMetadata(RateLimitResponse.RateLimitMetadata.builder()
                    .key(key)
                    .latencyMicros(latencyMicros)
                    .build());

                if (detailedLogging && log.isDebugEnabled()) {
                log.debug("Rate limit check for key={}: allowed={}, remaining={}, latency={}μs",
                    key, allowed == 1, remaining, latencyMicros);
                }

            return response;

        } catch (Exception e) {
            log.error("Error checking rate limit for key: {}", key, e);
            throw new RuntimeException("Rate limit check failed", e);
        }
    }

    private Instant calculateResetTime(RateLimitConfig config) {
        return Instant.now().plusSeconds(config.getRefillPeriodSeconds());
    }
}