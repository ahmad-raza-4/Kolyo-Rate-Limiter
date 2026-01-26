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
    
    // Token Bucket parameters
    private Integer capacity;
    private Double refillRate;
    private Integer refillPeriodSeconds;
    
    // Priority for pattern matching (higher = more specific)
    private Integer priority;
    
    // Metadata
    private Instant createdAt;
    private Instant updatedAt;

    // Validation
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
    }
}