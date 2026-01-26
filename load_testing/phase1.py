import asyncio
import random
import time
import httpx

BASE_URL = "http://localhost:8080"
ENDPOINT = "/api/ratelimit/check"

TARGET_RPS = 50
CONCURRENCY = 50
DURATION_S = 15

class TokenBucket:
    def __init__(self, rate, capacity):
        self.rate = rate
        self.capacity = capacity
        self.tokens = capacity
        self.last = time.perf_counter()

    def take(self):
        now = time.perf_counter()
        elapsed = now - self.last
        self.last = now
        self.tokens = min(self.capacity, self.tokens + elapsed * self.rate)
        if self.tokens >= 1:
            self.tokens -= 1
            return True
        return False

async def worker(client, bucket, stats):
    while time.perf_counter() < stats["end"]:
        if not bucket.take():
            await asyncio.sleep(0)  # yield
            continue

        payload = {
            "key": f"user:{random.randint(1, 10)}",
            "tokens": random.choice([1, 1, 1, 2])
        }

        start = time.perf_counter()
        try:
            resp = await client.post(f"{BASE_URL}{ENDPOINT}", json=payload, timeout=2.0)
            latency_ms = (time.perf_counter() - start) * 1000
            if resp.status_code == 200:
                stats["ok"] += 1
            elif resp.status_code == 429:
                stats["denied"] += 1
            else:
                stats["errors"] += 1
            stats["lat"] += latency_ms
        except Exception:
            stats["errors"] += 1

async def main():
    stats = {"ok": 0, "denied": 0, "errors": 0, "lat": 0.0, "end": time.perf_counter() + DURATION_S}
    bucket = TokenBucket(rate=TARGET_RPS, capacity=TARGET_RPS)

    limits = httpx.Limits(max_connections=CONCURRENCY, max_keepalive_connections=CONCURRENCY)
    async with httpx.AsyncClient(limits=limits) as client:
        tasks = [asyncio.create_task(worker(client, bucket, stats)) for _ in range(CONCURRENCY)]
        await asyncio.gather(*tasks)

    total = stats["ok"] + stats["denied"] + stats["errors"]
    avg_lat = stats["lat"] / max(1, stats["ok"] + stats["denied"])
    print(f"Total: {total}, OK: {stats['ok']}, 429: {stats['denied']}, Errors: {stats['errors']}")
    print(f"Avg latency ms: {avg_lat:.2f}")

if __name__ == "__main__":
    asyncio.run(main())