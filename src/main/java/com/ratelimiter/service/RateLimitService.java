package com.ratelimiter.service;

import com.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final TokenBucketAlgorithm tokenBucketAlgorithm;
    private final ConfigService configService;
    private final MetricsService metricsService;

    public RateLimitResponse checkLimit(RateLimitRequest request) {
        long startTime = System.nanoTime();
        
        try {
            // Get configuration for this key
            RateLimitConfig config = configService.getConfig(request.getKey());
            
            // Execute rate limit check (only Token Bucket for Phase 1)
            RateLimitResponse response = tokenBucketAlgorithm.checkLimit(
                request.getKey(),
                request.getTokens(),
                config
            );

            // Record metrics
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            metricsService.recordCheck(response, latencyMicros);

            return response;

        } catch (Exception e) {
            log.error("Error processing rate limit check for key: {}", request.getKey(), e);
            // Fail open: allow request on error but log it
            metricsService.recordError();
            return RateLimitResponse.builder()
                    .allowed(true)
                    .remainingTokens(-1)
                    .build();
        }
    }
}