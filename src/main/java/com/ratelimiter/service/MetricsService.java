package com.ratelimiter.service;

import com.ratelimiter.dto.RateLimitResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
// service for recording metrics related to rate limiting
public class MetricsService {

    // metrics counters and timer for rate limiting operations
    private final Counter allowedCounter;
    private final Counter deniedCounter;
    private final Counter errorCounter;
    private final Timer checkLatencyTimer;

    // initializes the metrics counters and timer
    public MetricsService(MeterRegistry registry) {
        this.allowedCounter = Counter.builder("ratelimit.checks.allowed")
                .description("Number of allowed rate limit checks")
                .register(registry);

        this.deniedCounter = Counter.builder("ratelimit.checks.denied")
                .description("Number of denied rate limit checks")
                .register(registry);

        this.errorCounter = Counter.builder("ratelimit.checks.errors")
                .description("Number of rate limit check errors")
                .register(registry);

        this.checkLatencyTimer = Timer.builder("ratelimit.check.latency")
                .description("Rate limit check latency")
                .register(registry);
    }

    // records the result of a rate limit check and its latency
    public void recordCheck(RateLimitResponse response, long latencyMicros) {
        if (response.isAllowed()) {
            allowedCounter.increment();
        } else {
            deniedCounter.increment();
        }
        
        checkLatencyTimer.record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    // records an error in rate limit checking
    public void recordError() {
        errorCounter.increment();
    }
}