package com.ratelimiter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.service.BenchmarkService.BenchmarkResult;
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
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class PerformanceBaselineService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper mapper;

    private static final String PREFIX = "perf:baseline:";
    private static final int MAX_HISTORY = 10;
    private static final double LATENCY_REGRESSION_PCT = 0.20;
    private static final double THROUGHPUT_REGRESSION_PCT = 0.15;

    public void store(String testName, BenchmarkResult result) {
        try {
            String key = PREFIX + testName;
            redisTemplate.opsForList().leftPush(key, mapper.writeValueAsString(result));
            redisTemplate.opsForList().trim(key, 0, MAX_HISTORY - 1);
            redisTemplate.expire(key, 30, TimeUnit.DAYS);
        } catch (Exception e) {
            log.error("Failed to store baseline: {}", testName, e);
        }
    }

    public List<BenchmarkResult> getHistory(String testName) {
        List<BenchmarkResult> history = new ArrayList<>();
        try {
            List<Object> raw = redisTemplate.opsForList().range(PREFIX + testName, 0, -1);
            if (raw != null) {
                for (Object item : raw) {
                    history.add(mapper.readValue((String) item, BenchmarkResult.class));
                }
            }
        } catch (Exception e) {
            log.error("Failed to load history: {}", testName, e);
        }
        return history;
    }

    public RegressionReport analyze(String testName, BenchmarkResult latest) {
        List<BenchmarkResult> history = getHistory(testName);
        if (history.isEmpty()) {
            return RegressionReport.builder()
                    .testName(testName).status("BASELINE")
                    .message("No previous baseline — this run becomes the baseline.")
                    .build();
        }

        BenchmarkResult prev = history.get(0);

        // Null safety and division by zero protection
        Long latestP95 = null;
        Long prevP95 = null;
        if (latest.getLatency() != null) {
            latestP95 = latest.getLatency().getP95Micros();
        }
        if (prev.getLatency() != null) {
            prevP95 = prev.getLatency().getP95Micros();
        }

        double latDelta = 0.0;
        if (latestP95 != null && prevP95 != null && prevP95 != 0L) {
            latDelta = (double) (latestP95 - prevP95) / prevP95;
        } else {
            log.warn("Previous baseline P95 latency is null or non-positive for test '{}'; " +
                    "cannot compute relative latency delta, treating as 0.", testName);
        }

        double tpDelta = 0.0;
        if (prev.getThroughputRps() > 0 && latest.getThroughputRps() >= 0) {
            tpDelta = (prev.getThroughputRps() - latest.getThroughputRps())
                    / prev.getThroughputRps();
        } else {
            log.warn("Previous baseline throughput is non-positive for test '{}'; " +
                    "cannot compute relative throughput delta, treating as 0.", testName);
        }

        boolean latRegressed = latDelta > LATENCY_REGRESSION_PCT;
        boolean tpRegressed = tpDelta > THROUGHPUT_REGRESSION_PCT;

        String status, message;
        if (latRegressed || tpRegressed) {
            status = "REGRESSION_DETECTED";
            List<String> issues = new ArrayList<>();
            if (latRegressed && latestP95 != null && prevP95 != null)
                issues.add(String.format(
                        "P95 latency +%.1f%% (%dμs → %dμs)", latDelta * 100,
                        prevP95, latestP95));
            if (tpRegressed)
                issues.add(String.format(
                        "Throughput -%.1f%% (%.0f → %.0f RPS)", tpDelta * 100,
                        prev.getThroughputRps(), latest.getThroughputRps()));
            message = String.join("; ", issues);
        } else {
            status = "OK";
            message = String.format("Latency delta %.1f%%, throughput delta %.1f%% — within thresholds",
                    latDelta * 100, -tpDelta * 100);
        }

        return RegressionReport.builder()
                .testName(testName).status(status).message(message)
                .previousResult(prev).currentResult(latest)
                .latencyDeltaPct(latDelta * 100).throughputDeltaPct(-tpDelta * 100)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegressionReport {
        private String testName;
        private String status; // BASELINE | OK | REGRESSION_DETECTED
        private String message;
        private BenchmarkResult previousResult;
        private BenchmarkResult currentResult;
        private double latencyDeltaPct;
        private double throughputDeltaPct;
    }
}