#!/usr/bin/env python3
"""Comprehensive load test for the entire rate limiter system.

This script tests:
- All 5 rate limiting algorithms (TOKEN_BUCKET, SLIDING_WINDOW, SLIDING_WINDOW_COUNTER, FIXED_WINDOW, LEAKY_BUCKET)
- All API endpoints (rate limit check, admin, benchmark, config)
- Provides detailed metrics per algorithm and per endpoint
- Simulates realistic user traffic patterns
- Generates comprehensive reports with visualizations data

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
    algorithms: List[AlgorithmConfig] = field(default_factory=list)


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
        "weight": 0.70,  # 70% of traffic
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
}


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
    
    # Build comprehensive summary
    summary = {
        "test_info": {
            "timestamp": datetime.now().isoformat(),
            "duration_seconds": duration_seconds,
            "concurrency": config.concurrency,
            "target_rps": config.target_rps,
            "base_url": config.base_url,
        },
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
    print_console_summary(overall_stats, algorithm_stats, endpoint_stats)


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
) -> None:
    """Print formatted summary to console."""
    print(f"\n{'='*80}")
    print(f"OVERALL RESULTS")
    print(f"{'='*80}")
    print(f"Total Requests:    {overall.total:,}")
    print(f"Allowed:           {overall.allowed:,} ({overall.allowed/overall.total*100:.1f}%)")
    print(f"Denied:            {overall.denied:,} ({overall.denied/overall.total*100:.1f}%)")
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
    
    print(f"\n{'='*80}\n")


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
        description="Comprehensive load test for rate limiter system",
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
    )
    
    print(f"\n{'='*80}")
    print(f"COMPREHENSIVE RATE LIMITER LOAD TEST")
    print(f"{'='*80}")
    print(f"Base URL:          {config.base_url}")
    print(f"Duration:          {config.duration_seconds}s")
    print(f"Warmup:            {config.warmup_seconds}s")
    print(f"Concurrency:       {config.concurrency}")
    print(f"Target RPS:        {config.target_rps}")
    print(f"Algorithms:        {', '.join(a.name for a in config.algorithms)}")
    print(f"Output Dir:        {config.output_dir}")
    print(f"{'='*80}\n")
    
    # Setup configurations
    if not args.skip_setup:
        if not setup_configurations(config.base_url, config.algorithms):
            print("⚠ Warning: Some configurations failed to setup. Continuing anyway...")
    
    # Warmup
    if config.warmup_seconds > 0:
        print(f"Warming up for {config.warmup_seconds}s...")
        time.sleep(config.warmup_seconds)
        print("Warmup complete.\n")
    
    # Run load test
    print(f"{'='*80}")
    print(f"Starting load test...")
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
    
    print(f"\nLoad test completed in {actual_duration}s")
    print(f"Collected {len(metrics):,} metrics\n")
    
    # Generate reports
    generate_reports(config, metrics, actual_duration)
    
    print(f"\n{'='*80}")
    print(f"Test completed successfully!")
    print(f"Results saved to: {config.output_dir}")
    print(f"{'='*80}\n")


if __name__ == "__main__":
    main()
