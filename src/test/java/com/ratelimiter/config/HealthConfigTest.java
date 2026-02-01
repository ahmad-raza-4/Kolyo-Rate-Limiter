package com.ratelimiter.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HealthConfigTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection connection;

    private HealthConfig healthConfig;

    @BeforeEach
    void setUp() {
        healthConfig = new HealthConfig(redisTemplate);
    }

    @Test
    void redisDetailedHealthIndicatorShouldReturnUpWhenRedisIsHealthy() {
        // Given
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(redisTemplate.keys("ratelimit:*")).thenReturn(Set.of("key1", "key2"));

        HealthIndicator indicator = healthConfig.redisDetailedHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        assertEquals(Status.UP, health.getStatus());
        assertNotNull(health.getDetails().get("latencyMs"));
        assertEquals(2, health.getDetails().get("activeKeys"));
        verify(connection).close();
    }

    @Test
    void redisDetailedHealthIndicatorShouldReturnDownWhenLatencyExceedsThreshold() {
        // Given
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenAnswer(invocation -> {
            // Simulate slow response
            Thread.sleep(150);
            return "PONG";
        });
        when(redisTemplate.keys("ratelimit:*")).thenReturn(Collections.emptySet());

        HealthIndicator indicator = healthConfig.redisDetailedHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().containsKey("reason"));
        assertTrue(health.getDetails().get("reason").toString().contains("latency"));
        verify(connection).close();
    }

    @Test
    void redisDetailedHealthIndicatorShouldReturnWarningWhenLatencyApproachingThreshold() {
        // Given
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenAnswer(invocation -> {
            // Simulate moderate delay
            Thread.sleep(60);
            return "PONG";
        });
        when(redisTemplate.keys("ratelimit:*")).thenReturn(Collections.emptySet());

        HealthIndicator indicator = healthConfig.redisDetailedHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().containsKey("warning"));
        assertTrue(health.getDetails().get("warning").toString().contains("approaching threshold"));
        verify(connection).close();
    }

    @Test
    void redisDetailedHealthIndicatorShouldReturnDownWhenRedisIsUnreachable() {
        // Given
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenThrow(new RuntimeException("Cannot connect to Redis"));

        HealthIndicator indicator = healthConfig.redisDetailedHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Cannot reach Redis", health.getDetails().get("error"));
        assertTrue(health.getDetails().containsKey("exception") || health.getDetails().size() > 0);
    }

    @Test
    void redisDetailedHealthIndicatorShouldHandleNullKeysGracefully() {
        // Given
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(redisTemplate.keys("ratelimit:*")).thenReturn(null);

        HealthIndicator indicator = healthConfig.redisDetailedHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        assertEquals(Status.UP, health.getStatus());
        assertEquals(0, health.getDetails().get("activeKeys"));
        verify(connection).close();
    }

    @Test
    void redisDetailedHealthIndicatorShouldCloseConnectionEvenOnException() {
        // Given
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(redisTemplate.keys("ratelimit:*")).thenThrow(new RuntimeException("Keys query failed"));

        HealthIndicator indicator = healthConfig.redisDetailedHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        assertEquals(Status.DOWN, health.getStatus());
        verify(connection).close();
    }

    @Test
    void rateLimiterHealthIndicatorShouldReturnUp() {
        // Given
        HealthIndicator indicator = healthConfig.rateLimiterHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        assertEquals(Status.UP, health.getStatus());
        assertTrue(health.getDetails().containsKey("algorithms"));
        assertTrue(health.getDetails().containsKey("status"));
        assertEquals("all algorithms registered", health.getDetails().get("status"));
    }

    @Test
    void rateLimiterHealthIndicatorShouldListAllAlgorithms() {
        // Given
        HealthIndicator indicator = healthConfig.rateLimiterHealthIndicator();

        // When
        Health health = indicator.health();

        // Then
        @SuppressWarnings("unchecked")
        var algorithms = (java.util.List<String>) health.getDetails().get("algorithms");
        assertNotNull(algorithms);
        assertEquals(5, algorithms.size());
        assertTrue(algorithms.contains("TOKEN_BUCKET"));
        assertTrue(algorithms.contains("SLIDING_WINDOW"));
        assertTrue(algorithms.contains("SLIDING_WINDOW_COUNTER"));
        assertTrue(algorithms.contains("FIXED_WINDOW"));
        assertTrue(algorithms.contains("LEAKY_BUCKET"));
    }
}
