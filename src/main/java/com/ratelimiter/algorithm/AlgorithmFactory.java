package com.ratelimiter.algorithm;

import com.ratelimiter.model.RateLimitAlgorithm;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// factory for creating and retrieving rate limit algorithms
@Component
public class AlgorithmFactory {

    // list of all available rate limit algorithms
    private final List<com.ratelimiter.algorithm.RateLimitAlgorithm> algorithms;
    // map of algorithm types to their implementations
    private final Map<RateLimitAlgorithm, com.ratelimiter.algorithm.RateLimitAlgorithm> algorithmMap;

    public AlgorithmFactory(List<com.ratelimiter.algorithm.RateLimitAlgorithm> algorithms) {
        this.algorithms = algorithms;
        this.algorithmMap = new EnumMap<>(RateLimitAlgorithm.class);
        // populate map with algorithm type as key
        for (com.ratelimiter.algorithm.RateLimitAlgorithm algorithm : algorithms) {
            this.algorithmMap.put(algorithm.getAlgorithmType(), algorithm);
        }
    }

    // retrieves algorithm implementation by type
    public com.ratelimiter.algorithm.RateLimitAlgorithm getAlgorithm(RateLimitAlgorithm type) {
        // get algorithm from map
        com.ratelimiter.algorithm.RateLimitAlgorithm algorithm = algorithmMap.get(type);
        // throw exception if algorithm not found
        if (algorithm == null) {
            throw new IllegalArgumentException("Unsupported algorithm: " + type);
        }
        return algorithm;
    }
}