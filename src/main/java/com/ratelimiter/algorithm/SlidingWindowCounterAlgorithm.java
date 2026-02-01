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
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component("slidingWindowCounterAlgorithm")
@RequiredArgsConstructor
public class SlidingWindowCounterAlgorithm implements RateLimitAlgorithm {

    private final RedisTemplate<String, Object> redisTemplate;
    private RedisScript<List> slidingWindowCounterScript;

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource = new ClassPathResource("lua/sliding_window_counter.lua");
        String scriptContent = new String(
                resource.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        this.slidingWindowCounterScript = RedisScript.of(scriptContent, List.class);
        log.info("Sliding Window Counter Lua script loaded successfully");
    }

    @Override
    public RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config) {
        long startTime = System.nanoTime();

        try {
            long nowSeconds = System.currentTimeMillis() / 1000;
            long windowSize = config.getRefillPeriodSeconds();

            // Calculate current and previous window boundaries
            long currentWindowStart = nowSeconds - (nowSeconds % windowSize);
            long previousWindowStart = currentWindowStart - windowSize;

            String currentKey = String.format("ratelimit:swc:%s:%d", key, currentWindowStart);
            String previousKey = String.format("ratelimit:swc:%s:%d", key, previousWindowStart);

            List<Object> result = redisTemplate.execute(
                    slidingWindowCounterScript,
                    Arrays.asList(currentKey, previousKey),
                    config.getCapacity(),
                    windowSize,
                    nowSeconds,
                    requestedTokens);

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Invalid response from Redis Lua script");
            }

            int allowed = ((Number) result.get(0)).intValue();
            double weightedCount = ((Number) result.get(1)).doubleValue();
            int currentCount = ((Number) result.get(2)).intValue();

            long latencyMicros = (System.nanoTime() - startTime) / 1000;

            // Calculate reset time (end of current window)
            long windowEnd = currentWindowStart + windowSize;
            Instant resetTime = Instant.ofEpochSecond(windowEnd);
            long retryAfter = windowEnd - nowSeconds;

            RateLimitResponse response;
            long remaining = config.getCapacity() - (long) Math.ceil(weightedCount);

            if (allowed == 1) {
                response = RateLimitResponse.allowed(
                        Math.max(0, remaining),
                        resetTime,
                        getAlgorithmType().name());
            } else {
                response = RateLimitResponse.denied(
                        0,
                        resetTime,
                        retryAfter,
                        getAlgorithmType().name());
            }

            response.setMetadata(RateLimitResponse.RateLimitMetadata.builder()
                    .key(key)
                    .latencyMicros(latencyMicros)
                    .build());

            log.debug("Sliding Window Counter check for key={}: allowed={}, " +
                    "weighted_count={}, current_count={}, tokens={}, latency={}μs",
                    key, allowed == 1, String.format("%.2f", weightedCount),
                    currentCount, requestedTokens, latencyMicros);

            return response;

        } catch (Exception e) {
            log.error("Error in Sliding Window Counter algorithm for key: {}", key, e);
            throw new RuntimeException("Rate limit check failed", e);
        }
    }

    @Override
    public com.ratelimiter.model.RateLimitAlgorithm getAlgorithmType() {
        return com.ratelimiter.model.RateLimitAlgorithm.SLIDING_WINDOW_COUNTER;
    }

    @Override
    public void reset(String key) {
        String pattern = "ratelimit:swc:" + key + ":*";
        redisTemplate.delete(redisTemplate.keys(pattern));
        log.info("Reset Sliding Window Counter for key pattern: {}", pattern);
    }
}