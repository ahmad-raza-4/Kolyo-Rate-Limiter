package com.ratelimiter.service;

import com.ratelimiter.algorithm.AlgorithmFactory;
import com.ratelimiter.algorithm.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// main service for handling rate limit checks and resets
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    // factory for retrieving rate limit algorithms
    private final AlgorithmFactory algorithmFactory;
    // service for retrieving rate limit configurations
    private final ConfigService configService;
    // service for recording metrics
    private final MetricsService metricsService;

    @org.springframework.beans.factory.annotation.Value("${ratelimiter.fail-open:true}")
    private boolean failOpen;

    // checks rate limit for the given request
    public RateLimitResponse checkLimit(RateLimitRequest request) {
        // record start time for latency calculation
        long startTime = System.nanoTime();

        try {
            // retrieve configuration for the key
            RateLimitConfig config = configService.getConfig(request.getKey());

            // get the appropriate algorithm implementation
            RateLimitAlgorithm algorithm = algorithmFactory.getAlgorithm(config.getAlgorithm());

            // execute the rate limit check
            RateLimitResponse response = algorithm.checkLimit(
                    request.getKey(),
                    request.getTokens(),
                    config);

            // record metrics for the check
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            metricsService.recordCheck(response, latencyMicros, config.getAlgorithm().name());

            return response;

        } catch (Exception e) {
            // log error and record error metrics
            log.error("Error processing rate limit check for key: {}", request.getKey(), e);
            metricsService.recordError();
            if (failOpen) {
                // fail open: allow request on error
                return RateLimitResponse.builder()
                        .allowed(true)
                        .remainingTokens(-1)
                        .build();
            }
            // fail closed: ask client to retry later to avoid overload
            return RateLimitResponse.builder()
                    .allowed(false)
                    .remainingTokens(0)
                    .retryAfterSeconds(60L)
                    .build();
        }
    }

    // resets the rate limit for the given key
    public void resetLimit(String key) {
        try {
            // retrieve configuration for the key
            RateLimitConfig config = configService.getConfig(key);
            // get the appropriate algorithm implementation
            RateLimitAlgorithm algorithm = algorithmFactory.getAlgorithm(config.getAlgorithm());
            // reset the rate limit
            algorithm.reset(key);
        } catch (Exception e) {
            // log error and record error metrics for reset failures
            log.error("Error resetting rate limit for key: {}", key, e);
            metricsService.recordError();
        }
    }
}