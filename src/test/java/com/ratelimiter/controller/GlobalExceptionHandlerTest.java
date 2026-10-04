package com.ratelimiter.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.service.ConfigService;
import com.ratelimiter.service.RateLimitService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for Change 3 (GlobalExceptionHandler) and Change 4 (headers on 200).
 * Uses @WebMvcTest — no Redis, no Docker needed.
 */
@WebMvcTest(controllers = {RateLimitController.class, GlobalExceptionHandler.class})
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RateLimitService rateLimitService;

    @MockBean
    private ConfigService configService;

    // ─── Change 4: headers on 200 ────────────────────────────────────

    @Test
    void shouldIncludeRateLimitHeadersOnAllowedResponse() throws Exception {
        RateLimitResponse response = RateLimitResponse.builder()
                .allowed(true)
                .remainingTokens(9)
                .resetTime(java.time.Instant.now().plusSeconds(60))
                .algorithm("TOKEN_BUCKET")
                .build();
        when(rateLimitService.checkLimit(any())).thenReturn(response);

        mockMvc.perform(post("/api/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"user:1\"}"))
                .andExpect(status().isOk())
                // Change 4: X-RateLimit-Remaining must be present on 200 too
                .andExpect(header().exists("X-RateLimit-Remaining"))
                .andExpect(header().string("X-RateLimit-Remaining", "9"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    @Test
    void shouldIncludeRateLimitHeadersOnDeniedResponse() throws Exception {
        RateLimitResponse response = RateLimitResponse.builder()
                .allowed(false)
                .remainingTokens(0)
                .resetTime(java.time.Instant.now().plusSeconds(60))
                .retryAfterSeconds(30L)
                .algorithm("TOKEN_BUCKET")
                .build();
        when(rateLimitService.checkLimit(any())).thenReturn(response);

        mockMvc.perform(post("/api/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"user:1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("X-RateLimit-Reset"))
                .andExpect(header().string("Retry-After", "30"));
    }

    // ─── Change 3: GlobalExceptionHandler ────────────────────────────

    @Test
    void shouldReturn400WithProblemDetailOnBlankKey() throws Exception {
        // @NotBlank on RateLimitRequest.key triggers MethodArgumentNotValidException
        mockMvc.perform(post("/api/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"\"}"))
                .andExpect(status().isBadRequest())
                // ProblemDetail response
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void shouldReturn400WithProblemDetailOnInvalidTokens() throws Exception {
        // @Min(1) on tokens triggers MethodArgumentNotValidException
        mockMvc.perform(post("/api/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"user:1\", \"tokens\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));
    }

    @Test
    void shouldReturn500WithProblemDetailOnUnexpectedException() throws Exception {
        when(rateLimitService.checkLimit(any())).thenThrow(new RuntimeException("Redis is down"));

        mockMvc.perform(post("/api/ratelimit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"user:1\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                // Must NOT expose internal exception message
                .andExpect(jsonPath("$.detail").value(
                        "An unexpected error occurred. Please try again later."));
    }
}
