# Kolyo Rate Limiter

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-Distributed-red.svg)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> **Production-ready, distributed rate limiting service achieving 40,000+ RPS throughput with proven reliability**

A high-performance, battle-tested rate limiting system supporting **5 industry-standard algorithms** with comprehensive monitoring, dynamic configuration, and proven scalability under load.

## Key Metrics

- **Throughput**: 28,571 - 40,323 requests/second per algorithm (benchmark mode)
- **Load Test Performance**: 117.99 req/s sustained, P95: 38.18ms, P99: 51.45ms
- **Reliability**: 100% success rate under sustained load
- **Algorithms**: 5 production-ready implementations
- **Battle-Tested**: Zero errors in 14,159+ request load tests

---

## Features

### Multiple Rate Limiting Algorithms

- **Token Bucket** - Smooth traffic with burst tolerance (38,168 RPS)
- **Sliding Window** - Precise rate limiting with no boundary issues (38,314 RPS)
- **Sliding Window Counter** - Memory-efficient precision (29,762 RPS)
- **Fixed Window** - Simple, high-performance counters (28,571 RPS)
- **Leaky Bucket** - Consistent output rate, smooth traffic (40,323 RPS) **Highest Throughput**

### Enterprise-Grade Features

- **Distributed State** - Redis-backed for multi-instance deployments
- **Dynamic Configuration** - Hot-reload configs without restart
- **Pattern-Based Rules** - Wildcard support for flexible key matching
- **Comprehensive Monitoring** - Prometheus metrics & Spring Actuator
- **Sub-Second Response** - Average latency ~16ms under load
- **Burst Handling** - Graceful degradation under 3x traffic spikes
- **Production Ready** - Docker, Kubernetes, comprehensive tests

---

## Performance Benchmarks

### Algorithm Comparison (Benchmark Mode)

| Algorithm | Throughput (RPS) | P95 Latency | Best For |
|-----------|------------------|-------------|----------|
| **Leaky Bucket** | **40,323** | 2.06ms | Smooth traffic shaping |
| **Sliding Window** | 38,314 | 1.96ms | Precise rate limiting |
| **Token Bucket** | 38,168 | 2.06ms | Burst tolerance required |
| **Sliding Window Counter** | 29,762 | 5.39ms | Balance of accuracy & performance |
| **Fixed Window** | 28,571 | 2.38ms | Simple, predictable limits |

### Load Test Results (120s, 120 RPS target, 20 concurrent clients)

```
Total Requests: 14,159
Success Rate: 100.00%
Throughput: 117.99 req/s
Avg Latency: 15.87 ms
P95 Latency: 38.18 ms
P99 Latency: 51.45 ms
Error Rate: 0.00%
```

### Burst Test (3x Normal Load, 30s)

```
Concurrent Clients: 60
Target RPS: 360
Total Requests: 10,634
P95 Latency: 56.80 ms (+48.8% vs normal)
P99 Latency: 70.47 ms (+37.0% vs normal)
Error Rate: 0.00% 
```

**System maintains 100% availability even under 3x peak load**

---

## Architecture

```text
           +--------+        +----------------------+        +--------+
           | Client | -----> |  Rate Limiter        | -----> | Redis  |
           |        |        |  Service (stateless) |        | (state)|
           +--------+        +----------------------+        +--------+

                                  |
                                  v
                        +--------------------------+
                        | Algorithms               |
                        | - Token Bucket           |
                        | - Sliding Window         |
                        | - Fixed Window           |
                        | - Leaky Bucket           |
                        | - SWC                    |
                        +--------------------------+
```

### Request Flow

1. **Client Request**→ Rate limit check with key & algorithm
2. **Configuration Lookup**→ Cached config or fetch from Redis
3. **Algorithm Execution**→ Atomic Lua script on Redis
4. **Response**→ `200 OK` (allowed) or `429 Too Many Requests` (denied)
5. **Headers**→ `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After`

