package com.ratelimiter.controller;

import com.ratelimiter.service.BenchmarkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/benchmark")
@RequiredArgsConstructor
@Tag(name = "Benchmark", description = "Performance benchmarking operations")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;

    @PostMapping("/run")
    @Operation(summary = "Run benchmark", description = "Execute performance benchmark for an algorithm")
    public ResponseEntity<BenchmarkService.BenchmarkResult> runBenchmark(
            @RequestBody BenchmarkService.BenchmarkRequest request) {
        
        log.info("Received benchmark request: {}", request);
        BenchmarkService.BenchmarkResult result = benchmarkService.runBenchmark(request);
        return ResponseEntity.ok(result);
    }
}