package com.ratelimiter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.service.BenchmarkService.BenchmarkResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerformanceBaselineServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ObjectMapper mapper;

    @Mock
    private ListOperations<String, Object> listOps;

    private PerformanceBaselineService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        service = new PerformanceBaselineService(redisTemplate, mapper);
    }

    @Test
    void shouldStoreBaselineSuccessfully() throws Exception {
        // Given
        String testName = "token_bucket";
        BenchmarkResult result = createSampleResult(1000.0, 100L);
        String resultJson = "{\"throughputRps\":1000.0}";
        when(mapper.writeValueAsString(result)).thenReturn(resultJson);

        // When
        service.store(testName, result);

        // Then
        verify(listOps).leftPush("perf:baseline:token_bucket", resultJson);
        verify(listOps).trim("perf:baseline:token_bucket", 0, 9);
        verify(redisTemplate).expire("perf:baseline:token_bucket", 30, TimeUnit.DAYS);
    }

    @Test
    void shouldHandleStoreException() throws Exception {
        // Given
        String testName = "token_bucket";
        BenchmarkResult result = createSampleResult(1000.0, 100L);
        when(mapper.writeValueAsString(result)).thenThrow(new RuntimeException("Serialization failed"));

        // When/Then - should not throw, just log
        assertDoesNotThrow(() -> service.store(testName, result));
    }

    @Test
    void shouldGetHistorySuccessfully() throws Exception {
        // Given
        String testName = "sliding_window";
        String json1 = "{\"throughputRps\":1000.0}";
        String json2 = "{\"throughputRps\":900.0}";
        BenchmarkResult result1 = createSampleResult(1000.0, 100L);
        BenchmarkResult result2 = createSampleResult(900.0, 110L);

        when(listOps.range("perf:baseline:sliding_window", 0, -1))
                .thenReturn(Arrays.asList(json1, json2));
        when(mapper.readValue(json1, BenchmarkResult.class)).thenReturn(result1);
        when(mapper.readValue(json2, BenchmarkResult.class)).thenReturn(result2);

        // When
        List<BenchmarkResult> history = service.getHistory(testName);

        // Then
        assertEquals(2, history.size());
        assertEquals(1000.0, history.get(0).getThroughputRps());
        assertEquals(900.0, history.get(1).getThroughputRps());
    }

    @Test
    void shouldReturnEmptyListWhenNoHistory() {
        // Given
        String testName = "fixed_window";
        when(listOps.range("perf:baseline:fixed_window", 0, -1)).thenReturn(null);

        // When
        List<BenchmarkResult> history = service.getHistory(testName);

        // Then
        assertTrue(history.isEmpty());
    }

    @Test
    void shouldHandleGetHistoryException() throws Exception {
        // Given
        String testName = "leaky_bucket";
        when(listOps.range("perf:baseline:leaky_bucket", 0, -1))
                .thenReturn(Arrays.asList("{\"invalid\":\"json\"}"));
        when(mapper.readValue(anyString(), eq(BenchmarkResult.class)))
                .thenThrow(new RuntimeException("Deserialization failed"));

        // When
        List<BenchmarkResult> history = service.getHistory(testName);

        // Then
        assertTrue(history.isEmpty());
    }

    @Test
    void shouldReturnBaselineStatusWhenNoHistory() {
        // Given
        String testName = "token_bucket";
        BenchmarkResult latest = createSampleResult(1000.0, 100L);
        when(listOps.range("perf:baseline:token_bucket", 0, -1)).thenReturn(null);

        // When
        PerformanceBaselineService.RegressionReport report = service.analyze(testName, latest);

        // Then
        assertEquals("BASELINE", report.getStatus());
        assertEquals("No previous baseline — this run becomes the baseline.", report.getMessage());
        assertEquals(testName, report.getTestName());
    }

    @Test
    void shouldDetectNoRegressionWhenWithinThresholds() throws Exception {
        // Given
        String testName = "token_bucket";
        BenchmarkResult prev = createSampleResult(1000.0, 100L);
        BenchmarkResult latest = createSampleResult(1050.0, 105L); // 5% latency increase, 5% throughput decrease

        setupHistoryMock(testName, prev);

        // When
        PerformanceBaselineService.RegressionReport report = service.analyze(testName, latest);

        // Then
        assertEquals("OK", report.getStatus());
        assertTrue(report.getMessage().contains("within thresholds"));
    }

    @Test
    void shouldDetectLatencyRegression() throws Exception {
        // Given
        String testName = "sliding_window";
        BenchmarkResult prev = createSampleResult(1000.0, 100L);
        BenchmarkResult latest = createSampleResult(1000.0, 130L); // 30% latency increase (> 20% threshold)

        setupHistoryMock(testName, prev);

        // When
        PerformanceBaselineService.RegressionReport report = service.analyze(testName, latest);

        // Then
        assertEquals("REGRESSION_DETECTED", report.getStatus());
        assertTrue(report.getMessage().contains("P95 latency"));
        assertTrue(report.getMessage().contains("30.0%"));
    }

    @Test
    void shouldDetectThroughputRegression() throws Exception {
        // Given
        String testName = "fixed_window";
        BenchmarkResult prev = createSampleResult(1000.0, 100L);
        BenchmarkResult latest = createSampleResult(800.0, 100L); // 20% throughput decrease (> 15% threshold)

        setupHistoryMock(testName, prev);

        // When
        PerformanceBaselineService.RegressionReport report = service.analyze(testName, latest);

        // Then
        assertEquals("REGRESSION_DETECTED", report.getStatus());
        assertTrue(report.getMessage().contains("Throughput"));
        assertTrue(report.getMessage().contains("20.0%"));
    }

    @Test
    void shouldHandleNullLatency() throws Exception {
        // Given
        String testName = "token_bucket";
        BenchmarkResult prev = createSampleResultWithNullLatency(1000.0);
        BenchmarkResult latest = createSampleResult(1000.0, 100L);

        setupHistoryMock(testName, prev);

        // When
        PerformanceBaselineService.RegressionReport report = service.analyze(testName, latest);

        // Then - should not throw, should handle gracefully
        assertNotNull(report);
        assertEquals("OK", report.getStatus());
    }

    @Test
    void shouldHandleZeroP95Latency() throws Exception {
        // Given
        String testName = "leaky_bucket";
        BenchmarkResult prev = createSampleResult(1000.0, 0L); // Zero P95
        BenchmarkResult latest = createSampleResult(1000.0, 100L);

        setupHistoryMock(testName, prev);

        // When
        PerformanceBaselineService.RegressionReport report = service.analyze(testName, latest);

        // Then - should not throw division by zero
        assertNotNull(report);
        assertEquals("OK", report.getStatus());
    }

    @Test
    void shouldHandleZeroThroughput() throws Exception {
        // Given
        String testName = "token_bucket";
        BenchmarkResult prev = createSampleResult(0.0, 100L); // Zero throughput
        BenchmarkResult latest = createSampleResult(1000.0, 100L);

        setupHistoryMock(testName, prev);

        // When
        PerformanceBaselineService.RegressionReport report = service.analyze(testName, latest);

        // Then - should not throw division by zero
        assertNotNull(report);
        assertEquals("OK", report.getStatus());
    }

    private BenchmarkResult createSampleResult(double throughput, long p95Micros) {
        BenchmarkResult.LatencyStats latency = BenchmarkResult.LatencyStats.builder()
                .minMicros(50L)
                .maxMicros(200L)
                .avgMicros(100.0)
                .p50Micros(90L)
                .p95Micros(p95Micros)
                .p99Micros(p95Micros + 10)
                .build();

        return BenchmarkResult.builder()
                .algorithm("TEST_ALGORITHM")
                .totalRequests(1000L)
                .allowedRequests(900L)
                .deniedRequests(100L)
                .throughputRps(throughput)
                .latency(latency)
                .durationMs(1000L)
                .errorRate(0.0)
                .build();
    }

    private BenchmarkResult createSampleResultWithNullLatency(double throughput) {
        return BenchmarkResult.builder()
                .algorithm("TEST_ALGORITHM")
                .totalRequests(1000L)
                .allowedRequests(900L)
                .deniedRequests(100L)
                .throughputRps(throughput)
                .latency(null)
                .durationMs(1000L)
                .errorRate(0.0)
                .build();
    }

    private void setupHistoryMock(String testName, BenchmarkResult prev) throws Exception {
        String prevJson = "{\"throughputRps\":" + prev.getThroughputRps() + "}";
        when(listOps.range("perf:baseline:" + testName, 0, -1))
                .thenReturn(Arrays.asList(prevJson));
        when(mapper.readValue(prevJson, BenchmarkResult.class)).thenReturn(prev);
    }
}