### Key Components

- **RateLimitService**- Orchestrates rate limiting logic
- **ConfigService**- Manages dynamic configurations with caching
- **Algorithm Implementations**- 5 algorithms with Lua scripts for atomicity
- **Redis Integration**- Distributed state with connection pooling
- **REST Controllers**- API endpoints for checks, config, admin, monitoring

---

## Quick Start

### Prerequisites

- **Docker** & **Docker Compose** (recommended)
- **OR** Java 21+ & Maven 3.9+ & Redis 6.0+ (for local development)

### Option 1: Docker Compose (Recommended) 🐳

The fastest way to get started:

```bash
# Clone the repository
git clone https://github.com/yourusername/rate-limiter.git
cd rate-limiter

# Start everything (Redis + Rate Limiter)
docker-compose up -d

# Check health
curl http://localhost:8080/actuator/health
```

**Services:**
- Rate Limiter: `http://localhost:8080`
- Redis: `localhost:6379`

**View logs:**
```bash
docker-compose logs -f rate-limiter
```

**Stop services:**
```bash
docker-compose down
```

### Option 2: Local Development

For development without Docker:

```bash
# 1. Clone & Install
git clone https://github.com/yourusername/rate-limiter.git
cd rate-limiter
mvn clean install

# 2. Start Redis
brew install redis # macOS
redis-server

# 3. Run the Application
mvn spring-boot:run
```

**Application starts on** `http://localhost:8080`

---

### Make Your First Request

```bash
# Check rate limit
curl -X POST http://localhost:8080/api/ratelimit/check \
-H "Content-Type: application/json" \
-d '{
"key": "user:123",
"algorithm": "TOKEN_BUCKET",
"tokens": 1
}'
```

**Expected Response:**

```json
{
"allowed": true,
"remainingTokens": 9,
"resetTime": "2026-02-02T07:13:19.897Z",
"retryAfterSeconds": 0,
"algorithm": "TOKEN_BUCKET",
"metadata": {
  "key": "user:123",
  "latencyMicros": 19351
}
}
```

---

## API Reference

### Rate Limiting Endpoints

#### Check Rate Limit

```http
POST /api/ratelimit/check
Content-Type: application/json

{
"key": "user:123",
"algorithm": "SLIDING_WINDOW",
"tokens": 1
}
```

**Response (200 OK - Allowed):**

```json
{
"allowed": true,
"remainingTokens": 45,
"resetTime": "2026-02-01T23:16:00Z",
"retryAfterSeconds": 0,
"algorithm": "SLIDING_WINDOW"
}
```

**Response (429 Too Many Requests - Denied):**

```json
{
"allowed": false,
"remainingTokens": 0,
"resetTime": "2026-02-01T23:16:00Z",
"retryAfterSeconds": 3599,
"algorithm": "SLIDING_WINDOW"
}
```

**Response Headers:**
- `X-RateLimit-Remaining`: Tokens remaining
- `X-RateLimit-Reset`: Unix timestamp when limit resets
- `Retry-After`: Seconds to wait (when denied)

---

### Configuration Endpoints

#### Get Configuration

```http
GET /api/ratelimit/config/{key}
```

#### Save Key-Specific Configuration

```http
POST /api/ratelimit/config/keys/{key}
Content-Type: application/json

{
"algorithm": "LEAKY_BUCKET",
"capacity": 100,
"refillRate": 10.0,
"refillPeriodSeconds": 1
}
```

#### Save Pattern Configuration (Wildcards)

```http
POST /api/ratelimit/config/patterns/user:*
Content-Type: application/json

{
"algorithm": "TOKEN_BUCKET",
"capacity": 50,
"refillRate": 5.0,
"refillPeriodSeconds": 1
}
```

#### Delete Configuration

```http
DELETE /api/ratelimit/config/keys/{key}
DELETE /api/ratelimit/config/patterns/{pattern}
```

