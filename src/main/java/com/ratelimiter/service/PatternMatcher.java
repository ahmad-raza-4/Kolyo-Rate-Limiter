package com.ratelimiter.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PatternMatcher {

    // Cache compiled patterns for performance
    private final Map<String, CompiledPattern> patternCache = new ConcurrentHashMap<>();

    public static class CompiledPattern {
        private final String pattern;
        private final String regex;
        private final int priority;

        public CompiledPattern(String pattern, int priority) {
            this.pattern = pattern;
            this.priority = priority;
            this.regex = compilePattern(pattern);
        }

        private String compilePattern(String pattern) {
            // Convert wildcard pattern to regex
            // api:* -> ^api:.*$
            // user:premium:* -> ^user:premium:.*$
            return "^" + pattern.replace("*", ".*") + "$";
        }

        public boolean matches(String key) {
            return key.matches(regex);
        }

        public String getPattern() {
            return pattern;
        }

        public int getPriority() {
            return priority;
        }
    }

    public CompiledPattern compile(String pattern, int priority) {
        return patternCache.computeIfAbsent(
                pattern + ":" + priority,
                k -> new CompiledPattern(pattern, priority));
    }

    public CompiledPattern findBestMatch(String key, List<CompiledPattern> patterns) {
        return patterns.stream()
                .filter(p -> p.matches(key))
                .max(Comparator.comparingInt(CompiledPattern::getPriority))
                .orElse(null);
    }

    public int calculatePriority(String pattern) {
        // Auto-calculate priority based on pattern specificity
        if (!pattern.contains("*")) {
            return 100; // Exact match
        }

        // Count segments and wildcards
        String[] segments = pattern.split(":");
        long wildcardCount = pattern.chars().filter(c -> c == '*').count();
        long segmentCount = segments.length;

        // More segments and fewer wildcards = higher priority
        return (int) ((segmentCount * 10) - (wildcardCount * 5));
    }

    public void clearCache() {
        patternCache.clear();
    }
}
