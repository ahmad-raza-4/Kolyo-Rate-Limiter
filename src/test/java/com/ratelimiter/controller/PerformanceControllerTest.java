package com.ratelimiter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.service.BenchmarkService;
import com.ratelimiter.service.BenchmarkService.BenchmarkRequest;
import com.ratelimiter.service.BenchmarkService.BenchmarkResult;
import com.ratelimiter.service.PerformanceBaselineService;
import com.ratelimiter.service.PerformanceBaselineService.RegressionReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PerformanceController.class)
class PerformanceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BenchmarkService benchmarkService;

    @MockBean
    private PerformanceBaselineService baselineService;

    @Test
    void shouldRunAndAnalyzeBenchmark() throws Exception {
        // Given
        BenchmarkRequest request = BenchmarkRequest.builder()
                .algorithm(com.ratelimiter.model.RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(100)
                .refillRate(10.0)
                .refillPeriodSeconds(1)
                .totalRequests(1000)
                .concurrentThreads(10)
                .durationSeconds(10)
                .build();

        BenchmarkResult benchmarkResult = createSampleResult();
        RegressionReport report = RegressionReport.builder()
                .testName("token_bucket")
                .status("OK")
                .message("Performance within thresholds")
                .currentResult(benchmarkResult)
                .build();

        when(benchmarkService.runBenchmark(any(BenchmarkRequest.class))).thenReturn(benchmarkResult);
        when(baselineService.analyze(eq("token_bucket"), any(BenchmarkResult.class))).thenReturn(report);

        // When/Then
        mockMvc.perform(post("/api/performance/run-and-analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testName").value("token_bucket"))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.message").value("Performance within thresholds"));

        verify(benchmarkService).runBenchmark(any(BenchmarkRequest.class));
        verify(baselineService).store(eq("token_bucket"), any(BenchmarkResult.class));
        verify(baselineService).analyze(eq("token_bucket"), any(BenchmarkResult.class));
    }

    @Test
    void shouldGetHistory() throws Exception {
        // Given
        String testName = "sliding_window";
        List<BenchmarkResult> history = Arrays.asList(
                createSampleResult(),
                createSampleResult()
        );

        when(baselineService.getHistory(testName)).thenReturn(history);

        // When/Then
        mockMvc.perform(get("/api/performance/history/{testName}", testName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));

        verify(baselineService).getHistory(testName);
    }

    @Test
    void shouldHandleEmptyHistory() throws Exception {
        // Given
        String testName = "fixed_window";
        when(baselineService.getHistory(testName)).thenReturn(Arrays.asList());

        // When/Then
        mockMvc.perform(get("/api/performance/history/{testName}", testName))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void shouldHandleInvalidRequest() throws Exception {
        // Given - invalid request with missing required fields
        String invalidRequest = "{}";

        // When/Then
        mockMvc.perform(post("/api/performance/run-and-analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidRequest))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void shouldHandleBenchmarkServiceException() throws Exception {
        // Given
        BenchmarkRequest request = BenchmarkRequest.builder()
                .algorithm(com.ratelimiter.model.RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(100)
                .refillRate(10.0)
                .refillPeriodSeconds(1)
                .totalRequests(1000)
                .concurrentThreads(10)
                .durationSeconds(10)
                .build();

        when(benchmarkService.runBenchmark(any(BenchmarkRequest.class)))
                .thenThrow(new RuntimeException("Benchmark failed"));

        // When/Then
        mockMvc.perform(post("/api/performance/run-and-analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void shouldDetectRegression() throws Exception {
        // Given
        BenchmarkRequest request = BenchmarkRequest.builder()
                .algorithm(com.ratelimiter.model.RateLimitAlgorithm.SLIDING_WINDOW)
                .capacity(100)
                .refillRate(10.0)
                .refillPeriodSeconds(1)
                .totalRequests(1000)
                .concurrentThreads(10)
                .durationSeconds(10)
                .build();

        BenchmarkResult benchmarkResult = createSampleResult();
        RegressionReport report = RegressionReport.builder()
                .testName("sliding_window")
                .status("REGRESSION_DETECTED")
                .message("P95 latency +25.0% (100μs → 125μs)")
                .currentResult(benchmarkResult)
                .latencyDeltaPct(25.0)
                .throughputDeltaPct(0.0)
                .build();

        when(benchmarkService.runBenchmark(any(BenchmarkRequest.class))).thenReturn(benchmarkResult);
        when(baselineService.analyze(eq("sliding_window"), any(BenchmarkResult.class))).thenReturn(report);

        // When/Then
        mockMvc.perform(post("/api/performance/run-and-analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGRESSION_DETECTED"))
                .andExpect(jsonPath("$.message").value("P95 latency +25.0% (100μs → 125μs)"))
                .andExpect(jsonPath("$.latencyDeltaPct").value(25.0));
    }

    private BenchmarkResult createSampleResult() {
        BenchmarkResult.LatencyStats latency = BenchmarkResult.LatencyStats.builder()
                .minMicros(50L)
                .maxMicros(200L)
                .avgMicros(100.0)
                .p50Micros(90L)
                .p95Micros(100L)
                .p99Micros(110L)
                .build();

        return BenchmarkResult.builder()
                .algorithm("TOKEN_BUCKET")
                .totalRequests(1000L)
                .allowedRequests(900L)
                .deniedRequests(100L)
                .throughputRps(1000.0)
                .latency(latency)
                .durationMs(1000L)
                .errorRate(0.0)
                .build();
    }
}