#### List All Patterns

```http
GET /api/ratelimit/config/patterns
```

#### Reload Configurations (Hot-Reload)

```http
POST /api/ratelimit/config/reload
```

---

### Admin Endpoints

#### List All Active Keys

```http
GET /api/admin/keys?limit=100
```

**Response:**

```json
[
{
"key": "ratelimit:bucket:user:123",
"type": "TOKEN_BUCKET",
"ttl": 3600,
"state": null
}
]
```

#### Get System Statistics

```http
GET /api/admin/stats
```

**Response:**

```json
{
"totalKeys": 1247,
"bucketKeys": 234,
"slidingKeys": 189,
"fixedKeys": 301,
"leakyKeys": 267,
"swcKeys": 156,
"configKeys": 100
}
```

#### Reset Key

```http
DELETE /api/admin/keys?key=user:123
```

#### Reset Keys by Pattern

```http
DELETE /api/admin/keys/{pattern}
```

#### Clear Configuration Cache

```http
POST /api/admin/cache/clear
```

---

### Monitoring Endpoints

#### Health Check

```http
GET /actuator/health
```

#### Prometheus Metrics

```http
GET /actuator/prometheus
```

**Key Metrics:**
- `ratelimit_check_total` - Total rate limit checks
- `ratelimit_denied_total` - Total denied requests
- `ratelimit_latency_seconds` - Latency histogram
- `redis_operations_total` - Redis operation count

---

## Configuration

### Application Configuration (`application.yml`)

```yaml
spring:
application:
name: rate-limiter
data:
redis:
host: localhost
port: 6379
timeout: 60000

ratelimiter:
default:
algorithm: TOKEN_BUCKET
capacity: 10
refill-rate: 10
refill-period-seconds: 60
redis:
key-prefix: "ratelimit"
config-prefix: "config"
ttl-seconds: 3600
cache:
config-ttl-seconds: 60
max-size: 10000
enable-stats: true
performance:
metrics-enabled: true
detailed-logging: false

server:
port: 8080
compression:
enabled: true
http2:
enabled: true
```

### Environment-Specific Configs

- `application-dev.yml` - Development settings
- `application-prod.yml` - Production settings

**Run with profile:**

```bash
java -jar target/rate-limiter.jar --spring.profiles.active=prod
```

---

## Algorithm Details

### Token Bucket

**Best for**: APIs with burst tolerance, user request limits

**How it works**: Tokens refill at a constant rate. Each request consumes tokens. Allows bursts up to bucket capacity.

**Configuration:**
```json
{
"algorithm": "TOKEN_BUCKET",
"capacity": 100,
"refillRate": 10.0,
"refillPeriodSeconds": 1
}
```
*Allows 100 burst, refills 10 tokens/second*

---

### Sliding Window

**Best for**: Precise rate limiting, no boundary issues

**How it works**: Tracks timestamps of all requests in a rolling window. Most accurate but higher memory usage.

**Configuration:**
```json
{
"algorithm": "SLIDING_WINDOW",
"capacity": 100,
"refillRate": 10.0,
"refillPeriodSeconds": 1
}
```
*Allows 100 requests/second, precise tracking*

---

### Sliding Window Counter

**Best for**: Balance of accuracy and performance

**How it works**: Combines fixed windows with sliding calculation. Memory-efficient approximation of sliding window.

**Configuration:**
```json
{
"algorithm": "SLIDING_WINDOW_COUNTER",
"capacity": 100,
"refillRate": 10.0,
"refillPeriodSeconds": 1
}
```
*~99% accurate, minimal memory*

---

### Fixed Window

**Best for**: Simple, predictable limits, API tiers

**How it works**: Resets counter at fixed time intervals. Simple and fast but boundary double-spend possible.

**Configuration:**
```json
{
"algorithm": "FIXED_WINDOW",
"capacity": 100,
"refillRate": 10.0,
"refillPeriodSeconds": 1
}
```
*Counter resets every second*

