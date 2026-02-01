#!/usr/bin/env python3
"""
Quick analysis script for load test results.

Usage:
    python load_testing/analyze_results.py data/comprehensive_loadtest
"""
import argparse
import json
import sys
from pathlib import Path
from typing import Dict, Any


def load_json(path: Path) -> Dict[str, Any]:
    """Load JSON file."""
    with path.open('r') as f:
        return json.load(f)


def print_section(title: str, width: int = 80):
    """Print a section header."""
    print(f"\n{'='*width}")
    print(f"{title.center(width)}")
    print(f"{'='*width}\n")


def format_latency(latency_dict: Dict[str, float]) -> str:
    """Format latency statistics."""
    return (
        f"  Min:    {latency_dict['min']:>8.3f} ms\n"
        f"  Avg:    {latency_dict['avg']:>8.3f} ms\n"
        f"  Median: {latency_dict['median']:>8.3f} ms\n"
        f"  P75:    {latency_dict['p75']:>8.3f} ms\n"
        f"  P90:    {latency_dict['p90']:>8.3f} ms\n"
        f"  P95:    {latency_dict['p95']:>8.3f} ms\n"
        f"  P99:    {latency_dict['p99']:>8.3f} ms\n"
        f"  P99.9:  {latency_dict['p999']:>8.3f} ms\n"
        f"  Max:    {latency_dict['max']:>8.3f} ms\n"
        f"  StdDev: {latency_dict['std_dev']:>8.3f} ms"
    )


def analyze_summary(summary_path: Path):
    """Analyze and display summary statistics."""
    data = load_json(summary_path)
    
    # Test Info
    print_section("TEST INFORMATION")
    test_info = data['test_info']
    print(f"Timestamp:         {test_info['timestamp']}")
    print(f"Duration:          {test_info['duration_seconds']}s")
    print(f"Concurrency:       {test_info['concurrency']}")
    print(f"Target RPS:        {test_info['target_rps']}")
    print(f"Base URL:          {test_info['base_url']}")
    
    # Overall Stats
    print_section("OVERALL STATISTICS")
    overall = data['overall']
    print(f"Total Requests:    {overall['total_requests']:,}")
    print(f"Allowed:           {overall['allowed']:,} ({overall['allowed']/overall['total_requests']*100:.2f}%)")
    print(f"Denied:            {overall['denied']:,} ({overall['denied']/overall['total_requests']*100:.2f}%)")
    print(f"Errors:            {overall['errors']:,} ({overall['error_rate_percent']:.2f}%)")
    print(f"Actual RPS:        {overall['rps']:.2f}")
    print(f"Success Rate:      {overall['success_rate_percent']:.2f}%")
    print(f"\nLatency Statistics:")
    print(format_latency(overall['latency_ms']))
    
    # Algorithm Comparison
    print_section("ALGORITHM COMPARISON")
    by_algo = data['by_algorithm']
    
    # Header
    print(f"{'Algorithm':<25} {'Requests':>10} {'RPS':>8} {'Avg (ms)':>10} {'P95 (ms)':>10} {'P99 (ms)':>10}")
    print(f"{'-'*25} {'-'*10} {'-'*8} {'-'*10} {'-'*10} {'-'*10}")
    
    # Sort by total requests
    sorted_algos = sorted(by_algo.items(), key=lambda x: x[1]['total_requests'], reverse=True)
    for algo, stats in sorted_algos:
        print(f"{algo:<25} {stats['total_requests']:>10,} {stats['rps']:>8.1f} "
              f"{stats['latency_ms']['avg']:>10.3f} {stats['latency_ms']['p95']:>10.3f} "
              f"{stats['latency_ms']['p99']:>10.3f}")
    
    # Detailed algorithm stats
    print_section("DETAILED ALGORITHM STATISTICS")
    for algo, stats in sorted_algos:
        print(f"\n{algo}:")
        print(f"  Total Requests:  {stats['total_requests']:,}")
        print(f"  Allowed:         {stats['allowed']:,}")
        print(f"  Denied:          {stats['denied']:,}")
        print(f"  Errors:          {stats['errors']:,}")
        print(f"  RPS:             {stats['rps']:.2f}")
        print(f"  Success Rate:    {stats['success_rate_percent']:.2f}%")
        print(f"  Latency:")
        print(format_latency(stats['latency_ms']))
    
    # Endpoint Comparison
    print_section("ENDPOINT COMPARISON")
    by_endpoint = data['by_endpoint']
    
    # Header
    print(f"{'Endpoint':<30} {'Requests':>10} {'RPS':>8} {'Avg (ms)':>10} {'P95 (ms)':>10} {'Errors':>8}")
    print(f"{'-'*30} {'-'*10} {'-'*8} {'-'*10} {'-'*10} {'-'*8}")
    
    # Sort by total requests
    sorted_endpoints = sorted(by_endpoint.items(), key=lambda x: x[1]['total_requests'], reverse=True)
    for endpoint, stats in sorted_endpoints:
        print(f"{endpoint:<30} {stats['total_requests']:>10,} {stats['rps']:>8.1f} "
              f"{stats['latency_ms']['avg']:>10.3f} {stats['latency_ms']['p95']:>10.3f} "
              f"{stats['errors']:>8,}")
    
    # Performance Insights
    print_section("PERFORMANCE INSIGHTS")
    
    # Find fastest and slowest algorithms
    fastest_algo = min(by_algo.items(), key=lambda x: x[1]['latency_ms']['avg'])
    slowest_algo = max(by_algo.items(), key=lambda x: x[1]['latency_ms']['avg'])
    
    print(f"Fastest Algorithm:  {fastest_algo[0]} (avg: {fastest_algo[1]['latency_ms']['avg']:.3f} ms)")
    print(f"Slowest Algorithm:  {slowest_algo[0]} (avg: {slowest_algo[1]['latency_ms']['avg']:.3f} ms)")
    
    # Find most and least used algorithms
    most_used = max(by_algo.items(), key=lambda x: x[1]['total_requests'])
    least_used = min(by_algo.items(), key=lambda x: x[1]['total_requests'])
    
    print(f"\nMost Used:          {most_used[0]} ({most_used[1]['total_requests']:,} requests)")
    print(f"Least Used:         {least_used[0]} ({least_used[1]['total_requests']:,} requests)")
    
    # Find fastest and slowest endpoints
    fastest_endpoint = min(by_endpoint.items(), key=lambda x: x[1]['latency_ms']['avg'])
    slowest_endpoint = max(by_endpoint.items(), key=lambda x: x[1]['latency_ms']['avg'])
    
    print(f"\nFastest Endpoint:   {fastest_endpoint[0]} (avg: {fastest_endpoint[1]['latency_ms']['avg']:.3f} ms)")
    print(f"Slowest Endpoint:   {slowest_endpoint[0]} (avg: {slowest_endpoint[1]['latency_ms']['avg']:.3f} ms)")
    
    # Error analysis
    if overall['errors'] > 0:
        print_section("ERROR ANALYSIS")
        print(f"Total Errors:       {overall['errors']:,}")
        print(f"Error Rate:         {overall['error_rate_percent']:.2f}%")
        
        print("\nErrors by Algorithm:")
        for algo, stats in sorted_algos:
            if stats['errors'] > 0:
                print(f"  {algo:<25} {stats['errors']:>8,} ({stats['error_rate_percent']:>6.2f}%)")
        
        print("\nErrors by Endpoint:")
        for endpoint, stats in sorted_endpoints:
            if stats['errors'] > 0:
                print(f"  {endpoint:<30} {stats['errors']:>8,} ({stats['error_rate_percent']:>6.2f}%)")
    
    # Recommendations
    print_section("RECOMMENDATIONS")
    
    if overall['error_rate_percent'] > 1.0:
        print("⚠ High error rate detected (>1%). Consider:")
        print("  - Checking service logs for errors")
        print("  - Verifying Redis connectivity")
        print("  - Reducing load (concurrency or RPS)")
    
    if overall['latency_ms']['p99'] > 100:
        print("⚠ High P99 latency (>100ms). Consider:")
        print("  - Optimizing algorithm implementations")
        print("  - Checking Redis performance")
        print("  - Reviewing system resources")
    
    if overall['success_rate_percent'] >= 99.5 and overall['latency_ms']['p99'] < 50:
        print("✓ Excellent performance!")
        print("  - Success rate: {:.2f}%".format(overall['success_rate_percent']))
        print("  - P99 latency: {:.3f} ms".format(overall['latency_ms']['p99']))
    
    print()


