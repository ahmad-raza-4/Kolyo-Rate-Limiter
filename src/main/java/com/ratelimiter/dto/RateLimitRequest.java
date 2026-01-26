package com.ratelimiter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitRequest {
    @NotBlank(message = "Key cannot be blank")
    private String key;
    
    @Min(value = 1, message = "Tokens must be at least 1")
    private int tokens = 1;
    
    // Optional metadata
    private String clientIp;
    private String endpoint;
}