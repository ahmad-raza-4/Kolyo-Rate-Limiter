package com.ratelimiter.service;

import com.ratelimiter.algorithm.AlgorithmFactory;
import com.ratelimiter.algorithm.RateLimitAlgorithm;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
// service for running performance benchmarks on rate limiting algorithms
public class BenchmarkService {

    // factory for retrieving algorithm implementations
    private final AlgorithmFactory algorithmFactory;
    // redis template for clearing keys between benchmarks
    private final RedisTemplate<String, Object> redisTemplate;

    // request model for benchmark configuration
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BenchmarkRequest {
        private com.ratelimiter.model.RateLimitAlgorithm algorithm;
        private int capacity;
        private double refillRate;
        private int refillPeriodSeconds;
        private int totalRequests;
        private int concurrentThreads;
        private int durationSeconds;
    }

    // result model for benchmark outcomes
    @Data
    @Builder
    public static class BenchmarkResult {
        private String algorithm;
        private long totalRequests;
        private long allowedRequests;
        private long deniedRequests;
        private double throughputRps;
        private LatencyStats latency;
        private long durationMs;
        private double errorRate;

        // latency statistics model
        @Data
        @Builder
        public static class LatencyStats {
            private long minMicros;
            private long maxMicros;
            private double avgMicros;
            private long p50Micros;
            private long p95Micros;
            private long p99Micros;
        }
    }

    // runs performance benchmark for specified algorithm
    public BenchmarkResult runBenchmark(BenchmarkRequest request) {
        // log benchmark start
        log.info("Starting benchmark for algorithm: {}", request.getAlgorithm());
        
        // clear existing keys for clean benchmark
        clearAlgorithmKeys(request.getAlgorithm());
        
        // build rate limit configuration
        RateLimitConfig config = RateLimitConfig.builder()
                .algorithm(request.getAlgorithm())
                .capacity(request.getCapacity())
                .refillRate(request.getRefillRate())
                .refillPeriodSeconds(request.getRefillPeriodSeconds())
                .build();

        // get algorithm implementation
        RateLimitAlgorithm algorithm = algorithmFactory.getAlgorithm(request.getAlgorithm());

        // initialize tracking variables
        List<Long> latencies = new ArrayList<>();
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger denied = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger total = new AtomicInteger(0);

        // create thread pool for concurrent requests
        ExecutorService executor = Executors.newFixedThreadPool(request.getConcurrentThreads());
        CountDownLatch latch = new CountDownLatch(request.getTotalRequests());

        // record benchmark start time
        long startTime = System.currentTimeMillis();

        // submit benchmark requests
        for (int i = 0; i < request.getTotalRequests(); i++) {
            int requestNum = i;
            executor.submit(() -> {
                try {
                    // construct benchmark key
                    String key = "benchmark:" + request.getAlgorithm().name().toLowerCase();
                    
                    // measure request latency
                    long reqStart = System.nanoTime();
                    RateLimitResponse response = algorithm.checkLimit(key, 1, config);
                    long reqEnd = System.nanoTime();
                    
                    // calculate latency in microseconds
                    long latencyMicros = (reqEnd - reqStart) / 1000;
                    synchronized (latencies) {
                        latencies.add(latencyMicros);
                    }
                    
                    // update counters
                    if (response.isAllowed()) {
                        allowed.incrementAndGet();
                    } else {
                        denied.incrementAndGet();
                    }
                    
                } catch (Exception e) {
                    // log errors but continue benchmark
                    log.error("Error in benchmark request {}: {}", requestNum, e.getMessage());
                    errors.incrementAndGet();
                } finally {
                    total.incrementAndGet();
                    // ensure latch is decremented
                    latch.countDown();
                }
            });
        }

        // wait for all requests to complete
        try {
            latch.await(request.getDurationSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("Benchmark interrupted", e);
            Thread.currentThread().interrupt();
        }

        // shutdown executor
        executor.shutdown();
        // record benchmark end time
        long endTime = System.currentTimeMillis();
        long durationMs = endTime - startTime;

        // calculate latency statistics
        latencies.sort(Long::compareTo);
        LongSummaryStatistics stats = latencies.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        // build latency stats
        BenchmarkResult.LatencyStats latencyStats = BenchmarkResult.LatencyStats.builder()
                .minMicros(stats.getMin())
                .maxMicros(stats.getMax())
                .avgMicros(stats.getAverage())
                .p50Micros(percentile(latencies, 0.50))
                .p95Micros(percentile(latencies, 0.95))
                .p99Micros(percentile(latencies, 0.99))
                .build();

        // calculate throughput and error rate
        double throughputRps = (total.get() * 1000.0) / durationMs;
        double errorRate = errors.get() / (double) total.get();

        // build final result
        BenchmarkResult result = BenchmarkResult.builder()
                .algorithm(request.getAlgorithm().name())
                .totalRequests(total.get())
                .allowedRequests(allowed.get())
                .deniedRequests(denied.get())
                .throughputRps(throughputRps)
                .latency(latencyStats)
                .durationMs(durationMs)
                .errorRate(errorRate)
                .build();

        // log benchmark completion
        log.info("Benchmark completed: {} RPS, P95: {}μs, P99: {}μs",
                String.format("%.2f", throughputRps),
                latencyStats.getP95Micros(),
                latencyStats.getP99Micros());

        return result;
    }

    // calculates percentile from sorted latency list
    private long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    // clears redis keys for specified algorithm before benchmark
    private void clearAlgorithmKeys(com.ratelimiter.model.RateLimitAlgorithm algorithm) {
        String pattern = "ratelimit:" + algorithm.name().toLowerCase() + ":*";
        redisTemplate.delete(redisTemplate.keys(pattern));
    }
}