def analyze_timeline(timeline_path: Path):
    """Analyze timeline data."""
    data = load_json(timeline_path)
    
    print_section("TIMELINE ANALYSIS")
    
    # Calculate statistics over time
    rps_values = [v['rps'] for v in data.values()]
    p99_values = [v['p99_ms'] for v in data.values()]
    
    print(f"Time Buckets:      {len(data)}")
    print(f"\nRPS Statistics:")
    print(f"  Min:             {min(rps_values):.2f}")
    print(f"  Max:             {max(rps_values):.2f}")
    print(f"  Avg:             {sum(rps_values)/len(rps_values):.2f}")
    
    print(f"\nP99 Latency Over Time:")
    print(f"  Min:             {min(p99_values):.3f} ms")
    print(f"  Max:             {max(p99_values):.3f} ms")
    print(f"  Avg:             {sum(p99_values)/len(p99_values):.3f} ms")
    
    # Detect anomalies
    avg_p99 = sum(p99_values) / len(p99_values)
    spikes = [(k, v['p99_ms']) for k, v in data.items() if v['p99_ms'] > avg_p99 * 2]
    
    if spikes:
        print(f"\n⚠ Detected {len(spikes)} latency spikes (>2x average):")
        for timestamp, p99 in spikes[:5]:  # Show first 5
            print(f"  Timestamp {timestamp}: {p99:.3f} ms")
    
    print()


def main():
    """Main function."""
    parser = argparse.ArgumentParser(description="Analyze load test results")
    parser.add_argument("results_dir", help="Directory containing test results")
    parser.add_argument("--timeline", action="store_true", help="Include timeline analysis")
    args = parser.parse_args()
    
    results_dir = Path(args.results_dir)
    
    if not results_dir.exists():
        print(f"Error: Directory not found: {results_dir}")
        sys.exit(1)
    
    summary_path = results_dir / "summary.json"
    if not summary_path.exists():
        print(f"Error: summary.json not found in {results_dir}")
        sys.exit(1)
    
    # Analyze summary
    analyze_summary(summary_path)
    
    # Analyze timeline if requested
    if args.timeline:
        timeline_path = results_dir / "timeline.json"
        if timeline_path.exists():
            analyze_timeline(timeline_path)
        else:
            print("Warning: timeline.json not found")
    
    print(f"\n{'='*80}")
    print(f"Analysis complete!")
    print(f"{'='*80}\n")


if __name__ == "__main__":
    main()
