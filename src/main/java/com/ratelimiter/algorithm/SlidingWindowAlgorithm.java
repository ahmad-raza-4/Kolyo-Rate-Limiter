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
import java.util.UUID;

// sliding window rate limiting algorithm implementation using redis lua script
@Slf4j
@Component("slidingWindowAlgorithm")
@RequiredArgsConstructor
public class SlidingWindowAlgorithm implements RateLimitAlgorithm {

    // redis template for executing lua scripts
    private final RedisTemplate<String, Object> redisTemplate;
    // compiled lua script for sliding window logic
    private RedisScript<List> slidingWindowScript;

    // loads and compiles the lua script on startup
    @PostConstruct
    public void init() throws IOException {
        // load lua script from classpath
        ClassPathResource resource = new ClassPathResource("lua/sliding_window.lua");
        // read script content as string
        String scriptContent = new String(
            resource.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        // compile script for redis execution
        this.slidingWindowScript = RedisScript.of(scriptContent, List.class);
        log.info("Sliding Window Lua script loaded successfully");
    }

    // checks if request can proceed based on sliding window
    @Override
    public RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config) {
        // record start time for latency calculation
        long startTime = System.nanoTime();
        
        try {
            // sliding window doesn't support multi-token requests efficiently
            if (requestedTokens > 1) {
                log.warn("sliding window algorithm doesn't support multi-token requests efficiently. " +
                        "processing as {} individual requests for key: {}", requestedTokens, key);
            }

            // construct redis key for the sliding window
            String windowKey = "ratelimit:sliding:" + key;
            // get current timestamp in milliseconds
            long nowMillis = System.currentTimeMillis();
            // calculate window size in milliseconds
            long windowSizeMillis = config.getRefillPeriodSeconds() * 1000L;
            // generate unique request id
            String requestId = UUID.randomUUID().toString();
            
            // execute script with parameters: capacity, window size, current time, request id, ttl
            List<Object> result = redisTemplate.execute(
                slidingWindowScript,
                Collections.singletonList(windowKey),
                config.getCapacity(),  // max requests in window
                windowSizeMillis,
                nowMillis,
                requestId,
                config.getRefillPeriodSeconds() + 60  // ttl slightly longer than window
            );

            // validate script response
            if (result == null || result.size() < 2) {
                throw new IllegalStateException("invalid response from redis lua script");
            }

            // extract results: allowed flag, remaining requests
            int allowed = ((Number) result.get(0)).intValue();
            int remaining = ((Number) result.get(1)).intValue();

            // calculate latency in microseconds
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            
            RateLimitResponse response;
            // calculate reset time based on config
            Instant resetTime = Instant.now().plusSeconds(config.getRefillPeriodSeconds());
            
            // create allowed response
            if (allowed == 1) {
                response = RateLimitResponse.allowed(
                    remaining,
                    resetTime,
                    getAlgorithmType().name()
                );
            } else {
                // create denied response with window size as retry-after
                response = RateLimitResponse.denied(
                    0,
                    resetTime,
                    config.getRefillPeriodSeconds().longValue(),
                    getAlgorithmType().name()
                );
            }

            // add metadata with key and latency
            response.setMetadata(RateLimitResponse.RateLimitMetadata.builder()
                    .key(key)
                    .latencyMicros(latencyMicros)
                    .build());

            log.debug("sliding window check for key={}: allowed={}, remaining={}, latency={}μs",
                    key, allowed == 1, remaining, latencyMicros);

            return response;

        } catch (Exception e) {
            // handle errors and throw runtime exception
            log.error("error in sliding window algorithm for key: {}", key, e);
            throw new RuntimeException("rate limit check failed", e);
        }
    }

    // returns the algorithm type
    @Override
    public com.ratelimiter.model.RateLimitAlgorithm getAlgorithmType() {
        return com.ratelimiter.model.RateLimitAlgorithm.SLIDING_WINDOW;
    }

    // resets the sliding window for the key
    @Override
    public void reset(String key) {
        // construct redis key for the sliding window
        String windowKey = "ratelimit:sliding:" + key;
        // delete the window from redis
        redisTemplate.delete(windowKey);
        log.info("reset sliding window for key: {}", key);
    }
}