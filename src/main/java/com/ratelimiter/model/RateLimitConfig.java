package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitConfig {
    private String keyPattern;
    private RateLimitAlgorithm algorithm;
    
    // Common parameters
    private Integer capacity;
    private Double refillRate;
    private Integer refillPeriodSeconds;
    
    // Priority for pattern matching
    private Integer priority;
    
    // Metadata
    private Instant createdAt;
    private Instant updatedAt;

    public void validate() {
        if (capacity == null || capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (refillRate == null || refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (refillPeriodSeconds == null || refillPeriodSeconds <= 0) {
            throw new IllegalArgumentException("Refill period must be positive");
        }
        
        // Algorithm-specific validation
        if (algorithm == RateLimitAlgorithm.SLIDING_WINDOW) {
            // Sliding window uses capacity as max requests
            if (capacity > 10000) {
                throw new IllegalArgumentException(
                    "Sliding Window capacity should be <= 10000 for memory efficiency");
            }
        }
    }

    public String getDescription() {
        return switch (algorithm) {
            case TOKEN_BUCKET -> 
                String.format("Token Bucket: %d capacity, refills %s tokens/%ds", 
                    capacity, refillRate, refillPeriodSeconds);
            case SLIDING_WINDOW -> 
                String.format("Sliding Window: %d requests per %ds", 
                    capacity, refillPeriodSeconds);
            case FIXED_WINDOW -> 
                String.format("Fixed Window: %d requests per %ds window", 
                    capacity, refillPeriodSeconds);
            default ->
                throw new IllegalStateException("Unsupported rate limit algorithm: " + algorithm);
        };
    }
}