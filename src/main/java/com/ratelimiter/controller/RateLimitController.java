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

@Slf4j
@RestController
@RequestMapping("/api/ratelimit")
@RequiredArgsConstructor
@Tag(name = "Rate Limiter", description = "Rate limiting operations")
// rest controller for handling rate limit requests and configurations
public class RateLimitController {

    // services for rate limiting and configuration management
    private final RateLimitService rateLimitService;
    private final ConfigService configService;

    // checks if the request is allowed based on rate limit for the given key
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

    // retrieves the rate limit configuration for the specified key
    @GetMapping("/config/{key}")
    @Operation(summary = "Get configuration", description = "Get rate limit configuration for a key")
    public ResponseEntity<RateLimitConfig> getConfig(@PathVariable String key) {
        RateLimitConfig config = configService.getConfig(key);
        return ResponseEntity.ok(config);
    }

    // saves the rate limit configuration for the given key
    @PostMapping("/config")
    @Operation(summary = "Save configuration", description = "Save rate limit configuration for a key")
    public ResponseEntity<Void> saveConfig(
            @RequestParam String key,
            @Valid @RequestBody RateLimitConfig config) {
        
        configService.saveConfig(key, config);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // deletes the rate limit configuration for the specified key
    @DeleteMapping("/config/{key}")
    @Operation(summary = "Delete configuration", description = "Delete rate limit configuration for a key")
    public ResponseEntity<Void> deleteConfig(@PathVariable String key) {
        configService.deleteConfig(key);
        return ResponseEntity.noContent().build();
    }
}