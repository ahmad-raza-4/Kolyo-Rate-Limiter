package com.ratelimiter.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RequestIdFilterTest {

    private RequestIdFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RequestIdFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldGenerateUuidWhenRequestIdHeaderIsMissing() throws ServletException, IOException {
        // Given
        when(request.getHeader("X-Request-Id")).thenReturn(null);

        // When
        filter.doFilterInternal(request, response, chain);

        // Then
        verify(response).setHeader(eq("X-Request-Id"), any(String.class));
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldGenerateUuidWhenRequestIdHeaderIsBlank() throws ServletException, IOException {
        // Given
        when(request.getHeader("X-Request-Id")).thenReturn("   ");

        // When
        filter.doFilterInternal(request, response, chain);

        // Then
        verify(response).setHeader(eq("X-Request-Id"), any(String.class));
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldUseProvidedRequestIdWhenPresent() throws ServletException, IOException {
        // Given
        String providedId = "test-request-id-123";
        when(request.getHeader("X-Request-Id")).thenReturn(providedId);

        // When
        filter.doFilterInternal(request, response, chain);

        // Then
        verify(response).setHeader("X-Request-Id", providedId);
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldSetMdcRequestIdDuringFilterChain() throws ServletException, IOException {
        // Given
        String providedId = "test-request-id-456";
        when(request.getHeader("X-Request-Id")).thenReturn(providedId);

        // When
        doAnswer(invocation -> {
            // Assert MDC is set during filter chain execution
            assertEquals(providedId, MDC.get("requestId"));
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilterInternal(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
    }

    @Test
    void shouldCleanupMdcAfterFilterChain() throws ServletException, IOException {
        // Given
        String providedId = "test-request-id-789";
        when(request.getHeader("X-Request-Id")).thenReturn(providedId);

        // When
        filter.doFilterInternal(request, response, chain);

        // Then
        assertNull(MDC.get("requestId"), "MDC should be cleaned up after filter chain");
    }

    @Test
    void shouldCleanupMdcEvenWhenExceptionOccurs() throws ServletException, IOException {
        // Given
        when(request.getHeader("X-Request-Id")).thenReturn("test-id");
        doThrow(new ServletException("Test exception")).when(chain).doFilter(request, response);

        // When/Then
        assertThrows(ServletException.class, () -> filter.doFilterInternal(request, response, chain));
        assertNull(MDC.get("requestId"), "MDC should be cleaned up even when exception occurs");
    }

    @Test
    void shouldSetResponseHeaderBeforeChainExecution() throws ServletException, IOException {
        // Given
        String providedId = "test-request-id-abc";
        when(request.getHeader("X-Request-Id")).thenReturn(providedId);

        // When
        filter.doFilterInternal(request, response, chain);

        // Then
        // Verify that setHeader is called before doFilter
        var inOrder = inOrder(response, chain);
        inOrder.verify(response).setHeader("X-Request-Id", providedId);
        inOrder.verify(chain).doFilter(request, response);
    }
}
