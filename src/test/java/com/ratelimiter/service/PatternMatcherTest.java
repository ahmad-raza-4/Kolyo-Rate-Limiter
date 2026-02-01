package com.ratelimiter.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatternMatcherTest {

    private final PatternMatcher patternMatcher = new PatternMatcher();

    @Test
    void shouldMatchExactKey() {
        PatternMatcher.CompiledPattern pattern = patternMatcher.compile("user:123", 100);

        assertThat(pattern.matches("user:123")).isTrue();
        assertThat(pattern.matches("user:456")).isFalse();
    }

    @Test
    void shouldMatchWildcardPattern() {
        PatternMatcher.CompiledPattern pattern = patternMatcher.compile("user:*", 50);

        assertThat(pattern.matches("user:123")).isTrue();
        assertThat(pattern.matches("user:premium:456")).isTrue();
        assertThat(pattern.matches("api:v1")).isFalse();
    }

    @Test
    void shouldMatchMultiSegmentPattern() {
        PatternMatcher.CompiledPattern pattern = patternMatcher.compile("api:v1:*", 40);

        assertThat(pattern.matches("api:v1:users")).isTrue();
        assertThat(pattern.matches("api:v1:users:123")).isTrue();
        assertThat(pattern.matches("api:v2:users")).isFalse();
    }

    @Test
    void shouldSelectHighestPriorityMatch() {
        List<PatternMatcher.CompiledPattern> patterns = Arrays.asList(
                patternMatcher.compile("user:*", 10),
                patternMatcher.compile("user:premium:*", 50),
                patternMatcher.compile("user:premium:123", 100));

        // Test exact match (highest priority)
        PatternMatcher.CompiledPattern bestMatch = patternMatcher.findBestMatch("user:premium:123", patterns);
        assertThat(bestMatch).isNotNull();
        assertThat(bestMatch.getPriority()).isEqualTo(100);
        assertThat(bestMatch.getPattern()).isEqualTo("user:premium:123");

        // Test second-level match
        bestMatch = patternMatcher.findBestMatch("user:premium:456", patterns);
        assertThat(bestMatch).isNotNull();
        assertThat(bestMatch.getPriority()).isEqualTo(50);
        assertThat(bestMatch.getPattern()).isEqualTo("user:premium:*");

        // Test generic match
        bestMatch = patternMatcher.findBestMatch("user:free:789", patterns);
        assertThat(bestMatch).isNotNull();
        assertThat(bestMatch.getPriority()).isEqualTo(10);
        assertThat(bestMatch.getPattern()).isEqualTo("user:*");
    }

    @Test
    void shouldCalculatePriorityCorrectly() {
        // Exact match should have highest priority
        int exactPriority = patternMatcher.calculatePriority("user:premium:123");
        assertThat(exactPriority).isEqualTo(100);

        // More specific patterns should have higher priority
        int specificPriority = patternMatcher.calculatePriority("user:premium:*");
        int genericPriority = patternMatcher.calculatePriority("user:*");
        assertThat(specificPriority).isGreaterThan(genericPriority);

        // Root wildcard should have lowest priority (except default)
        int rootPriority = patternMatcher.calculatePriority("*");
        assertThat(rootPriority).isLessThan(genericPriority);
    }

    @Test
    void shouldHandleComplexPatterns() {
        PatternMatcher.CompiledPattern pattern = patternMatcher.compile("api:v*:users:*", 30);

        assertThat(pattern.matches("api:v1:users:123")).isTrue();
        assertThat(pattern.matches("api:v2:users:admin")).isTrue();
        assertThat(pattern.matches("api:v1:posts:123")).isFalse();
    }

    @Test
    void shouldReturnNullWhenNoMatch() {
        List<PatternMatcher.CompiledPattern> patterns = Arrays.asList(
                patternMatcher.compile("user:*", 10),
                patternMatcher.compile("api:*", 10));

        PatternMatcher.CompiledPattern bestMatch = patternMatcher.findBestMatch("db:read:123", patterns);
        assertThat(bestMatch).isNull();
    }
}
