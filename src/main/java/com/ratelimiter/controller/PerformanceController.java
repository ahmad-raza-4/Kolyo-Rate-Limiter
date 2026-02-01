package com.ratelimiter.controller;

import com.ratelimiter.service.BenchmarkService;
import com.ratelimiter.service.BenchmarkService.BenchmarkResult;
import com.ratelimiter.service.PerformanceBaselineService;
import com.ratelimiter.service.PerformanceBaselineService.RegressionReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
@Tag(name = "Performance", description = "Baseline tracking and regression detection")
public class PerformanceController {

    private final BenchmarkService benchmarkService;
    private final PerformanceBaselineService baselineService;

    @PostMapping("/run-and-analyze")
    @Operation(summary = "Run benchmark, store baseline, detect regression — single call")
    public ResponseEntity<RegressionReport> runAndAnalyze(
            @RequestBody BenchmarkService.BenchmarkRequest request) {

        BenchmarkResult result = benchmarkService.runBenchmark(request);
        String testName = request.getAlgorithm().name().toLowerCase();
        baselineService.store(testName, result);
        return ResponseEntity.ok(baselineService.analyze(testName, result));
    }

    @GetMapping("/history/{testName}")
    @Operation(summary = "Full benchmark history for a test name")
    public ResponseEntity<List<BenchmarkService.BenchmarkResult>> getHistory(
            @PathVariable String testName) {
        return ResponseEntity.ok(baselineService.getHistory(testName));
    }
}