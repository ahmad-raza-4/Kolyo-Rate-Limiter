package com.ratelimiter.algorithm;

import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;

/**
 * Interface for rate limiting algorithms.
 * Implementations must be thread-safe and support distributed execution.
 */
public interface RateLimitAlgorithm {
    
    /**
     * Check if request is allowed under rate limit.
     *
     * @param key Unique identifier for the rate limit bucket
     * @param requestedTokens Number of tokens to consume
     * @param config Rate limit configuration
     * @return Response indicating if request is allowed
     */
    RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config);
    
    /**
     * Get the algorithm type.
     *
     * @return Algorithm type enum value
     */
    com.ratelimiter.model.RateLimitAlgorithm getAlgorithmType();
    
    /**
     * Reset rate limit state for a key.
     *
     * @param key Key to reset
     */
    void reset(String key);
}