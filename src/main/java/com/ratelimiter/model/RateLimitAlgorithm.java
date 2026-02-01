package com.ratelimiter.model;

public enum RateLimitAlgorithm {
    FIXED_WINDOW,
    SLIDING_WINDOW,
    SLIDING_WINDOW_COUNTER,
    TOKEN_BUCKET,
    LEAKY_BUCKET
}