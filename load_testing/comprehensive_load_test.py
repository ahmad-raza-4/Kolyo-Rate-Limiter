#!/usr/bin/env python3
"""Comprehensive E2E load test for the entire rate limiter system.

This script provides full end-to-end testing for production launch:
- All 5 rate limiting algorithms (TOKEN_BUCKET, SLIDING_WINDOW, SLIDING_WINDOW_COUNTER, FIXED_WINDOW, LEAKY_BUCKET)
- All API endpoints (rate limit check, admin, benchmark, config, health, metrics, performance)
- Pre-flight health checks and validation
- Post-test validation and regression detection
- Detailed metrics per algorithm and per endpoint
- Realistic user traffic patterns including burst scenarios
- Comprehensive reports with visualizations data
- Performance baseline tracking and regression analysis

Usage:
  python load_testing/comprehensive_load_test.py \\
    --base-url http://localhost:8080 \\
    --duration-seconds 180 \\
    --concurrency 100 \\
    --target-rps 1000 \\
    --output-dir data/comprehensive_loadtest

Requirements:
- Rate limiter service running on specified base-url
- Redis running and accessible
- Python 3.8+
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import statistics
import sys
import threading
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime
from http.client import HTTPConnection, HTTPException
from pathlib import Path
from typing import Dict, List, Tuple, Optional, Any
from urllib.parse import urlparse


# ============================================================================
# Data Models
# ============================================================================

@dataclass
class RequestMetric:
    """Metrics for a single request."""
    timestamp_ms: int
    latency_ms: float
    status_code: int
    allowed: Optional[bool]
    error: Optional[str]
    key: str
    endpoint: str
    algorithm: str
    tokens: int
    remaining_tokens: Optional[int] = None
    retry_after: Optional[int] = None


@dataclass
class AggregateStats:
    """Aggregated statistics for a set of requests."""
    total: int
    allowed: int
    denied: int
    errors: int
    rps: float
    min_ms: float
    max_ms: float
    avg_ms: float
    median_ms: float
    p50_ms: float
    p75_ms: float
    p90_ms: float
    p95_ms: float
    p99_ms: float
    p999_ms: float
    std_dev_ms: float
    success_rate: float
    error_rate: float


@dataclass
class AlgorithmConfig:
    """Configuration for each algorithm."""
    name: str
    capacity: int
    refill_rate: float
    refill_period_seconds: int
    
    
@dataclass
class TestConfig:
    """Overall test configuration."""
    base_url: str
    duration_seconds: int
    warmup_seconds: int
    concurrency: int
    target_rps: int
    output_dir: Path
    raw_csv: bool
    seed: int
    include_burst_test: bool
    include_benchmark_test: bool
    include_performance_test: bool
    strict_validation: bool
    algorithms: List[AlgorithmConfig] = field(default_factory=list)


@dataclass
class HealthCheckResult:
    """Health check validation result."""
    passed: bool
    redis_latency_ms: int
    active_keys: int
    issues: List[str]


# ============================================================================
# Algorithm Configurations
# ============================================================================

DEFAULT_ALGORITHM_CONFIGS = [
    AlgorithmConfig(
        name="TOKEN_BUCKET",
        capacity=100,
        refill_rate=10.0,
        refill_period_seconds=1
    ),
    AlgorithmConfig(
        name="SLIDING_WINDOW",
        capacity=100,
        refill_rate=10.0,
        refill_period_seconds=1
    ),
    AlgorithmConfig(
        name="SLIDING_WINDOW_COUNTER",
        capacity=100,
        refill_rate=10.0,
        refill_period_seconds=1
    ),
    AlgorithmConfig(
        name="FIXED_WINDOW",
        capacity=100,
        refill_rate=10.0,
        refill_period_seconds=1
    ),
    AlgorithmConfig(
        name="LEAKY_BUCKET",
        capacity=100,
        refill_rate=10.0,
        refill_period_seconds=1
    ),
]


# ============================================================================
# Endpoint Definitions
# ============================================================================

ENDPOINTS = {
    "rate_limit_check": {
        "path": "/api/ratelimit/check",
        "method": "POST",
        "weight": 0.60,  # 60% of traffic - core functionality
    },
    "get_config": {
        "path": "/api/ratelimit/config/{key}",
        "method": "GET",
        "weight": 0.10,  # 10% of traffic
    },
    "admin_stats": {
        "path": "/api/admin/stats",
        "method": "GET",
        "weight": 0.05,  # 5% of traffic
    },
    "admin_keys": {
        "path": "/api/admin/keys",
        "method": "GET",
        "weight": 0.05,  # 5% of traffic
    },
    "get_patterns": {
        "path": "/api/ratelimit/config/patterns",
        "method": "GET",
        "weight": 0.05,  # 5% of traffic
    },
    "save_config": {
        "path": "/api/ratelimit/config/keys/{key}",
        "method": "POST",
        "weight": 0.05,  # 5% of traffic
    },
    "health_check": {
        "path": "/actuator/health",
        "method": "GET",
        "weight": 0.05,  # 5% of traffic
    },
    "metrics": {
        "path": "/actuator/metrics",
        "method": "GET",
        "weight": 0.05,  # 5% of traffic
    },
}


# ============================================================================
# Pre-flight Health Checks
# ============================================================================

def run_health_checks(base_url: str) -> HealthCheckResult:
    """Run comprehensive health checks before testing."""
    print(f"\n{'='*80}")
    print(f"Running Pre-flight Health Checks")
    print(f"{'='*80}\n")
    
    issues = []
    redis_latency = 0
    active_keys = 0
    
    parsed = urlparse(base_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    
    # Check main health endpoint
    try:
        status, body, _ = make_http_request(conn, "GET", "/actuator/health", None, {})
        if status != 200:
            issues.append(f"Health endpoint returned {status}")
        else:
            data = json.loads(body)
            # Debug: print the actual health response structure
            print(f"DEBUG: Health response structure: {json.dumps(data, indent=2)[:500]}")
            
            if data.get("status") != "UP":
                issues.append(f"Service status is {data.get('status')}, not UP")
            
            # Check Redis health details
            components = data.get("components", {})
            redis_health = components.get("redisDetailedHealthIndicator", {})
            redis_status = redis_health.get("status")
            
            if redis_status is None:
                print(f"  ⚠ Redis health indicator not found in components")
                print(f"  Available components: {list(components.keys())}")
            elif redis_status != "UP":
                issues.append(f"Redis health is {redis_status}")
            else:
                details = redis_health.get("details", {})
                redis_latency = details.get("latencyMs", 0)
                active_keys = details.get("activeKeys", 0)
                
                if redis_latency > 100:
                    issues.append(f"Redis latency too high: {redis_latency}ms")
                
                print(f"✓ Redis Health: UP (latency={redis_latency}ms, keys={active_keys})")
            
            # Check rate limiter health
            rl_health = components.get("rateLimiterHealthIndicator", {})
            rl_status = rl_health.get("status")
            
            if rl_status is None:
                print(f"  ⚠ Rate limiter health indicator not found in components")
            elif rl_status != "UP":
                issues.append(f"Rate limiter health is {rl_status}")
            else:
                print(f"✓ Rate Limiter Health: UP")
    except Exception as e:
        issues.append(f"Failed to reach health endpoint: {e}")
    
    # Check metrics endpoint
    try:
        status, body, _ = make_http_request(conn, "GET", "/actuator/metrics", None, {})
        if status != 200:
            issues.append(f"Metrics endpoint returned {status}")
        else:
            print(f"✓ Metrics endpoint: accessible")
    except Exception as e:
        issues.append(f"Failed to reach metrics endpoint: {e}")
    
    # Check rate limit endpoint with test request
    try:
        payload = json.dumps({
            "key": "test:preflight:check",
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        status, body, _ = make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
        if status != 200:
            issues.append(f"Rate limit check endpoint returned {status}")
        else:
            print(f"✓ Rate limit check endpoint: functional")
    except Exception as e:
        issues.append(f"Failed to test rate limit endpoint: {e}")
    
    conn.close()
    
    passed = len(issues) == 0
    
    if passed:
        print(f"\n✓ All pre-flight checks PASSED")
    else:
        print(f"\n✗ Pre-flight checks FAILED:")
        for issue in issues:
            print(f"  - {issue}")
    
    print()
    
    return HealthCheckResult(
        passed=passed,
        redis_latency_ms=redis_latency,
        active_keys=active_keys,
        issues=issues
    )


# ============================================================================
# Traffic Simulation
# ============================================================================

def weighted_choice(rnd: random.Random, items: List[Tuple[Any, float]]) -> Any:
    """Select an item based on weighted probabilities."""
    r = rnd.random()
    acc = 0.0
    for value, weight in items:
        acc += weight
        if r <= acc:
            return value
    return items[-1][0]


def generate_traffic_profile(rnd: random.Random, algorithms: List[AlgorithmConfig]) -> Tuple[str, str, int, str, str]:
    """
    Generate realistic traffic pattern.
    
    Returns: (key, endpoint_name, tokens, client_ip, algorithm)
    """
    # User tiers with different probabilities
    user_tiers = [
        ("user:guest", 0.60),
        ("user:registered", 0.30),
        ("user:premium", 0.08),
        ("user:vip", 0.02),
    ]
    
    # Token consumption patterns
    token_weights = [
        (1, 0.70),
        (2, 0.20),
        (5, 0.08),
        (10, 0.02),
    ]
    
    # Select endpoint based on weights
    endpoint_items = [(name, info["weight"]) for name, info in ENDPOINTS.items()]
    endpoint_name = weighted_choice(rnd, endpoint_items)
    
    # Select user tier and generate key
    user_tier = weighted_choice(rnd, user_tiers)
    user_id = rnd.randint(1, 10000)
    
    # Select algorithm
    algorithm = rnd.choice(algorithms).name
    
    # Build key with algorithm prefix
    key = f"{algorithm.lower()}:{user_tier}:{user_id}"
    
    # Select tokens
    tokens = weighted_choice(rnd, token_weights)
    
    # Generate realistic IP
    client_ip = f"10.{rnd.randint(0, 255)}.{rnd.randint(0, 255)}.{rnd.randint(1, 254)}"
    
    return key, endpoint_name, tokens, client_ip, algorithm


# ============================================================================
# HTTP Request Handling
# ============================================================================

def make_http_request(
    conn: HTTPConnection,
    method: str,
    path: str,
    payload: Optional[str] = None,
    headers: Optional[Dict[str, str]] = None,
) -> Tuple[int, str, Dict[str, str]]:
    """Make HTTP request and return status, body, and headers."""
    if headers is None:
        headers = {}
    
    try:
        conn.request(method, path, body=payload, headers=headers)
        response = conn.getresponse()
        data = response.read().decode("utf-8")
        response_headers = dict(response.getheaders())
        return response.status, data, response_headers
    except Exception as e:
        raise HTTPException(f"Request failed: {e}")


def build_request_for_endpoint(
    endpoint_name: str,
    key: str,
    tokens: int,
    client_ip: str,
    algorithm: str,
    config: AlgorithmConfig,
) -> Tuple[str, str, Optional[str], Dict[str, str]]:
    """
    Build HTTP request components for a given endpoint.
    
    Returns: (method, path, payload, headers)
    """
    endpoint_info = ENDPOINTS[endpoint_name]
    method = endpoint_info["method"]
    path = endpoint_info["path"]
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    payload = None
    
    if endpoint_name == "rate_limit_check":
        path = "/api/ratelimit/check"
        payload = json.dumps({
            "key": key,
            "tokens": tokens,
            "clientIp": client_ip,
            "endpoint": "/api/test",
        })
    
    elif endpoint_name == "get_config":
        path = f"/api/ratelimit/config/{key}"
    
    elif endpoint_name == "admin_stats":
        path = "/api/admin/stats"
    
    elif endpoint_name == "admin_keys":
        path = "/api/admin/keys?limit=50"
    
    elif endpoint_name == "get_patterns":
        path = "/api/ratelimit/config/patterns"
    
    elif endpoint_name == "save_config":
        path = f"/api/ratelimit/config/keys/{key}"
        payload = json.dumps({
            "algorithm": algorithm,
            "capacity": config.capacity,
            "refillRate": config.refill_rate,
            "refillPeriodSeconds": config.refill_period_seconds,
        })
    
    elif endpoint_name == "health_check":
        path = "/actuator/health"
    
    elif endpoint_name == "metrics":
        path = "/actuator/metrics"
    
    return method, path, payload, headers


# ============================================================================
# Worker Thread
# ============================================================================

def worker_thread(
    worker_id: int,
    config: TestConfig,
    metrics: List[RequestMetric],
    lock: threading.Lock,
) -> None:
    """Worker thread that generates load."""
    rnd = random.Random(config.seed + worker_id)
    parsed = urlparse(config.base_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    
    start_time = time.time()
    per_worker_rps = max(1, config.target_rps // max(config.concurrency, 1))
    interval = 1.0 / max(per_worker_rps, 1)
    
    request_count = 0
    
    while time.time() - start_time < config.duration_seconds:
        # Generate traffic profile
        key, endpoint_name, tokens, client_ip, algorithm = generate_traffic_profile(
            rnd, config.algorithms
        )
        
        # Find algorithm config
        algo_config = next((a for a in config.algorithms if a.name == algorithm), config.algorithms[0])
        
        # Build request
        method, path, payload, headers = build_request_for_endpoint(
            endpoint_name, key, tokens, client_ip, algorithm, algo_config
        )
        
        # Execute request and measure
        t0 = time.time()
        status = 0
        allowed = None
        error = None
        remaining_tokens = None
        retry_after = None
        
        try:
            status, body, response_headers = make_http_request(conn, method, path, payload, headers)
            
            # Parse response for rate limit check
            if endpoint_name == "rate_limit_check" and body:
                try:
                    data = json.loads(body)
                    allowed = bool(data.get("allowed", False))
                    remaining_tokens = data.get("remainingTokens")
                    retry_after = data.get("retryAfterSeconds")
                except json.JSONDecodeError:
                    error = "Invalid JSON response"
            
        except Exception as exc:
            error = str(exc)
        
        t1 = time.time()
        
        # Record metric
        metric = RequestMetric(
            timestamp_ms=int(t0 * 1000),
            latency_ms=(t1 - t0) * 1000.0,
            status_code=status,
            allowed=allowed,
            error=error,
            key=key,
            endpoint=endpoint_name,
            algorithm=algorithm,
            tokens=tokens,
            remaining_tokens=remaining_tokens,
            retry_after=retry_after,
        )
        
        with lock:
            metrics.append(metric)
        
        request_count += 1
        
        # Rate limiting (best effort)
        elapsed = time.time() - t0
        sleep_time = max(0.0, interval - elapsed)
        if sleep_time > 0:
            time.sleep(sleep_time)
    
    conn.close()


# ============================================================================
# Burst Testing
# ============================================================================

def run_burst_test(config: TestConfig) -> List[RequestMetric]:
    """
    Run burst test scenario: sudden spike in traffic.
    Tests system behavior under sudden load increase.
    """
    print(f"\n{'='*80}")
    print(f"Running Burst Test")
    print(f"{'='*80}\n")
    
    metrics: List[RequestMetric] = []
    lock = threading.Lock()
    
    # Use 3x normal concurrency for burst
    burst_concurrency = config.concurrency * 3
    burst_duration = 30  # 30 second burst
    
    print(f"Burst Parameters:")
    print(f"  Concurrency: {burst_concurrency} (3x normal)")
    print(f"  Duration: {burst_duration}s")
    print(f"  Target RPS: {config.target_rps * 3}\n")
    
    burst_config = TestConfig(
        base_url=config.base_url,
        duration_seconds=burst_duration,
        warmup_seconds=0,
        concurrency=burst_concurrency,
        target_rps=config.target_rps * 3,
        output_dir=config.output_dir,
        raw_csv=False,
        seed=config.seed,
        algorithms=config.algorithms,
        include_burst_test=False,
        include_benchmark_test=False,
        include_performance_test=False,
        strict_validation=False,
    )
    
    start_time = time.time()
    
    with ThreadPoolExecutor(max_workers=burst_concurrency) as executor:
        futures = []
        for i in range(burst_concurrency):
            future = executor.submit(worker_thread, i, burst_config, metrics, lock)
            futures.append(future)
        
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as e:
                print(f"Burst worker error: {e}")
    
    end_time = time.time()
    actual_duration = int(end_time - start_time)
    
    print(f"\n✓ Burst test completed in {actual_duration}s")
    print(f"  Collected {len(metrics):,} metrics\n")
    
    return metrics


# ============================================================================
# Benchmark Testing
# ============================================================================

def run_benchmark_tests(config: TestConfig) -> Dict[str, Any]:
    """Run benchmark tests for all algorithms."""
    print(f"\n{'='*80}")
    print(f"Running Benchmark Tests")
    print(f"{'='*80}\n")
    
    parsed = urlparse(config.base_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=60)
    
    benchmark_results = {}
    
    for algo_config in config.algorithms:
        print(f"Benchmarking {algo_config.name}...")
        
        payload = json.dumps({
            "algorithm": algo_config.name,
            "capacity": algo_config.capacity,
            "refillRate": algo_config.refill_rate,
            "refillPeriodSeconds": algo_config.refill_period_seconds,
            "totalRequests": 10000,
            "concurrentThreads": 50,
            "durationSeconds": 60,
        })
        
        headers = {"Content-Type": "application/json"}
        
        try:
            status, body, _ = make_http_request(conn, "POST", "/api/benchmark/run", payload, headers)
            if status == 200:
                result = json.loads(body)
                benchmark_results[algo_config.name] = result
                print(f"  ✓ {algo_config.name}: {result.get('throughputRps', 0):.2f} RPS, "
                      f"P95={result.get('latency', {}).get('p95Micros', 0)}μs")
            else:
                print(f"  ✗ {algo_config.name}: HTTP {status}")
        except Exception as e:
            print(f"  ✗ {algo_config.name}: {e}")
    
    conn.close()
    print()
    
    return benchmark_results


# ============================================================================
# Performance Regression Testing
# ============================================================================

def run_performance_tests(config: TestConfig) -> Dict[str, Any]:
    """Run performance regression tests for all algorithms."""
    print(f"\n{'='*80}")
    print(f"Running Performance Regression Tests")
    print(f"{'='*80}\n")
    
    parsed = urlparse(config.base_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=120)
    
    performance_results = {}
    
    for algo_config in config.algorithms:
        print(f"Testing {algo_config.name} for regression...")
        
        payload = json.dumps({
            "algorithm": algo_config.name,
            "capacity": algo_config.capacity,
            "refillRate": algo_config.refill_rate,
            "refillPeriodSeconds": algo_config.refill_period_seconds,
            "totalRequests": 10000,
            "concurrentThreads": 50,
            "durationSeconds": 60,
        })
        
        headers = {"Content-Type": "application/json"}
        
        try:
            status, body, _ = make_http_request(conn, "POST", "/api/performance/run-and-analyze", payload, headers)
            if status == 200:
                result = json.loads(body)
                performance_results[algo_config.name] = result
                status_str = result.get('status', 'UNKNOWN')
                message = result.get('message', '')
                
                if status_str == "REGRESSION_DETECTED":
                    print(f"  ⚠ {algo_config.name}: REGRESSION - {message}")
                elif status_str == "BASELINE":
                    print(f"  ℹ {algo_config.name}: BASELINE - {message}")
                else:
                    print(f"  ✓ {algo_config.name}: OK - {message}")
            else:
                print(f"  ✗ {algo_config.name}: HTTP {status}")
        except Exception as e:
            print(f"  ✗ {algo_config.name}: {e}")
    
    conn.close()
    print()
    
    return performance_results


# ============================================================================
# Edge Case Testing - Verify Rate Limiting Correctness
# ============================================================================

@dataclass
class EdgeCaseResult:
    """Result of an edge case test."""
    test_name: str
    passed: bool
    expected_allowed: int
    expected_denied: int
    actual_allowed: int
    actual_denied: int
    issues: List[str]
    details: Dict[str, Any]


def run_edge_case_tests(config: TestConfig) -> List[EdgeCaseResult]:
    """
    Run edge case tests to verify rate limiting is working correctly.
    
    Tests include:
    1. Exhaust bucket - send more requests than capacity, verify denials
    2. Burst beyond capacity - rapid requests should be denied
    3. Token tracking - verify remaining tokens decrease correctly
    4. Retry-after headers - verify they're set on denials
    5. Gradual refill - verify tokens refill over time
    """
    print(f"\n{'='*80}")
    print(f"Running Edge Case Tests - Rate Limiting Correctness")
    print(f"{'='*80}\n")
    
    parsed = urlparse(config.base_url)
    results = []
    
    # Test 1: Exhaust bucket test
    results.append(test_exhaust_bucket(parsed, config.algorithms[0]))  # TOKEN_BUCKET
    
    # Test 2: Burst beyond capacity
    results.append(test_burst_beyond_capacity(parsed, config.algorithms[0]))
    
    # Test 3: Token tracking validation
    results.append(test_token_tracking(parsed, config.algorithms[0]))
    
    # Test 4: Retry-after header validation
    results.append(test_retry_after_headers(parsed, config.algorithms[0]))
    
    # Test 5: Gradual refill validation
    results.append(test_gradual_refill(parsed, config.algorithms[0]))
    
    # Summary
    passed_count = sum(1 for r in results if r.passed)
    total_count = len(results)
    
    print(f"\n{'='*80}")
    print(f"Edge Case Test Summary: {passed_count}/{total_count} PASSED")
    print(f"{'='*80}\n")
    
    for result in results:
        status = "✓ PASS" if result.passed else "✗ FAIL"
        print(f"{status}: {result.test_name}")
        if not result.passed:
            for issue in result.issues:
                print(f"  - {issue}")
    
    print()
    return results


def test_exhaust_bucket(parsed, algo_config: AlgorithmConfig) -> EdgeCaseResult:
    """Test that requests are denied after exhausting the bucket capacity."""
    test_name = "Exhaust Bucket Test"
    print(f"Running: {test_name}...")
    
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    key = f"edgecase:exhaust:{int(time.time())}"
    issues = []
    
    # Configure a small bucket for testing with ZERO refill during test
    capacity = 10
    refill_rate = 0.001  # Nearly zero - 0.001 tokens per 3600 seconds = effectively no refill
    refill_period = 3600  # 1 hour - ensures no refill during test
    
    # Configure the rate limit
    setup_key_config(conn, key, algo_config.name, capacity, refill_rate, refill_period)
    
    allowed_count = 0
    denied_count = 0
    error_count = 0
    requests_to_send = capacity + 5  # Send more than capacity
    
    responses = []
    for i in range(requests_to_send):
        payload = json.dumps({
            "key": key,
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        
        try:
            status, body, _ = make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
            if status == 200:
                data = json.loads(body)
                is_allowed = data.get("allowed")
                responses.append({"req": i, "status":status, "allowed": is_allowed, "remaining": data.get("remainingTokens")})
                if is_allowed:
                    allowed_count += 1
                else:
                    denied_count += 1
            elif status == 429:
                # HTTP 429 is a valid denial response
                denied_count += 1
                responses.append({"req": i, "status": status, "denied": True})
            else:
                error_count += 1
                responses.append({"req": i, "status": status, "error": True})
        except Exception as e:
            error_count += 1
            issues.append(f"Request {i} failed: {e}")
    
    conn.close()
    
    # Debug output
    print(f"  Debug: First 5 responses: {responses[:5]}")
    print(f"  Debug: Last 5 responses: {responses[-5:]}")
    
    # Validate: first 'capacity' should be allowed, rest denied
    expected_allowed = capacity
    expected_denied = requests_to_send - capacity
    
    # Allow 1 token margin for potential race conditions
    if allowed_count < expected_allowed - 1 or allowed_count > expected_allowed + 1:
        issues.append(f"Expected ~{expected_allowed} allowed, got {allowed_count}")
    
    if denied_count < expected_denied - 1:
        issues.append(f"Expected at least {expected_denied - 1} denied, got {denied_count}")
    
    if error_count > 0:
        issues.append(f"Got {error_count} errors (HTTP failures)")
    
    passed = len(issues) == 0
    
    print(f"  Sent: {requests_to_send}, Allowed: {allowed_count}, Denied: {denied_count}, Errors: {error_count}")
    print(f"  {'✓ PASS' if passed else '✗ FAIL'}")
    
    return EdgeCaseResult(
        test_name=test_name,
        passed=passed,
        expected_allowed=expected_allowed,
        expected_denied=expected_denied,
        actual_allowed=allowed_count,
        actual_denied=denied_count,
        issues=issues,
        details={"capacity": capacity, "requests_sent": requests_to_send, "responses": responses}
    )


def test_burst_beyond_capacity(parsed, algo_config: AlgorithmConfig) -> EdgeCaseResult:
    """Test rapid requests beyond capacity are properly denied."""
    test_name = "Burst Beyond Capacity Test"
    print(f"Running: {test_name}...")
    
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    key = f"edgecase:burst:{int(time.time())}"
    issues = []
    
    capacity = 20
    burst_size = 50  # Send 2.5x capacity rapidly
    refill_rate = 0.001  # Nearly zero refill
    refill_period = 3600  # 1 hour
    
    setup_key_config(conn, key, algo_config.name, capacity, refill_rate, refill_period)
    
    allowed_count = 0
    denied_count = 0
    
    # Send burst of requests as fast as possible
    for i in range(burst_size):
        payload = json.dumps({
            "key": key,
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        
        try:
            status, body, _ = make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
            if status == 200:
                data = json.loads(body)
                if data.get("allowed"):
                    allowed_count += 1
                else:
                    denied_count += 1
            elif status == 429:
                # HTTP 429 is a valid denial response
                denied_count += 1
        except Exception as e:
            issues.append(f"Request {i} failed: {e}")
    
    conn.close()
    
    # At least (burst_size - capacity) should be denied
    expected_denied = burst_size - capacity
    
    # Allow 2 tokens margin for race conditions
    if denied_count < expected_denied - 2:
        issues.append(f"Expected ~{expected_denied} denied, got only {denied_count}")
    
    if allowed_count > capacity + 2:
        issues.append(f"Too many allowed: {allowed_count} (capacity: {capacity})")
    
    passed = len(issues) == 0
    
    print(f"  Burst: {burst_size}, Allowed: {allowed_count}, Denied: {denied_count}")
    print(f"  {'✓ PASS' if passed else '✗ FAIL'}")
    
    return EdgeCaseResult(
        test_name=test_name,
        passed=passed,
        expected_allowed=capacity,
        expected_denied=expected_denied,
        actual_allowed=allowed_count,
        actual_denied=denied_count,
        issues=issues,
        details={"capacity": capacity, "burst_size": burst_size}
    )


def test_token_tracking(parsed, algo_config: AlgorithmConfig) -> EdgeCaseResult:
    """Test that remaining tokens are tracked correctly."""
    test_name = "Token Tracking Test"
    print(f"Running: {test_name}...")
    
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    key = f"edgecase:tokens:{int(time.time())}"
    issues = []
    
    capacity = 15
    refill_rate = 0.001  # Nearly zero refill
    refill_period = 3600
    
    setup_key_config(conn, key, algo_config.name, capacity, refill_rate, refill_period)
    
    remaining_tokens_list = []
    
    # Send requests and track remaining tokens
    for i in range(capacity):
        payload = json.dumps({
            "key": key,
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        
        try:
            status, body, _ = make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
            if status == 200:
                data = json.loads(body)
                if data.get("allowed"):
                    remaining = data.get("remainingTokens")
                    if remaining is not None:
                        remaining_tokens_list.append(remaining)
        except Exception as e:
            issues.append(f"Request {i} failed: {e}")
    
    conn.close()
    
    # Validate: remaining tokens should strictly decrease (no refill)
    if len(remaining_tokens_list) > 2:
        # Check that tokens are monotonically decreasing
        is_decreasing = all(
            remaining_tokens_list[i] >= remaining_tokens_list[i + 1]
            for i in range(len(remaining_tokens_list) - 1)
        )
        
        if not is_decreasing:
            issues.append(f"Tokens not decreasing monotonically: {remaining_tokens_list}")
    else:
        issues.append("Not enough token data collected")
    
    passed = len(issues) == 0
    
    print(f"  Tracked {len(remaining_tokens_list)} token values")
    if remaining_tokens_list:
        print(f"  Range: {min(remaining_tokens_list)} to {max(remaining_tokens_list)}")
    print(f"  {'✓ PASS' if passed else '✗ FAIL'}")
    
    return EdgeCaseResult(
        test_name=test_name,
        passed=passed,
        expected_allowed=capacity,
        expected_denied=0,
        actual_allowed=len(remaining_tokens_list),
        actual_denied=0,
        issues=issues,
        details={"remaining_tokens": remaining_tokens_list}
    )


def test_retry_after_headers(parsed, algo_config: AlgorithmConfig) -> EdgeCaseResult:
    """Test that retry-after headers are set correctly on denied requests."""
    test_name = "Retry-After Headers Test"
    print(f"Running: {test_name}...")
    
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    key = f"edgecase:retry:{int(time.time())}"
    issues = []
    
    capacity = 5
    refill_rate = 0.001  # Nearly zero refill
    refill_period = 3600
    
    setup_key_config(conn, key, algo_config.name, capacity, refill_rate, refill_period)
    
    # Exhaust the bucket
    for i in range(capacity):
        payload = json.dumps({
            "key": key,
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
    
    # Now send requests that should be denied
    retry_after_values = []
    denied_count = 0
    for i in range(5):  # Try 5 requests instead of 3 for better validation
        payload = json.dumps({
            "key": key,
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        
        try:
            status, body, response_headers = make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
            if status == 200:
                data = json.loads(body)
                if not data.get("allowed"):
                    denied_count += 1
                    retry_after = data.get("retryAfterSeconds")
                    if retry_after is not None and retry_after > 0:
                        retry_after_values.append(retry_after)
            elif status == 429:
                # HTTP 429 is a valid denial response
                denied_count += 1
                # Try to get retry-after from response headers or body
                if body:
                    try:
                        data = json.loads(body)
                        retry_after = data.get("retryAfterSeconds")
                        if retry_after is not None and retry_after > 0:
                            retry_after_values.append(retry_after)
                    except json.JSONDecodeError:
                        # Body is not valid JSON (e.g., HTML error page); ignore and fall back to headers.
                        pass
                # Also check Retry-After header
                retry_after_header = response_headers.get("Retry-After")
                if retry_after_header:
                    try:
                        retry_after_values.append(int(retry_after_header))
                    except ValueError:
                        # Ignore invalid Retry-After header values; they are non-fatal for this validation.
                        pass
        except Exception as e:
            issues.append(f"Request {i} failed: {e}")
    
    conn.close()
    
    # Validate: denied requests should have retry-after set
    if denied_count == 0:
        issues.append(f"No requests were denied (expected at least 3)")
    elif len(retry_after_values) == 0:
        issues.append("No retry-after values found on denied requests")
    elif any(v <= 0 for v in retry_after_values):
        issues.append(f"Invalid retry-after values: {retry_after_values}")
    
    passed = len(issues) == 0
    
    print(f"  Denied: {denied_count}, Retry-after values: {retry_after_values}")
    print(f"  {'✓ PASS' if passed else '✗ FAIL'}")
    
    return EdgeCaseResult(
        test_name=test_name,
        passed=passed,
        expected_allowed=0,
        expected_denied=5,
        actual_allowed=0,
        actual_denied=denied_count,
        issues=issues,
        details={"retry_after_values": retry_after_values, "denied_count": denied_count}
    )


def test_gradual_refill(parsed, algo_config: AlgorithmConfig) -> EdgeCaseResult:
    """Test that tokens refill gradually over time."""
    test_name = "Gradual Refill Test"
    print(f"Running: {test_name}...")
    
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    key = f"edgecase:refill:{int(time.time())}"
    issues = []
    
    capacity = 10
    refill_rate = 5.0  # 5 tokens per second
    refill_period = 1
    
    setup_key_config(conn, key, algo_config.name, capacity, refill_rate, refill_period)
    
    # Exhaust the bucket
    allowed_initial = 0
    for i in range(capacity):
        payload = json.dumps({
            "key": key,
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        status, body, _ = make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
        if status == 200 and json.loads(body).get("allowed"):
            allowed_initial += 1
    
    # Wait for refill (2 seconds should add ~10 tokens at 5/sec)
    print(f"  Waiting 2s for refill...")
    time.sleep(2)
    
    # Try requests again
    allowed_after_refill = 0
    for i in range(capacity):
        payload = json.dumps({
            "key": key,
            "tokens": 1,
            "clientIp": "127.0.0.1",
            "endpoint": "/api/test",
        })
        headers = {"Content-Type": "application/json"}
        status, body, _ = make_http_request(conn, "POST", "/api/ratelimit/check", payload, headers)
        if status == 200 and json.loads(body).get("allowed"):
            allowed_after_refill += 1
    
    conn.close()
    
    # Validate: after refill, we should be able to make more requests
    expected_refilled = int(refill_rate * 2)  # ~10 tokens in 2 seconds
    
    if allowed_after_refill < expected_refilled * 0.7:  # Allow 30% margin
        issues.append(f"Expected ~{expected_refilled} allowed after refill, got {allowed_after_refill}")
    
    if allowed_initial != capacity:
        issues.append(f"Initial bucket not full: expected {capacity}, got {allowed_initial}")
    
    passed = len(issues) == 0
    
    print(f"  Initial allowed: {allowed_initial}, After refill: {allowed_after_refill}")
    print(f"  {'✓ PASS' if passed else '✗ FAIL'}")
    
    return EdgeCaseResult(
        test_name=test_name,
        passed=passed,
        expected_allowed=expected_refilled,
        expected_denied=0,
        actual_allowed=allowed_after_refill,
        actual_denied=0,
        issues=issues,
        details={
            "initial_allowed": allowed_initial,
            "after_refill_allowed": allowed_after_refill,
            "refill_rate": refill_rate,
            "wait_time_seconds": 2
        }
    )


def setup_key_config(conn: HTTPConnection, key: str, algorithm: str, capacity: int, refill_rate: float, refill_period: int):
    """Helper to configure a specific key for testing."""
    path = f"/api/ratelimit/config/keys/{key}"
    payload = json.dumps({
        "algorithm": algorithm,
        "capacity": capacity,
        "refillRate": refill_rate,
        "refillPeriodSeconds": refill_period,
    })
    headers = {"Content-Type": "application/json"}
    
    try:
        status, body, resp_headers = make_http_request(conn, "POST", path, payload, headers)
        if status != 200 and status != 201:
            print(f"  ⚠ Warning: Failed to configure key {key}: HTTP {status}")
            if body:
                print(f"    Response: {body[:200]}")
        else:
            # Verify configuration was applied by reading it back
            verify_status, verify_body, _ = make_http_request(conn, "GET", f"/api/ratelimit/config/{key}", None, {})
            if verify_status == 200:
                config_data = json.loads(verify_body)
                actual_capacity = config_data.get("capacity")
                actual_refill = config_data.get("refillRate")
                if actual_capacity != capacity or abs(actual_refill - refill_rate) > 0.001:
                    print(f"  ⚠ Warning: Config mismatch for {key}")
                    print(f"    Expected: capacity={capacity}, refillRate={refill_rate}")
                    print(f"    Actual: capacity={actual_capacity}, refillRate={actual_refill}")
    except Exception as e:
        print(f"  ⚠ Warning: Error configuring key {key}: {e}")


# ============================================================================
# Statistics Computation
# ============================================================================

def compute_percentile(sorted_values: List[float], percentile: float) -> float:
    """Compute percentile from sorted values."""
    if not sorted_values:
        return 0.0
    k = int((len(sorted_values) - 1) * percentile)
    return sorted_values[k]


def aggregate_metrics(metrics: List[RequestMetric], duration_seconds: int) -> AggregateStats:
    """Compute aggregate statistics from metrics."""
    if not metrics:
        return AggregateStats(
            total=0, allowed=0, denied=0, errors=0, rps=0.0,
            min_ms=0.0, max_ms=0.0, avg_ms=0.0, median_ms=0.0,
            p50_ms=0.0, p75_ms=0.0, p90_ms=0.0, p95_ms=0.0, p99_ms=0.0, p999_ms=0.0,
            std_dev_ms=0.0, success_rate=0.0, error_rate=0.0
        )
    
    total = len(metrics)
    allowed = sum(1 for m in metrics if m.allowed is True)
    denied = sum(1 for m in metrics if m.allowed is False)
    errors = sum(1 for m in metrics if m.error is not None)
    
    # Latency statistics
    latencies = [m.latency_ms for m in metrics if m.error is None]
    latencies.sort()
    
    min_ms = min(latencies) if latencies else 0.0
    max_ms = max(latencies) if latencies else 0.0
    avg_ms = statistics.mean(latencies) if latencies else 0.0
    median_ms = statistics.median(latencies) if latencies else 0.0
    std_dev_ms = statistics.stdev(latencies) if len(latencies) > 1 else 0.0
    
    success_rate = (total - errors) / total * 100 if total > 0 else 0.0
    error_rate = errors / total * 100 if total > 0 else 0.0
    
    return AggregateStats(
        total=total,
        allowed=allowed,
        denied=denied,
        errors=errors,
        rps=total / max(duration_seconds, 1),
        min_ms=min_ms,
        max_ms=max_ms,
        avg_ms=avg_ms,
        median_ms=median_ms,
        p50_ms=compute_percentile(latencies, 0.50),
        p75_ms=compute_percentile(latencies, 0.75),
        p90_ms=compute_percentile(latencies, 0.90),
        p95_ms=compute_percentile(latencies, 0.95),
        p99_ms=compute_percentile(latencies, 0.99),
        p999_ms=compute_percentile(latencies, 0.999),
        std_dev_ms=std_dev_ms,
        success_rate=success_rate,
        error_rate=error_rate,
    )


# ============================================================================
# Reporting
# ============================================================================

def build_timeline(metrics: List[RequestMetric]) -> Dict[str, Any]:
    """Build per-second timeline of metrics."""
    buckets: Dict[int, List[RequestMetric]] = defaultdict(list)
    
    for m in metrics:
        sec = m.timestamp_ms // 1000
        buckets[sec].append(m)
    
    timeline = {}
    for sec, bucket in sorted(buckets.items()):
        agg = aggregate_metrics(bucket, 1)
        timeline[str(sec)] = {
            "rps": agg.rps,
            "allowed": agg.allowed,
            "denied": agg.denied,
            "errors": agg.errors,
            "p50_ms": agg.p50_ms,
            "p95_ms": agg.p95_ms,
            "p99_ms": agg.p99_ms,
            "avg_ms": agg.avg_ms,
        }
    
    return timeline


def generate_reports(
    config: TestConfig,
    all_metrics: List[RequestMetric],
    duration_seconds: int,
    burst_metrics: Optional[List[RequestMetric]] = None,
    benchmark_results: Optional[Dict[str, Any]] = None,
    performance_results: Optional[Dict[str, Any]] = None,
    health_check: Optional[HealthCheckResult] = None,
    edge_case_results: Optional[List[EdgeCaseResult]] = None,
) -> None:
    """Generate comprehensive reports."""
    output_dir = config.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"\n{'='*80}")
    print(f"Generating Reports...")
    print(f"{'='*80}\n")
    
    # Overall summary
    overall_stats = aggregate_metrics(all_metrics, duration_seconds)
    
    # Per-algorithm breakdown
    algorithm_metrics = defaultdict(list)
    for m in all_metrics:
        algorithm_metrics[m.algorithm].append(m)
    
    algorithm_stats = {}
    for algo, metrics in algorithm_metrics.items():
        algorithm_stats[algo] = aggregate_metrics(metrics, duration_seconds)
    
    # Per-endpoint breakdown
    endpoint_metrics = defaultdict(list)
    for m in all_metrics:
        endpoint_metrics[m.endpoint].append(m)
    
    endpoint_stats = {}
    for endpoint, metrics in endpoint_metrics.items():
        endpoint_stats[endpoint] = aggregate_metrics(metrics, duration_seconds)
    
    # Per-algorithm-per-endpoint breakdown
    algo_endpoint_metrics = defaultdict(lambda: defaultdict(list))
    for m in all_metrics:
        algo_endpoint_metrics[m.algorithm][m.endpoint].append(m)
    
    algo_endpoint_stats = {}
    for algo, endpoints in algo_endpoint_metrics.items():
        algo_endpoint_stats[algo] = {}
        for endpoint, metrics in endpoints.items():
            algo_endpoint_stats[algo][endpoint] = aggregate_metrics(metrics, duration_seconds)
    
    # Burst test analysis
    burst_analysis = None
    if burst_metrics:
        burst_stats = aggregate_metrics(burst_metrics, 30)
        burst_analysis = {
            "overall": stats_to_dict(burst_stats),
            "degradation_vs_normal": {
                "latency_p95_increase_pct": ((burst_stats.p95_ms - overall_stats.p95_ms) / overall_stats.p95_ms * 100) if overall_stats.p95_ms > 0 else 0,
                "latency_p99_increase_pct": ((burst_stats.p99_ms - overall_stats.p99_ms) / overall_stats.p99_ms * 100) if overall_stats.p99_ms > 0 else 0,
                "error_rate_increase_pct": burst_stats.error_rate - overall_stats.error_rate,
            }
        }
    
    # Edge case test results
    edge_case_summary = None
    if edge_case_results:
        passed_count = sum(1 for r in edge_case_results if r.passed)
        edge_case_summary = {
            "total_tests": len(edge_case_results),
            "passed": passed_count,
            "failed": len(edge_case_results) - passed_count,
            "tests": [
                {
                    "name": r.test_name,
                    "passed": r.passed,
                    "expected_allowed": r.expected_allowed,
                    "expected_denied": r.expected_denied,
                    "actual_allowed": r.actual_allowed,
                    "actual_denied": r.actual_denied,
                    "issues": r.issues,
                    "details": r.details
                }
                for r in edge_case_results
            ]
        }
    
    # Build comprehensive summary
    summary = {
        "test_info": {
            "timestamp": datetime.now().isoformat(),
            "duration_seconds": duration_seconds,
            "concurrency": config.concurrency,
            "target_rps": config.target_rps,
            "base_url": config.base_url,
        },
        "health_check": {
            "passed": health_check.passed if health_check else None,
            "redis_latency_ms": health_check.redis_latency_ms if health_check else None,
            "active_keys": health_check.active_keys if health_check else None,
            "issues": health_check.issues if health_check else [],
        } if health_check else None,
        "edge_case_tests": edge_case_summary,
        "overall": stats_to_dict(overall_stats),
        "by_algorithm": {algo: stats_to_dict(stats) for algo, stats in algorithm_stats.items()},
        "by_endpoint": {endpoint: stats_to_dict(stats) for endpoint, stats in endpoint_stats.items()},
        "by_algorithm_and_endpoint": {
            algo: {
                endpoint: stats_to_dict(stats)
                for endpoint, stats in endpoints.items()
            }
            for algo, endpoints in algo_endpoint_stats.items()
        },
        "burst_test": burst_analysis,
        "benchmark_results": benchmark_results,
        "performance_results": performance_results,
    }
    
    # Write summary JSON
    summary_path = output_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2))
    print(f"✓ Summary written to: {summary_path}")
    
    # Write timeline
    timeline = build_timeline(all_metrics)
    timeline_path = output_dir / "timeline.json"
    timeline_path.write_text(json.dumps(timeline, indent=2))
    print(f"✓ Timeline written to: {timeline_path}")
    
    # Write per-algorithm timelines
    for algo, metrics in algorithm_metrics.items():
        algo_timeline = build_timeline(metrics)
        algo_timeline_path = output_dir / f"timeline_{algo.lower()}.json"
        algo_timeline_path.write_text(json.dumps(algo_timeline, indent=2))
        print(f"✓ {algo} timeline written to: {algo_timeline_path}")
    
    # Write burst timeline if exists
    if burst_metrics:
        burst_timeline = build_timeline(burst_metrics)
        burst_timeline_path = output_dir / "timeline_burst.json"
        burst_timeline_path.write_text(json.dumps(burst_timeline, indent=2))
        print(f"✓ Burst timeline written to: {burst_timeline_path}")
    
    # Write raw CSV if requested
    if config.raw_csv:
        csv_path = output_dir / "raw_metrics.csv"
        with csv_path.open("w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow([
                "timestamp_ms", "latency_ms", "status_code", "allowed", "error",
                "key", "endpoint", "algorithm", "tokens", "remaining_tokens", "retry_after"
            ])
            for m in all_metrics:
                writer.writerow([
                    m.timestamp_ms, f"{m.latency_ms:.3f}", m.status_code, m.allowed, m.error,
                    m.key, m.endpoint, m.algorithm, m.tokens, m.remaining_tokens, m.retry_after
                ])
        print(f"✓ Raw metrics CSV written to: {csv_path}")
    
    # Print console summary
    print_console_summary(overall_stats, algorithm_stats, endpoint_stats, burst_analysis, performance_results)


def stats_to_dict(stats: AggregateStats) -> Dict[str, Any]:
    """Convert AggregateStats to dictionary."""
    return {
        "total_requests": stats.total,
        "allowed": stats.allowed,
        "denied": stats.denied,
        "errors": stats.errors,
        "rps": round(stats.rps, 2),
        "success_rate_percent": round(stats.success_rate, 2),
        "error_rate_percent": round(stats.error_rate, 2),
        "latency_ms": {
            "min": round(stats.min_ms, 3),
            "max": round(stats.max_ms, 3),
            "avg": round(stats.avg_ms, 3),
            "median": round(stats.median_ms, 3),
            "p50": round(stats.p50_ms, 3),
            "p75": round(stats.p75_ms, 3),
            "p90": round(stats.p90_ms, 3),
            "p95": round(stats.p95_ms, 3),
            "p99": round(stats.p99_ms, 3),
            "p999": round(stats.p999_ms, 3),
            "std_dev": round(stats.std_dev_ms, 3),
        }
    }


def print_console_summary(
    overall: AggregateStats,
    by_algorithm: Dict[str, AggregateStats],
    by_endpoint: Dict[str, AggregateStats],
    burst_analysis: Optional[Dict[str, Any]] = None,
    performance_results: Optional[Dict[str, Any]] = None,
) -> None:
    """Print formatted summary to console."""
    print(f"\n{'='*80}")
    print(f"OVERALL RESULTS")
    print(f"{'='*80}")
    print(f"Total Requests:    {overall.total:,}")
    print(f"Allowed:           {overall.allowed:,} ({overall.allowed/overall.total*100:.1f}%)" if overall.total > 0 else "Allowed:           0")
    print(f"Denied:            {overall.denied:,} ({overall.denied/overall.total*100:.1f}%)" if overall.total > 0 else "Denied:            0")
    print(f"Errors:            {overall.errors:,} ({overall.error_rate:.2f}%)")
    print(f"Throughput:        {overall.rps:.2f} req/s")
    print(f"Success Rate:      {overall.success_rate:.2f}%")
    print(f"\nLatency (ms):")
    print(f"  Min:             {overall.min_ms:.3f}")
    print(f"  Avg:             {overall.avg_ms:.3f}")
    print(f"  Median:          {overall.median_ms:.3f}")
    print(f"  P95:             {overall.p95_ms:.3f}")
    print(f"  P99:             {overall.p99_ms:.3f}")
    print(f"  P99.9:           {overall.p999_ms:.3f}")
    print(f"  Max:             {overall.max_ms:.3f}")
    print(f"  Std Dev:         {overall.std_dev_ms:.3f}")
    
    print(f"\n{'='*80}")
    print(f"BY ALGORITHM")
    print(f"{'='*80}")
    for algo, stats in sorted(by_algorithm.items()):
        print(f"\n{algo}:")
        print(f"  Requests:        {stats.total:,}")
        print(f"  Allowed:         {stats.allowed:,}")
        print(f"  Denied:          {stats.denied:,}")
        print(f"  Errors:          {stats.errors:,}")
        print(f"  RPS:             {stats.rps:.2f}")
        print(f"  Avg Latency:     {stats.avg_ms:.3f} ms")
        print(f"  P95 Latency:     {stats.p95_ms:.3f} ms")
        print(f"  P99 Latency:     {stats.p99_ms:.3f} ms")
    
    print(f"\n{'='*80}")
    print(f"BY ENDPOINT")
    print(f"{'='*80}")
    for endpoint, stats in sorted(by_endpoint.items()):
        print(f"\n{endpoint}:")
        print(f"  Requests:        {stats.total:,}")
        print(f"  Errors:          {stats.errors:,}")
        print(f"  RPS:             {stats.rps:.2f}")
        print(f"  Avg Latency:     {stats.avg_ms:.3f} ms")
        print(f"  P95 Latency:     {stats.p95_ms:.3f} ms")
        print(f"  P99 Latency:     {stats.p99_ms:.3f} ms")
    
    if burst_analysis:
        print(f"\n{'='*80}")
        print(f"BURST TEST ANALYSIS")
        print(f"{'='*80}")
        overall_burst = burst_analysis["overall"]
        degradation = burst_analysis["degradation_vs_normal"]
        print(f"Total Requests:    {overall_burst['total_requests']:,}")
        print(f"Error Rate:        {overall_burst['error_rate_percent']:.2f}%")
        print(f"P95 Latency:       {overall_burst['latency_ms']['p95']:.3f} ms")
        print(f"P99 Latency:       {overall_burst['latency_ms']['p99']:.3f} ms")
        print(f"\nDegradation vs Normal:")
        print(f"  P95 Increase:    {degradation['latency_p95_increase_pct']:+.1f}%")
        print(f"  P99 Increase:    {degradation['latency_p99_increase_pct']:+.1f}%")
        print(f"  Error Rate Δ:    {degradation['error_rate_increase_pct']:+.2f}%")
    
    if performance_results:
        print(f"\n{'='*80}")
        print(f"PERFORMANCE REGRESSION ANALYSIS")
        print(f"{'='*80}")
        for algo, result in sorted(performance_results.items()):
            status = result.get('status', 'UNKNOWN')
            message = result.get('message', '')
            
            status_symbol = "✓" if status == "OK" else "⚠" if status == "REGRESSION_DETECTED" else "ℹ"
            print(f"\n{status_symbol} {algo}: {status}")
            print(f"  {message}")
    
    print(f"\n{'='*80}\n")


# ============================================================================
# Post-Test Validation
# ============================================================================

def run_post_test_validation(base_url: str, overall_stats: AggregateStats, strict: bool = True) -> bool:
    """Run post-test validation checks."""
    print(f"\n{'='*80}")
    print(f"Running Post-Test Validation")
    print(f"{'='*80}\n")
    
    issues = []
    
    # Check error rate threshold
    if overall_stats.error_rate > 5.0:
        issues.append(f"Error rate too high: {overall_stats.error_rate:.2f}% (threshold: 5%)")
    else:
        print(f"✓ Error rate within acceptable range: {overall_stats.error_rate:.2f}%")
    
    # Check latency thresholds
    if overall_stats.p95_ms > 100.0:
        msg = f"P95 latency too high: {overall_stats.p95_ms:.3f}ms (threshold: 100ms)"
        if strict:
            issues.append(msg)
        else:
            print(f"⚠ {msg}")
    else:
        print(f"✓ P95 latency acceptable: {overall_stats.p95_ms:.3f}ms")
    
    if overall_stats.p99_ms > 200.0:
        msg = f"P99 latency too high: {overall_stats.p99_ms:.3f}ms (threshold: 200ms)"
        if strict:
            issues.append(msg)
        else:
            print(f"⚠ {msg}")
    else:
        print(f"✓ P99 latency acceptable: {overall_stats.p99_ms:.3f}ms")
    
    # Check system health after test
    parsed = urlparse(base_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    
    try:
        status, body, _ = make_http_request(conn, "GET", "/actuator/health", None, {})
        if status != 200:
            issues.append(f"Health endpoint returned {status} after test")
        else:
            data = json.loads(body)
            if data.get("status") != "UP":
                issues.append(f"Service status is {data.get('status')} after test")
            else:
                print(f"✓ System health: UP after test")
    except Exception as e:
        issues.append(f"Failed to reach health endpoint after test: {e}")
    
    conn.close()
    
    passed = len(issues) == 0
    
    if passed:
        print(f"\n✓ All post-test validations PASSED")
    else:
        print(f"\n✗ Post-test validations FAILED:")
        for issue in issues:
            print(f"  - {issue}")
    
    print()
    
    return passed


# ============================================================================
# Configuration Setup
# ============================================================================

def setup_configurations(base_url: str, algorithms: List[AlgorithmConfig]) -> bool:
    """Setup rate limit configurations for all algorithms before testing."""
    print(f"\n{'='*80}")
    print(f"Setting up configurations for {len(algorithms)} algorithms...")
    print(f"{'='*80}\n")
    
    parsed = urlparse(base_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 8080, timeout=10)
    
    success = True
    
    for algo_config in algorithms:
        # Create pattern-based config for this algorithm
        pattern = f"{algo_config.name.lower()}:*"
        path = f"/api/ratelimit/config/patterns/{pattern}"
        
        payload = json.dumps({
            "algorithm": algo_config.name,
            "capacity": algo_config.capacity,
            "refillRate": algo_config.refill_rate,
            "refillPeriodSeconds": algo_config.refill_period_seconds,
        })
        
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
        }
        
        try:
            status, body, _ = make_http_request(conn, "POST", path, payload, headers)
            if status in [200, 201]:
                print(f"✓ Configured {algo_config.name}: capacity={algo_config.capacity}, "
                      f"refillRate={algo_config.refill_rate}, period={algo_config.refill_period_seconds}s")
            else:
                print(f"✗ Failed to configure {algo_config.name}: HTTP {status}")
                success = False
        except Exception as e:
            print(f"✗ Error configuring {algo_config.name}: {e}")
            success = False
    
    conn.close()
    print()
    return success


# ============================================================================
# Main Execution
# ============================================================================

def parse_args() -> argparse.Namespace:
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Comprehensive E2E load test for rate limiter system",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--base-url", default="http://localhost:8080", help="Base URL of the service")
    parser.add_argument("--duration-seconds", type=int, default=180, help="Test duration in seconds")
    parser.add_argument("--warmup-seconds", type=int, default=10, help="Warmup duration in seconds")
    parser.add_argument("--concurrency", type=int, default=100, help="Number of concurrent workers")
    parser.add_argument("--target-rps", type=int, default=1000, help="Target requests per second")
    parser.add_argument("--output-dir", default="data/comprehensive_loadtest", help="Output directory")
    parser.add_argument("--raw-csv", action="store_true", help="Generate raw metrics CSV")
    parser.add_argument("--seed", type=int, default=42, help="Random seed for reproducibility")
    parser.add_argument("--skip-setup", action="store_true", help="Skip configuration setup")
    parser.add_argument("--skip-burst-test", action="store_true", help="Skip burst testing")
    parser.add_argument("--skip-benchmark-test", action="store_true", help="Skip benchmark testing")
    parser.add_argument("--skip-performance-test", action="store_true", help="Skip performance regression testing")
    parser.add_argument("--skip-health-checks", action="store_true", help="Skip health checks")
    parser.add_argument("--strict-validation", action="store_true", help="Enable strict post-test validation")
    return parser.parse_args()


def main() -> None:
    """Main execution function."""
    args = parse_args()
    random.seed(args.seed)
    
    # Build test configuration
    config = TestConfig(
        base_url=args.base_url,
        duration_seconds=args.duration_seconds,
        warmup_seconds=args.warmup_seconds,
        concurrency=args.concurrency,
        target_rps=args.target_rps,
        output_dir=Path(args.output_dir),
        raw_csv=args.raw_csv,
        seed=args.seed,
        algorithms=DEFAULT_ALGORITHM_CONFIGS,
        include_burst_test=not args.skip_burst_test,
        include_benchmark_test=not args.skip_benchmark_test,
        include_performance_test=not args.skip_performance_test,
        strict_validation=args.strict_validation,
    )
    
    print(f"\n{'='*80}")
    print(f"COMPREHENSIVE E2E RATE LIMITER LOAD TEST")
    print(f"{'='*80}")
    print(f"Base URL:          {config.base_url}")
    print(f"Duration:          {config.duration_seconds}s")
    print(f"Warmup:            {config.warmup_seconds}s")
    print(f"Concurrency:       {config.concurrency}")
    print(f"Target RPS:        {config.target_rps}")
    print(f"Algorithms:        {', '.join(a.name for a in config.algorithms)}")
    print(f"Output Dir:        {config.output_dir}")
    print(f"Burst Test:        {'Enabled' if config.include_burst_test else 'Disabled'}")
    print(f"Benchmark Test:    {'Enabled' if config.include_benchmark_test else 'Disabled'}")
    print(f"Performance Test:  {'Enabled' if config.include_performance_test else 'Disabled'}")
    print(f"{'='*80}\n")
    
    # Pre-flight health checks
    health_check = None
    if not args.skip_health_checks:
        health_check = run_health_checks(config.base_url)
        if not health_check.passed and config.strict_validation:
            print("✗ Pre-flight health checks failed. Exiting...")
            sys.exit(1)
    
    # Setup configurations
    if not args.skip_setup:
        if not setup_configurations(config.base_url, config.algorithms):
            print("⚠ Warning: Some configurations failed to setup. Continuing anyway...")
    
    # Warmup
    if config.warmup_seconds > 0:
        print(f"Warming up for {config.warmup_seconds}s...")
        time.sleep(config.warmup_seconds)
        print("Warmup complete.\n")
    
    # Run edge case tests (validate rate limiting correctness)
    edge_case_results = run_edge_case_tests(config)
    
    # Run main load test
    print(f"{'='*80}")
    print(f"Starting Main Load Test...")
    print(f"{'='*80}\n")
    
    metrics: List[RequestMetric] = []
    lock = threading.Lock()
    
    start_time = time.time()
    
    with ThreadPoolExecutor(max_workers=config.concurrency) as executor:
        futures = []
        for i in range(config.concurrency):
            future = executor.submit(worker_thread, i, config, metrics, lock)
            futures.append(future)
        
        # Wait for all workers to complete
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as e:
                print(f"Worker error: {e}")
    
    end_time = time.time()
    actual_duration = int(end_time - start_time)
    
    print(f"\n✓ Main load test completed in {actual_duration}s")
    print(f"  Collected {len(metrics):,} metrics\n")
    
    # Run burst test
    burst_metrics = None
    if config.include_burst_test:
        burst_metrics = run_burst_test(config)
    
    # Run benchmark tests
    benchmark_results = None
    if config.include_benchmark_test:
        benchmark_results = run_benchmark_tests(config)
    
    # Run performance regression tests
    performance_results = None
    if config.include_performance_test:
        performance_results = run_performance_tests(config)
    
    # Generate reports
    generate_reports(
        config, 
        metrics, 
        actual_duration, 
        burst_metrics=burst_metrics,
        benchmark_results=benchmark_results,
        performance_results=performance_results,
        health_check=health_check,
        edge_case_results=edge_case_results,
    )
    
    # Post-test validation
    overall_stats = aggregate_metrics(metrics, actual_duration)
    validation_passed = run_post_test_validation(config.base_url, overall_stats, config.strict_validation)
    
    print(f"\n{'='*80}")
    if validation_passed:
        print(f"✓ TEST SUITE COMPLETED SUCCESSFULLY!")
    else:
        print(f"⚠ TEST SUITE COMPLETED WITH WARNINGS")
    print(f"Results saved to: {config.output_dir}")
    print(f"{'='*80}\n")
    
    # Exit with appropriate code
    if config.strict_validation and not validation_passed:
        sys.exit(1)


if __name__ == "__main__":
    main()
