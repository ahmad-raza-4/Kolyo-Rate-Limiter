#!/usr/bin/env python3
"""Full-system load test for rate limiter API.

Simulates a heavy-traffic e-commerce site (nike.com-like) with mixed traffic patterns,
collects latency/throughput/allow/deny/error metrics, and stores results to disk.

Usage:
  python load_testing/full_system_load_test.py \
    --base-url http://localhost:8080 \
    --duration-seconds 120 \
    --concurrency 200 \
    --target-rps 1500 \
    --output-dir data/loadtest

Notes:
- Requires the app to be running locally.
- Uses standard library only (no external deps).
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from http.client import HTTPConnection
from pathlib import Path
from typing import Dict, List, Tuple
from urllib.parse import urlparse


@dataclass
class RequestMetric:
    ts_ms: int
    latency_ms: float
    status: int
    allowed: bool | None
    error: str | None
    key: str
    endpoint: str
    tokens: int


@dataclass
class AggregateStats:
    total: int
    allowed: int
    denied: int
    errors: int
    rps: float
    min_ms: float
    max_ms: float
    avg_ms: float
    p50_ms: float
    p95_ms: float
    p99_ms: float


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Full-system load test for rate limiter API")
    parser.add_argument("--base-url", default="http://localhost:8080", help="Base URL of the app")
    parser.add_argument("--duration-seconds", type=int, default=120, help="Test duration in seconds")
    parser.add_argument("--warmup-seconds", type=int, default=10, help="Warmup duration in seconds")
    parser.add_argument("--concurrency", type=int, default=200, help="Concurrent worker threads")
    parser.add_argument("--target-rps", type=int, default=1500, help="Target requests per second")
    parser.add_argument("--output-dir", default="data/loadtest", help="Directory to store metrics")
    parser.add_argument("--raw-csv", action="store_true", help="Store per-request metrics CSV")
    parser.add_argument("--seed", type=int, default=42, help="Random seed")
    return parser.parse_args()


def compute_percentile(sorted_values: List[float], percentile: float) -> float:
    if not sorted_values:
        return 0.0
    k = int((len(sorted_values) - 1) * percentile)
    return sorted_values[k]


def aggregate_metrics(metrics: List[RequestMetric], duration_seconds: int) -> AggregateStats:
    total = len(metrics)
    allowed = sum(1 for m in metrics if m.allowed is True)
    denied = sum(1 for m in metrics if m.allowed is False)
    errors = sum(1 for m in metrics if m.error is not None)

    latencies = [m.latency_ms for m in metrics if m.error is None]
    latencies.sort()
    min_ms = min(latencies) if latencies else 0.0
    max_ms = max(latencies) if latencies else 0.0
    avg_ms = statistics.mean(latencies) if latencies else 0.0

    return AggregateStats(
        total=total,
        allowed=allowed,
        denied=denied,
        errors=errors,
        rps=total / max(duration_seconds, 1),
        min_ms=min_ms,
        max_ms=max_ms,
        avg_ms=avg_ms,
        p50_ms=compute_percentile(latencies, 0.50),
        p95_ms=compute_percentile(latencies, 0.95),
        p99_ms=compute_percentile(latencies, 0.99),
    )


def build_request_payload(key: str, endpoint: str, tokens: int, client_ip: str) -> str:
    return json.dumps(
        {
            "key": key,
            "tokens": tokens,
            "clientIp": client_ip,
            "endpoint": endpoint,
        }
    )


def make_request(
    conn: HTTPConnection,
    path: str,
    payload: str,
    headers: Dict[str, str],
) -> Tuple[int, str]:
    conn.request("POST", path, body=payload, headers=headers)
    response = conn.getresponse()
    data = response.read().decode("utf-8")
    return response.status, data


def traffic_profile(rnd: random.Random) -> Tuple[str, str, int, str]:
    """Simulate a nike.com-like traffic pattern.

    Returns: (key, endpoint, tokens, client_ip)
    """
    endpoints = [
        ("/product", 0.40),
        ("/search", 0.20),
        ("/cart", 0.10),
        ("/checkout", 0.05),
        ("/home", 0.25),
    ]
    users = [
        ("user:guest", 0.70),
        ("user:registered", 0.25),
        ("user:vip", 0.05),
    ]
    token_weights = [
        (1, 0.80),
        (2, 0.15),
        (5, 0.05),
    ]

    endpoint = weighted_choice(rnd, endpoints)
    user_tier = weighted_choice(rnd, users)
    tokens = weighted_choice(rnd, token_weights)

    user_id = rnd.randint(1, 50000)
    key = f"{user_tier}:{user_id}"
    client_ip = f"10.{rnd.randint(0,255)}.{rnd.randint(0,255)}.{rnd.randint(1,254)}"
    return key, endpoint, tokens, client_ip


def weighted_choice(rnd: random.Random, items: List[Tuple[str | int, float]]):
    r = rnd.random()
    acc = 0.0
    for value, weight in items:
        acc += weight
        if r <= acc:
            return value
    return items[-1][0]


def worker(
    worker_id: int,
    base_url: str,
    duration_seconds: int,
    target_rps: int,
    metrics: List[RequestMetric],
    lock: threading.Lock,
    seed: int,
) -> None:
    rnd = random.Random(seed + worker_id)
    parsed = urlparse(base_url)
    conn = HTTPConnection(parsed.hostname, parsed.port or 80, timeout=5)
    path = "/api/ratelimit/check"
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
    }

    start = time.time()
    interval = 1.0 / max(target_rps, 1)

    while time.time() - start < duration_seconds:
        key, endpoint, tokens, client_ip = traffic_profile(rnd)
        payload = build_request_payload(key, endpoint, tokens, client_ip)

        t0 = time.time()
        status = 0
        allowed = None
        error = None
        try:
            status, body = make_request(conn, path, payload, headers)
            if body:
                data = json.loads(body)
                allowed = bool(data.get("allowed"))
        except Exception as exc:  # noqa: BLE001
            error = str(exc)
        t1 = time.time()

        metric = RequestMetric(
            ts_ms=int(t0 * 1000),
            latency_ms=(t1 - t0) * 1000.0,
            status=status,
            allowed=allowed,
            error=error,
            key=key,
            endpoint=endpoint,
            tokens=tokens,
        )

        with lock:
            metrics.append(metric)

        # best-effort pacing
        elapsed = time.time() - t0
        sleep_time = max(0.0, interval - elapsed)
        if sleep_time > 0:
            time.sleep(sleep_time)

    conn.close()


def write_outputs(
    output_dir: Path,
    metrics: List[RequestMetric],
    duration_seconds: int,
    raw_csv: bool,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)

    aggregate = aggregate_metrics(metrics, duration_seconds)
    summary = {
        "durationSeconds": duration_seconds,
        "totalRequests": aggregate.total,
        "allowed": aggregate.allowed,
        "denied": aggregate.denied,
        "errors": aggregate.errors,
        "rps": aggregate.rps,
        "latencyMs": {
            "min": aggregate.min_ms,
            "max": aggregate.max_ms,
            "avg": aggregate.avg_ms,
            "p50": aggregate.p50_ms,
            "p95": aggregate.p95_ms,
            "p99": aggregate.p99_ms,
        },
    }

    summary_path = output_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2))

    timeline = build_timeline(metrics)
    timeline_path = output_dir / "timeline.json"
    timeline_path.write_text(json.dumps(timeline, indent=2))

    if raw_csv:
        csv_path = output_dir / "requests.csv"
        with csv_path.open("w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow([
                "ts_ms",
                "latency_ms",
                "status",
                "allowed",
                "error",
                "key",
                "endpoint",
                "tokens",
            ])
            for m in metrics:
                writer.writerow([
                    m.ts_ms,
                    f"{m.latency_ms:.3f}",
                    m.status,
                    m.allowed,
                    m.error,
                    m.key,
                    m.endpoint,
                    m.tokens,
                ])


def build_timeline(metrics: List[RequestMetric]) -> Dict[str, Dict[str, float]]:
    """Aggregate metrics per second for a simple timeline."""
    buckets: Dict[int, List[RequestMetric]] = {}
    for m in metrics:
        sec = m.ts_ms // 1000
        buckets.setdefault(sec, []).append(m)

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
        }
    return timeline


def main() -> None:
    args = parse_args()
    random.seed(args.seed)

    # Warmup
    if args.warmup_seconds > 0:
        print(f"Warmup for {args.warmup_seconds}s...")
        time.sleep(args.warmup_seconds)

    metrics: List[RequestMetric] = []
    lock = threading.Lock()

    per_worker_rps = max(1, args.target_rps // max(args.concurrency, 1))

    print(
        f"Running load test: duration={args.duration_seconds}s, "
        f"concurrency={args.concurrency}, targetRPS={args.target_rps}"
    )

    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = []
        for i in range(args.concurrency):
            futures.append(
                executor.submit(
                    worker,
                    i,
                    args.base_url,
                    args.duration_seconds,
                    per_worker_rps,
                    metrics,
                    lock,
                    args.seed,
                )
            )
        for f in futures:
            f.result()

    output_dir = Path(args.output_dir)
    write_outputs(output_dir, metrics, args.duration_seconds, args.raw_csv)

    summary = aggregate_metrics(metrics, args.duration_seconds)
    print(
        "Done. Summary: "
        f"total={summary.total}, allowed={summary.allowed}, denied={summary.denied}, "
        f"errors={summary.errors}, rps={summary.rps:.2f}, "
        f"p95={summary.p95_ms:.2f}ms, p99={summary.p99_ms:.2f}ms"
    )


if __name__ == "__main__":
    main()
