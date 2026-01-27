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

// token bucket rate limiting algorithm implementation using redis lua script
@Slf4j
@Component("tokenBucketAlgorithm")
@RequiredArgsConstructor
public class TokenBucketAlgorithm implements RateLimitAlgorithm {

    // redis template for executing lua scripts
    private final RedisTemplate<String, Object> redisTemplate;
    // compiled lua script for token bucket logic
    private RedisScript<List<Object>> tokenBucketScript;

    // loads and compiles the lua script on startup
    @PostConstruct
    public void init() throws IOException {
        // load lua script from classpath
        ClassPathResource resource = new ClassPathResource("lua/token_bucket.lua");
        // read script content as string
        String scriptContent = new String(
            resource.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        // compile script for redis execution
        @SuppressWarnings("unchecked")
        RedisScript<List<Object>> script = (RedisScript<List<Object>>) (RedisScript<?>)
            RedisScript.of(scriptContent, List.class);
        this.tokenBucketScript = script;
        log.info("Token Bucket Lua script loaded successfully");
    }

    // checks if request can proceed based on token availability
    @Override
    public RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config) {
        // record start time for latency calculation
        long startTime = System.nanoTime();
        
        try {
            // construct redis key for the bucket
            String bucketKey = "ratelimit:bucket:" + key;
            // get current timestamp in milliseconds
            long nowMillis = System.currentTimeMillis();
            
            // execute script with parameters: requested tokens, capacity, refill rate per second, current time, ttl
            List<Object> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(bucketKey),
                requestedTokens,
                config.getCapacity(),
                config.getRefillRate() / config.getRefillPeriodSeconds(),
                nowMillis,
                3600
            );

            // validate script response
            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Invalid response from Redis Lua script");
            }

            // extract results: allowed flag, remaining tokens, retry after seconds
            int allowed = ((Number) result.get(0)).intValue();
            double remaining = ((Number) result.get(1)).doubleValue();
            double retryAfter = ((Number) result.get(2)).doubleValue();

            // calculate latency in microseconds
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            
            RateLimitResponse response;
            // create allowed response
            if (allowed == 1) {
                response = RateLimitResponse.allowed(
                    (long) remaining,
                    calculateResetTime(config),
                    getAlgorithmType().name()
                );
            } else {
                // create denied response
                response = RateLimitResponse.denied(
                    (long) remaining,
                    calculateResetTime(config),
                    (long) Math.ceil(retryAfter),
                    getAlgorithmType().name()
                );
            }

            // add metadata with key and latency
            response.setMetadata(RateLimitResponse.RateLimitMetadata.builder()
                    .key(key)
                    .latencyMicros(latencyMicros)
                    .build());

            log.debug("Token Bucket check for key={}: allowed={}, remaining={}, latency={}μs",
                    key, allowed == 1, remaining, latencyMicros);

            return response;

        } catch (Exception e) {
            // handle errors and throw runtime exception
            log.error("Error in Token Bucket algorithm for key: {}", key, e);
            throw new RuntimeException("Rate limit check failed", e);
        }
    }

    // returns the algorithm type
    @Override
    public com.ratelimiter.model.RateLimitAlgorithm getAlgorithmType() {
        return com.ratelimiter.model.RateLimitAlgorithm.TOKEN_BUCKET;
    }

    // resets the token bucket for the key
    @Override
    public void reset(String key) {
        // construct redis key for the bucket
        String bucketKey = "ratelimit:bucket:" + key;
        // delete the bucket from redis
        redisTemplate.delete(bucketKey);
        log.info("Reset Token Bucket for key: {}", key);
    }

    // calculates the next reset time based on config
    private Instant calculateResetTime(RateLimitConfig config) {
        // add refill period to current time
        return Instant.now().plusSeconds(config.getRefillPeriodSeconds());
    }
}