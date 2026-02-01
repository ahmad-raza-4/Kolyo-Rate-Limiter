package com.ratelimiter.controller;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.service.ConfigService;
import com.ratelimiter.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/ratelimit")
@RequiredArgsConstructor
@Tag(name = "Rate Limiter", description = "Rate limiting operations")
public class RateLimitController {

    private final RateLimitService rateLimitService;
    private final ConfigService configService;

    @PostMapping("/check")
    @Operation(summary = "Check rate limit", description = "Check if request is allowed for given key")
    public ResponseEntity<RateLimitResponse> checkRateLimit(
            @Valid @RequestBody RateLimitRequest request) {

        log.debug("Rate limit check request: {}", request);
        RateLimitResponse response = rateLimitService.checkLimit(request);

        if (response.isAllowed()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("X-RateLimit-Remaining", String.valueOf(response.getRemainingTokens()))
                    .header("X-RateLimit-Reset", response.getResetTime().toString())
                    .header("Retry-After", String.valueOf(response.getRetryAfterSeconds()))
                    .body(response);
        }
    }

    @GetMapping("/config/{key}")
    @Operation(summary = "Get configuration", description = "Get rate limit configuration for a key")
    public ResponseEntity<RateLimitConfig> getConfig(@PathVariable String key) {
        RateLimitConfig config = configService.getConfig(key);
        return ResponseEntity.ok(config);
    }

    @PostMapping("/config/keys/{key}")
    @Operation(summary = "Save key configuration", description = "Save rate limit configuration for a specific key")
    public ResponseEntity<Void> saveKeyConfig(
            @PathVariable String key,
            @Valid @RequestBody RateLimitConfig config) {

        configService.saveKeyConfig(key, config);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/config/patterns/{pattern}")
    @Operation(summary = "Save pattern configuration", description = "Save rate limit configuration for a key pattern (supports wildcards)")
    public ResponseEntity<Void> savePatternConfig(
            @PathVariable String pattern,
            @Valid @RequestBody RateLimitConfig config) {

        configService.savePatternConfig(pattern, config);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/config/keys/{key}")
    @Operation(summary = "Delete key configuration", description = "Delete rate limit configuration for a specific key")
    public ResponseEntity<Void> deleteKeyConfig(@PathVariable String key) {
        configService.deleteKeyConfig(key);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/config/patterns/{pattern}")
    @Operation(summary = "Delete pattern configuration", description = "Delete rate limit configuration for a pattern")
    public ResponseEntity<Void> deletePatternConfig(@PathVariable String pattern) {
        configService.deletePatternConfig(pattern);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/config/patterns")
    @Operation(summary = "List all patterns", description = "Get all configured patterns")
    public ResponseEntity<List<RateLimitConfig>> getAllPatterns() {
        List<RateLimitConfig> patterns = configService.getAllPatterns();
        return ResponseEntity.ok(patterns);
    }

    @PostMapping("/config/reload")
    @Operation(summary = "Reload configurations", description = "Reload all configurations and clear caches (hot-reload)")
    public ResponseEntity<Void> reloadConfigurations() {
        configService.reloadConfigurations();
        return ResponseEntity.ok().build();
    }
}