---

### Leaky Bucket

**Best for**: Traffic shaping, smooth output rate, message queues

**How it works**: Requests "leak" out at a constant rate. Enforces smooth, consistent traffic flow.

**Configuration:**
```json
{
"algorithm": "LEAKY_BUCKET",
"capacity": 100,
"refillRate": 10.0,
"refillPeriodSeconds": 1
}
```
*Processes 10 requests/second consistently*

---

## Testing & Validation

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify
```

### Load Testing

Comprehensive end-to-end load testing framework included:

```bash
cd load_testing
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Run comprehensive load test
python comprehensive_load_test.py \
--concurrency 20 \
--target-rps 120 \
--duration 180
```

**Test Suite Includes:**
- Edge case tests (bucket exhaustion, burst, token tracking)
- Main load test (configurable duration, concurrency, RPS)
- Burst test (3x load spike)
- Benchmark tests (maximum throughput per algorithm)
- Performance regression tests
- Pre/post-flight health checks

**Test Results:**
```
Edge Cases: 5/5 PASSED 
Main Load Test: 14,159 requests, 0 errors 
Burst Test: 10,634 requests, 0 errors 
Benchmark Tests: 28k-40k RPS 
Regression Tests: All within thresholds 
```

---

## Deployment

### Docker Deployment

The project includes a production-ready, multi-stage Dockerfile with:
- ✅ **3-stage build** with dependency caching (6x faster rebuilds)
- ✅ **Security hardening** - runs as non-root user
- ✅ **Health checks** - automatic monitoring
- ✅ **JVM optimization** - G1GC, container-aware memory
- ✅ **Graceful shutdown** - proper signal handling

#### Quick Start with Docker Compose (Recommended)

```bash
# Start both Redis and Rate Limiter
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f

# Stop services
docker-compose down
```

**Services:**
- Rate Limiter: `http://localhost:8080` (with health checks)
- Redis: `localhost:6379` (with persistence)

#### Manual Docker Build & Run

```bash
# Build the image
docker build -t rate-limiter:latest .

# Run Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# Run Rate Limiter
docker run -d \
  --name rate-limiter \
  -p 8080:8080 \
  -e SPRING_DATA_REDIS_HOST=redis \
  --link redis:redis \
  rate-limiter:latest

# Check health
curl http://localhost:8080/actuator/health
```

#### Docker Image Features

**Build Performance:**
- First build: ~2.5 minutes
- Rebuild (code changes only): ~45 seconds
- Rebuild (no changes): ~30 seconds

**Image Details:**
- Base: `eclipse-temurin:21-jre-alpine`
- Size: ~350MB (optimized)
- User: `ratelimiter:1000` (non-root)
- Health check: Every 30s via `/actuator/health`

**Environment Variables:**
```bash
JAVA_OPTS="-Xmx512m -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
SPRING_DATA_REDIS_HOST="redis"
SPRING_DATA_REDIS_PORT="6379"
SERVER_PORT="8080"
```

---

### Kubernetes Deployment

