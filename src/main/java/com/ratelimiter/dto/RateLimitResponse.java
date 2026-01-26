package com.ratelimiter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateLimitResponse {
    private boolean allowed;
    private long remainingTokens;
    private Instant resetTime;
    private Long retryAfterSeconds;
    private String algorithm;
    private RateLimitMetadata metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitMetadata {
        private String key;
        private String matchedPattern;
        private long latencyMicros;
    }

    public static RateLimitResponse allowed(long remaining, Instant resetTime, String algorithm) {
        return RateLimitResponse.builder()
                .allowed(true)
                .remainingTokens(remaining)
                .resetTime(resetTime)
                .algorithm(algorithm)
                .build();
    }

    public static RateLimitResponse denied(long remaining, Instant resetTime, long retryAfter, String algorithm) {
        return RateLimitResponse.builder()
                .allowed(false)
                .remainingTokens(remaining)
                .resetTime(resetTime)
                .retryAfterSeconds(retryAfter)
                .algorithm(algorithm)
                .build();
    }
}