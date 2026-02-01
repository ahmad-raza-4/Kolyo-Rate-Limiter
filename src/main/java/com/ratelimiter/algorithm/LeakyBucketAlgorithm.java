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
@Component("leakyBucketAlgorithm")
@RequiredArgsConstructor
public class LeakyBucketAlgorithm implements RateLimitAlgorithm {

    private final RedisTemplate<String, Object> redisTemplate;
    private RedisScript<List> leakyBucketScript;

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("lua/leaky_bucket.lua");
        String scriptContent = new String(
            resource.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        this.leakyBucketScript = RedisScript.of(scriptContent, List.class);
        log.info("Leaky Bucket Lua script loaded successfully");
    }

    @Override
    public RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config) {
        long startTime = System.nanoTime();
        
        try {
            String bucketKey = "ratelimit:leaky:" + key;
            long nowMillis = System.currentTimeMillis();
            
            // Leak rate in requests per second
            double leakRatePerSecond = config.getRefillRate() / config.getRefillPeriodSeconds();
            
            List<Object> result = redisTemplate.execute(
                leakyBucketScript,
                Collections.singletonList(bucketKey),
                config.getCapacity(),     // max queue size
                leakRatePerSecond,        // leak rate (req/sec)
                nowMillis,
                requestedTokens,
                3600                      // TTL
            );

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Invalid response from Redis Lua script");
            }

            int allowed = ((Number) result.get(0)).intValue();
            int queueSize = ((Number) result.get(1)).intValue();
            double waitTimeSeconds = ((Number) result.get(2)).doubleValue();

            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            
            RateLimitResponse response;
            Instant resetTime = Instant.now().plusSeconds(config.getRefillPeriodSeconds());
            
            if (allowed == 1) {
                response = RateLimitResponse.allowed(
                    Math.max(0, config.getCapacity() - queueSize),  // remaining capacity
                    resetTime,
                    getAlgorithmType().name()
                );
                
                // Add wait time to metadata if request is queued
                if (waitTimeSeconds > 0) {
                    log.debug("Request queued for key={}, wait_time={}s", key, waitTimeSeconds);
                }
                
            } else {
                long retryAfterSeconds = (long) Math.ceil(waitTimeSeconds);
                if (retryAfterSeconds <= 0 || Double.isNaN(waitTimeSeconds) || Double.isInfinite(waitTimeSeconds)) {
                    retryAfterSeconds = 1;
                }

                response = RateLimitResponse.denied(
                    Math.max(0, config.getCapacity() - queueSize),
                    resetTime,
                    retryAfterSeconds,
                    getAlgorithmType().name()
                );
            }

            response.setMetadata(RateLimitResponse.RateLimitMetadata.builder()
                    .key(key)
                    .latencyMicros(latencyMicros)
                    .build());

            log.debug("Leaky Bucket check for key={}: allowed={}, queue_size={}, " +
                      "wait_time={}s, latency={}μs",
                    key, allowed == 1, queueSize, String.format("%.2f", waitTimeSeconds), 
                    latencyMicros);

            return response;

        } catch (Exception e) {
            log.error("Error in Leaky Bucket algorithm for key: {}", key, e);
            throw new RuntimeException("Rate limit check failed", e);
        }
    }

    @Override
    public com.ratelimiter.model.RateLimitAlgorithm getAlgorithmType() {
        return com.ratelimiter.model.RateLimitAlgorithm.LEAKY_BUCKET;
    }

    @Override
    public void reset(String key) {
        String bucketKey = "ratelimit:leaky:" + key;
        redisTemplate.delete(bucketKey);
        log.info("Reset Leaky Bucket for key: {}", key);
    }
}