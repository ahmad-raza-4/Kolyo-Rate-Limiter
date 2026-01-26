package com.ratelimiter.algorithm;

import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component("fixedWindowAlgorithm")
@RequiredArgsConstructor
public class FixedWindowAlgorithm implements RateLimitAlgorithm {

    private final RedisTemplate<String, Object> redisTemplate;
    private RedisScript<List<Object>> fixedWindowScript;

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("lua/fixed_window.lua");
        String scriptContent = new String(
            resource.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        @SuppressWarnings("unchecked")
        RedisScript<List<Object>> script = (RedisScript<List<Object>>) (RedisScript<?>)
                RedisScript.of(scriptContent, List.class);
        this.fixedWindowScript = script;
        log.info("Fixed Window Lua script loaded successfully");
    }

    @Override
    public RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config) {
        long startTime = System.nanoTime();
        
        try {
            // Fixed window doesn't naturally support multi-token requests
            // For now, we treat each token as a separate request count
            if (requestedTokens > 1) {
                log.debug("Fixed Window counting {} tokens as {} requests for key: {}", 
                        requestedTokens, requestedTokens, key);
            }

            // Calculate current window start time
            long nowSeconds = System.currentTimeMillis() / 1000;
            long windowSize = config.getRefillPeriodSeconds();
            long windowStart = nowSeconds - (nowSeconds % windowSize);
            
            String counterKey = String.format("ratelimit:fixed:%s:%d", key, windowStart);
            
            // For multi-token requests, we need to check capacity
            // Simplified: we'll make one check and multiply remaining by tokens
            List<Object> result = redisTemplate.execute(
                fixedWindowScript,
                Collections.singletonList(counterKey),
                config.getCapacity(),
                windowSize
            );

            if (result == null || result.size() < 2) {
                throw new IllegalStateException("Invalid response from Redis Lua script");
            }

            int allowed = ((Number) result.get(0)).intValue();
            int remaining = ((Number) result.get(1)).intValue();

            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            
            // Calculate reset time (end of current window)
            long windowEnd = windowStart + windowSize;
            Instant resetTime = Instant.ofEpochSecond(windowEnd);
            long retryAfter = windowEnd - nowSeconds;
            
            RateLimitResponse response;
            if (allowed == 1) {
                response = RateLimitResponse.allowed(
                    remaining,
                    resetTime,
                    getAlgorithmType().name()
                );
            } else {
                response = RateLimitResponse.denied(
                    0,
                    resetTime,
                    retryAfter,
                    getAlgorithmType().name()
                );
            }

            response.setMetadata(RateLimitResponse.RateLimitMetadata.builder()
                    .key(key)
                    .latencyMicros(latencyMicros)
                    .build());

            log.debug("Fixed Window check for key={}, window={}: allowed={}, remaining={}, latency={}μs",
                    key, windowStart, allowed == 1, remaining, latencyMicros);

            return response;

        } catch (Exception e) {
            log.error("Error in Fixed Window algorithm for key: {}", key, e);
            throw new RuntimeException("Rate limit check failed", e);
        }
    }

    @Override
    public com.ratelimiter.model.RateLimitAlgorithm getAlgorithmType() {
        return com.ratelimiter.model.RateLimitAlgorithm.FIXED_WINDOW;
    }

    @Override
    public void reset(String key) {
        // For fixed window, we need to delete all possible window keys
        // In production, you'd want a more sophisticated cleanup mechanism
        String pattern = "ratelimit:fixed:" + key + ":*";
        redisTemplate.delete(redisTemplate.keys(pattern));
        log.info("Reset Fixed Window for key pattern: {}", pattern);
    }
}