Create deployment manifest (`k8s-deployment.yml`):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
name: rate-limiter
spec:
replicas: 3
selector:
matchLabels:
app: rate-limiter
template:
metadata:
labels:
app: rate-limiter
spec:
containers:
- name: rate-limiter
image: rate-limiter:latest
ports:
- containerPort: 8080
env:
- name: SPRING_DATA_REDIS_HOST
value: "redis-service"
- name: JAVA_OPTS
value: "-Xmx1g -Xms1g -XX:+UseG1GC"
---
apiVersion: v1
kind: Service
metadata:
name: rate-limiter-service
spec:
type: LoadBalancer
ports:
- port: 80
targetPort: 8080
selector:
app: rate-limiter
```

Deploy:

```bash
kubectl apply -f k8s-deployment.yml
```

---

### Production Considerations

#### Scaling Redis

For high-availability production deployments:

- **Redis Cluster** - Horizontal scaling, automatic sharding
- **Redis Sentinel** - Automatic failover, high availability
- **Managed Redis** - AWS ElastiCache, Azure Cache for Redis, Google Memorystore

#### Performance Tuning

**Connection Pooling** (already configured):
```yaml
spring:
data:
redis:
lettuce:
pool:
max-active: 20
max-idle: 10
min-idle: 5
```

**JVM Tuning:**
```bash
java -Xmx2g -Xms2g \
-XX:+UseG1GC \
-XX:MaxGCPauseMillis=200 \
-jar rate-limiter.jar
```

#### Monitoring

**Prometheus + Grafana:**

1. Scrape `/actuator/prometheus` endpoint
2. Import Grafana dashboard for Spring Boot
3. Monitor key metrics:
- Request rate
- Denial rate
- Latency percentiles
- Redis operations
- JVM metrics

---

## Monitoring & Observability

### Health Checks

```bash
curl http://localhost:8080/actuator/health
```

**Response:**
```json
{
"status": "UP",
"components": {
"redis": {
"status": "UP",
"details": {
"version": "7.0.5"
}
},
"rateLimiter": {
"status": "UP",
"details": {
"algorithms": ["TOKEN_BUCKET", "SLIDING_WINDOW", ...]
}
}
}
}
```

### Metrics

**Key Prometheus Metrics:**

- `ratelimit_check_total{algorithm, result}` - Counter
- `ratelimit_latency_seconds{algorithm}` - Histogram
- `redis_commands_total{command}` - Counter
- `http_server_requests_seconds{uri, status}` - Histogram

**Custom Metrics:**
```java
@Timed(value = "ratelimit.check", percentiles = {0.5, 0.95, 0.99})
public RateLimitResponse checkLimit(RateLimitRequest request) {
// ...
}
```

---

## Contributing

Contributions are welcome! Please follow these guidelines:

### Development Setup

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/amazing-feature`
3. Install dependencies: `mvn clean install`
4. Make your changes
5. Run tests: `mvn verify`
6. Commit changes: `git commit -m 'Add amazing feature'`
7. Push to branch: `git push origin feature/amazing-feature`
8. Open a Pull Request

### Code Standards

- Follow [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- Write unit tests for new features (target: 80%+ coverage)
- Update documentation for API changes
- Use meaningful commit messages

### Testing Requirements

- All tests must pass: `mvn verify`
- Add integration tests for new algorithms
- Run load tests for performance-impacting changes

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## Roadmap

- [ ] **gRPC Support**- High-performance RPC interface
- [ ] **Distributed Tracing**- OpenTelemetry integration
- [ ] **Admin Dashboard**- Web UI for configuration & monitoring
- [ ] **Multi-Tenancy**- Isolated rate limits per tenant
- [ ] **Advanced Quotas**- Hierarchical limits (user/org/global)
- [ ] **Rate Limit Sharing**- Distributed quotas across instances
- [ ] **Machine Learning**- Adaptive rate limits based on traffic patterns

---

## Support

- **Documentation**: 
  - [README.md](README.md) - Main documentation
  - [DOCKER_GUIDE.md](DOCKER_GUIDE.md) - Comprehensive Docker deployment guide
  - [PROJECT_GUIDE.md](PROJECT_GUIDE.md) - Deep dive into architecture & implementation
  - Inline code comments
- **Issues**: [GitHub Issues](https://github.com/yourusername/rate-limiter/issues)
- **Discussions**: [GitHub Discussions](https://github.com/yourusername/rate-limiter/discussions)

---

## Acknowledgments

- **Redis**- Blazing-fast distributed state management
- **Spring Boot**- Excellent framework for rapid development
- **Lua Scripts**- Atomic operation execution on Redis
- **Load Testing Community**- Best practices for robust testing

---

**Built with for high-performance, production-grade rate limiting**
