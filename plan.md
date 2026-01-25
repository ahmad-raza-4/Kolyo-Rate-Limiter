# Distributed Rate Limiter MVP - Complete Design Document

## 🎯 Project Overview

**Goal**: Build a production-grade distributed rate limiter to learn system design, distributed systems, caching, consistency models, and low-latency architectures.

**Tech Stack**:
- **Language**: Java 21+
- **Framework**: Spring Boot 3.x
- **Cache/State**: Redis
- **Build Tool**: Maven/Gradle
- **Testing**: JUnit 5, Testcontainers
- **Monitoring**: Micrometer, Prometheus
- **Containerization**: Docker

---

## 📚 Learning Objectives

Through this project, you'll learn:

1. **System Design Concepts**
   - Rate limiting algorithms (Token Bucket, Sliding Window, Fixed Window, Leaky Bucket)
   - Distributed state management
   - CAP theorem tradeoffs
   - Consistency models (eventual vs strong consistency)

2. **Low Latency Systems**
   - Sub-millisecond response times
   - Connection pooling
   - Pipelining and batching
   - Async/non-blocking I/O

3. **Distributed Systems**
   - Distributed locks
   - Clock synchronization issues
   - Network partitions handling
   - Horizontal scaling

4. **Caching & Redis**
   - Redis data structures (String, Hash, Sorted Sets)
   - Lua scripting for atomic operations
   - TTL and expiration strategies
   - Redis pipelines

5. **Observability**
   - Metrics collection (latency, throughput, error rates)
   - Health checks
   - Performance benchmarking

---

## 🏗️ High-Level Design (HLD)

### System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Client Applications                      │
│  (API Gateway, Microservices, Web Apps, Mobile Apps)        │
└────────────────────┬────────────────────────────────────────┘
                     │ HTTP REST API
                     ▼
┌─────────────────────────────────────────────────────────────┐
│              Rate Limiter Service (Spring Boot)              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   REST API   │  │  Algorithm   │  │   Config     │      │
│  │  Controller  │─▶│   Engine     │  │  Management  │      │
│  └──────────────┘  └──────┬───────┘  └──────────────┘      │
│                            │                                  │
│  ┌──────────────┐  ┌──────▼───────┐  ┌──────────────┐      │
│  │   Metrics    │  │  Redis       │  │   Health     │      │
│  │  Collector   │  │  Client      │  │   Checks     │      │
│  └──────────────┘  └──────┬───────┘  └──────────────┘      │
└────────────────────────────┼────────────────────────────────┘
                             │ Redis Protocol
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    Redis Cluster/Sentinel                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Primary    │─▶│   Replica    │  │   Replica    │      │
│  │   (Master)   │  │              │  │              │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  State: Rate limit buckets, configurations, metrics          │
└─────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│              Monitoring & Observability Stack                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │  Prometheus  │  │   Grafana    │  │   Logging    │      │
│  │  (Metrics)   │  │  (Dashboards)│  │   (ELK/Loki) │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

1. **API Layer**
   - RESTful endpoints for rate limit checks
   - Request validation and authentication
   - Response formatting (allowed/denied, retry-after headers)

2. **Algorithm Engine**
   - Pluggable rate limiting algorithms
   - Token Bucket (default), Sliding Window, Fixed Window, Leaky Bucket
   - Configuration-driven algorithm selection

3. **Redis Integration**
   - Distributed state storage
   - Atomic operations via Lua scripts
   - Connection pooling and circuit breaking

4. **Configuration Management**
   - Dynamic per-key limits
   - Pattern-based rules (e.g., `api:*`, `user:premium:*`)
   - Hot-reload without restarts

5. **Observability**
   - Prometheus metrics (latency, throughput, denials)
   - Health checks (Redis connectivity, system resources)
   - Performance baselines and regression detection

---

## 🔍 Low-Level Design (LLD)

### 1. Rate Limiting Algorithms

#### Token Bucket Algorithm (Primary)

**Concept**: Bucket holds tokens; requests consume tokens; bucket refills at fixed rate.

**Data Model (Redis)**:
```
Key: "ratelimit:bucket:{userId}"
Value: Hash
  - tokens: current token count (float)
  - capacity: max tokens (int)
  - refill_rate: tokens per second (float)
  - last_refill: timestamp (long)
```

**Algorithm Flow**:
```
1. Fetch bucket from Redis
2. Calculate tokens to add = (current_time - last_refill) * refill_rate
3. New tokens = min(current_tokens + tokens_to_add, capacity)
4. If new_tokens >= requested_tokens:
     - Deduct tokens
     - Update bucket
     - Return ALLOWED
   Else:
     - Return DENIED with retry-after = time_to_refill
```

**Lua Script for Atomicity**:
```lua
-- check_and_consume.lua
local key = KEYS[1]
local requested = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refill_rate = tonumber(ARGV[3])
local now = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(bucket[1]) or capacity
local last_refill = tonumber(bucket[2]) or now

-- Calculate refill
local elapsed = math.max(0, now - last_refill)
local refilled = math.min(capacity, tokens + (elapsed * refill_rate))

if refilled >= requested then
    redis.call('HMSET', key, 'tokens', refilled - requested, 'last_refill', now)
    redis.call('EXPIRE', key, 3600)
    return {1, refilled - requested, 0}  -- allowed, remaining, retry_after
else
    local retry_after = (requested - refilled) / refill_rate
    return {0, refilled, retry_after}  -- denied, remaining, retry_after
end
```

**Tradeoffs**:
- ✅ Handles bursts gracefully
- ✅ Simple to implement and understand
- ✅ Good for most API use cases
- ❌ Allows bursts up to full capacity

---

#### Sliding Window Algorithm

**Concept**: Track requests in a rolling time window; reject if count exceeds limit.

**Data Model (Redis)**:
```
Key: "ratelimit:sliding:{userId}"
Value: Sorted Set (ZSET)
  Score: timestamp (milliseconds)
  Member: request_id (UUID)
```

**Algorithm Flow**:
```
1. Remove old entries: ZREMRANGEBYSCORE key 0 (now - window_size)
2. Count current entries: ZCARD key
3. If count < limit:
     - Add new entry: ZADD key now request_id
     - Return ALLOWED
   Else:
     - Return DENIED
```

**Lua Script**:
```lua
-- sliding_window.lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local request_id = ARGV[4]

redis.call('ZREMRANGEBYSCORE', key, 0, now - window_ms)
local count = redis.call('ZCARD', key)

if count < limit then
    redis.call('ZADD', key, now, request_id)
    redis.call('EXPIRE', key, math.ceil(window_ms / 1000))
    return {1, limit - count - 1}
else
    return {0, 0}
end
```

**Tradeoffs**:
- ✅ Precise rate enforcement
- ✅ No burst allowance
- ✅ Perfect for strict limits
- ❌ Higher memory usage (stores each request)
- ❌ More Redis operations

---

#### Fixed Window Algorithm

**Concept**: Counter resets at fixed intervals; simplest and most memory-efficient.

**Data Model (Redis)**:
```
Key: "ratelimit:fixed:{userId}:{window_start}"
Value: Integer (counter)
TTL: window size
```

**Algorithm Flow**:
```
1. Calculate window_key = current_time - (current_time % window_size)
2. Increment counter: INCR key
3. If first request in window: EXPIRE key window_size
4. If counter <= limit: ALLOWED
   Else: DENIED
```

**Lua Script**:
```lua
-- fixed_window.lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_size = tonumber(ARGV[2])

local count = redis.call('INCR', key)
if count == 1 then
    redis.call('EXPIRE', key, window_size)
end

if count <= limit then
    return {1, limit - count}
else
    return {0, 0}
end
```

**Tradeoffs**:
- ✅ Extremely low memory usage
- ✅ Very fast (single INCR operation)
- ✅ Simple to implement
- ❌ Burst at window boundaries (2x limit in 2 seconds)
- ❌ Less accurate than sliding window

---

#### Leaky Bucket Algorithm

**Concept**: Requests "leak" out at constant rate; queue up to capacity.

**Data Model (Redis)**:
```
Key: "ratelimit:leaky:{userId}"
Value: Hash
  - queue_size: current queue length
  - last_leak: timestamp
```

**Algorithm Flow**:
```
1. Calculate leaked = (now - last_leak) * leak_rate
2. New queue = max(0, current_queue - leaked)
3. If new_queue < capacity:
     - Add request to queue
     - Return ALLOWED
   Else:
     - Return DENIED
```

**Tradeoffs**:
- ✅ Constant output rate (good for downstream protection)
- ✅ Smooth traffic shaping
- ❌ No burst handling
- ❌ Requests wait in queue (may increase latency)

---

### 2. Data Models

#### Configuration Schema

```java
@Document("rate_limit_config")
public class RateLimitConfig {
    @Id
    private String id;  // key or pattern (e.g., "user:123", "api:*")
    
    private String keyPattern;
    private RateLimitAlgorithm algorithm;  // TOKEN_BUCKET, SLIDING_WINDOW, etc.
    
    // Token Bucket params
    private Integer capacity;
    private Double refillRate;
    private Integer refillPeriodSeconds;
    
    // Sliding Window params
    private Integer windowSizeSeconds;
    private Integer maxRequests;
    
    // Metadata
    private Integer priority;  // for pattern matching (higher = more specific)
    private Instant createdAt;
    private Instant updatedAt;
}
```

#### Rate Limit Response

```java
public class RateLimitResponse {
    private boolean allowed;
    private long remainingTokens;
    private Instant resetTime;
    private Long retryAfterSeconds;  // null if allowed
    private String algorithm;
    private RateLimitMetadata metadata;
    
    public static class RateLimitMetadata {
        private String key;
        private String matchedPattern;
        private long latencyMicros;
    }
}
```

---

### 3. API Design

#### Core Endpoints

```
POST /api/ratelimit/check
Request:
{
  "key": "user:123",
  "tokens": 1,
  "metadata": {
    "ip": "192.168.1.1",
    "endpoint": "/api/v1/data"
  }
}

Response (200 OK):
{
  "allowed": true,
  "remainingTokens": 95,
  "resetTime": "2024-01-15T10:30:00Z",
  "retryAfterSeconds": null,
  "algorithm": "TOKEN_BUCKET",
  "metadata": {
    "key": "user:123",
    "matchedPattern": "user:*",
    "latencyMicros": 1250
  }
}

Response (429 Too Many Requests):
{
  "allowed": false,
  "remainingTokens": 0,
  "resetTime": "2024-01-15T10:35:00Z",
  "retryAfterSeconds": 300,
  "algorithm": "TOKEN_BUCKET"
}
```

```
GET /api/ratelimit/config/{key}
Response:
{
  "keyPattern": "user:*",
  "algorithm": "TOKEN_BUCKET",
  "capacity": 100,
  "refillRate": 10,
  "refillPeriodSeconds": 60,
  "priority": 10
}
```

```
POST /api/ratelimit/config
Request:
{
  "keyPattern": "premium:*",
  "algorithm": "TOKEN_BUCKET",
  "capacity": 1000,
  "refillRate": 100,
  "refillPeriodSeconds": 60
}
```

```
GET /actuator/health
Response:
{
  "status": "UP",
  "components": {
    "redis": {"status": "UP", "details": {...}},
    "rateLimiter": {
      "status": "UP",
      "activeBuckets": 1234,
      "throughputRps": 5420,
      "p95LatencyMs": 1.8
    }
  }
}
```

---

### 4. Redis Schema Design

#### Key Naming Convention
```
ratelimit:{algorithm}:{key}
  Examples:
  - ratelimit:bucket:user:123
  - ratelimit:sliding:api:endpoint:/data
  - ratelimit:fixed:ip:192.168.1.1:1705315200  (window timestamp)

config:{type}:{pattern}
  Examples:
  - config:key:user:123
  - config:pattern:api:*
  - config:default
```

#### Data Structures

**Token Bucket**:
```
HSET ratelimit:bucket:user:123
  tokens 95.5
  capacity 100
  refill_rate 10
  last_refill 1705315234567
EXPIRE ratelimit:bucket:user:123 3600
```

**Sliding Window**:
```
ZADD ratelimit:sliding:user:123 1705315234567 "req-uuid-1"
ZADD ratelimit:sliding:user:123 1705315235123 "req-uuid-2"
EXPIRE ratelimit:sliding:user:123 60
```

**Configuration**:
```
HSET config:pattern:user:*
  algorithm TOKEN_BUCKET
  capacity 100
  refill_rate 10
  refill_period_seconds 60
  priority 10
```

---

### 5. Performance Optimizations

#### Connection Pooling
```java
@Configuration
public class RedisConfig {
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        GenericObjectPoolConfig poolConfig = new GenericObjectPoolConfig();
        poolConfig.setMaxTotal(50);  // Max connections
        poolConfig.setMaxIdle(20);
        poolConfig.setMinIdle(5);
        poolConfig.setTestOnBorrow(true);
        
        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration
            .builder()
            .poolConfig(poolConfig)
            .commandTimeout(Duration.ofMillis(500))
            .build();
            
        return new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("localhost", 6379),
            clientConfig
        );
    }
}
```

#### Lua Script Caching
```java
public class LuaScriptCache {
    private final Map<String, RedisScript<List>> scripts = new ConcurrentHashMap<>();
    
    public RedisScript<List> getScript(String name) {
        return scripts.computeIfAbsent(name, k -> {
            String scriptContent = loadScriptFromFile(k);
            return RedisScript.of(scriptContent, List.class);
        });
    }
}
```

#### Async Processing
```java
@Service
public class RateLimitService {
    @Async("rateLimitExecutor")
    public CompletableFuture<RateLimitResponse> checkAsync(String key, int tokens) {
        return CompletableFuture.completedFuture(check(key, tokens));
    }
}

@Configuration
public class AsyncConfig {
    @Bean(name = "rateLimitExecutor")
    public Executor rateLimitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("rate-limit-");
        executor.initialize();
        return executor;
    }
}
```

---

### 6. Consistency & CAP Tradeoffs

**Consistency Model**: Eventual Consistency with Strong Consistency for Individual Keys

**Approach**:
- Use Redis Lua scripts for atomic operations within a single key
- Accept eventual consistency across different keys
- Rely on Redis replication for availability

**Tradeoffs**:
- ✅ High availability (Redis Sentinel/Cluster)
- ✅ Low latency (no distributed locks)
- ❌ Potential inconsistency during network partitions
- ❌ No global rate limiting across partitions

**Handling Network Partitions**:
```java
@Service
public class RateLimitService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final CircuitBreaker circuitBreaker;
    
    public RateLimitResponse check(String key, int tokens) {
        try {
            return circuitBreaker.executeSupplier(() -> 
                performRedisCheck(key, tokens)
            );
        } catch (Exception e) {
            // Fail-open: allow request but log incident
            log.error("Redis unavailable, failing open for key: {}", key);
            return RateLimitResponse.failOpen();
        }
    }
}
```

---

### 7. Metrics & Observability

**Key Metrics to Track**:
```java
@Component
public class RateLimitMetrics {
    private final MeterRegistry registry;
    
    // Counters
    private final Counter allowedCounter;
    private final Counter deniedCounter;
    
    // Timers
    private final Timer checkLatency;
    
    // Gauges
    private final AtomicInteger activeBuckets = new AtomicInteger(0);
    
    public void recordCheck(RateLimitResponse response, long latencyMicros) {
        if (response.isAllowed()) {
            allowedCounter.increment();
        } else {
            deniedCounter.increment();
        }
        
        checkLatency.record(latencyMicros, TimeUnit.MICROSECONDS);
    }
}
```

**Prometheus Metrics**:
```
# HELP rate_limit_checks_total Total rate limit checks
# TYPE rate_limit_checks_total counter
rate_limit_checks_total{result="allowed"} 125034
rate_limit_checks_total{result="denied"} 2341

# HELP rate_limit_check_duration_seconds Time to perform rate limit check
# TYPE rate_limit_check_duration_seconds histogram
rate_limit_check_duration_seconds_bucket{le="0.001"} 98234
rate_limit_check_duration_seconds_bucket{le="0.005"} 124521
rate_limit_check_duration_seconds_sum 187.234
rate_limit_check_duration_seconds_count 125034

# HELP rate_limit_active_buckets Current number of active buckets
# TYPE rate_limit_active_buckets gauge
rate_limit_active_buckets 5420
```

---

## 🚀 Development Phases

### Phase 1: Foundation (Week 1-2)
**Goal**: Basic rate limiter with Token Bucket algorithm

**Tasks**:
1. Set up Spring Boot project with dependencies
2. Configure Redis connection and pooling
3. Implement Token Bucket algorithm with Lua script
4. Create REST API endpoints (check, config)
5. Write unit tests (90%+ coverage target)
6. Add basic logging

**Deliverable**: Working rate limiter for single algorithm

**Learning Focus**:
- Spring Boot structure
- Redis basics (HSET, EXPIRE, Lua scripts)
- REST API design
- Unit testing with Mockito

---

### Phase 2: Multi-Algorithm Support (Week 3)
**Goal**: Add Sliding Window and Fixed Window algorithms

**Tasks**:
1. Refactor to Strategy Pattern for algorithms
2. Implement Sliding Window with ZSET
3. Implement Fixed Window with INCR
4. Add algorithm selection via configuration
5. Benchmark algorithms (throughput, latency, memory)
6. Write integration tests with Testcontainers

**Deliverable**: Three working algorithms with comparative benchmarks

**Learning Focus**:
- Strategy/Factory patterns
- Redis data structures (ZSET)
- Performance benchmarking
- Testcontainers for integration tests

---

### Phase 3: Configuration Management (Week 4)
**Goal**: Dynamic, pattern-based configuration

**Tasks**:
1. Implement configuration storage in Redis
2. Add pattern matching (e.g., `api:*`, `user:premium:*`)
3. Create config CRUD endpoints
4. Add hot-reload without restart
5. Implement priority-based pattern matching
6. Add configuration validation

**Deliverable**: Flexible configuration system with patterns

**Learning Focus**:
- Pattern matching algorithms
- Configuration hot-reload
- Validation with Bean Validation
- CRUD REST endpoints

---

### Phase 4: Observability & Monitoring (Week 5)
**Goal**: Production-grade monitoring

**Tasks**:
1. Integrate Micrometer for metrics
2. Add Prometheus endpoint (`/actuator/prometheus`)
3. Implement custom metrics (latency, throughput, denials)
4. Create health checks (Redis connectivity, system resources)
5. Add performance baseline tracking
6. Set up Grafana dashboards (optional but recommended)

**Deliverable**: Full observability stack

**Learning Focus**:
- Micrometer and Prometheus
- Custom metrics
- Health checks
- Performance monitoring

---

### Phase 5: Advanced Features (Week 6)
**Goal**: Production readiness

**Tasks**:
1. Implement Leaky Bucket algorithm
2. Add batch check endpoint (multiple keys)
3. Implement circuit breaker for Redis failures
4. Add request/response caching
5. Create load testing scripts (JMeter or Gatling)
6. Add API documentation (Swagger/OpenAPI)

**Deliverable**: Production-ready rate limiter

**Learning Focus**:
- Circuit breaker pattern (Resilience4j)
- Batch processing
- Load testing
- API documentation

---

### Phase 6: Deployment & Scaling (Week 7)
**Goal**: Deploy to cloud and scale

**Tasks**:
1. Create Docker image with multi-stage build
2. Write docker-compose.yml (app + Redis)
3. Create Kubernetes manifests (Deployment, Service, ConfigMap)
4. Deploy to cloud (AWS/GCP/Azure)
5. Set up Redis Cluster/Sentinel
6. Load test production deployment

**Deliverable**: Cloud-deployed, horizontally scalable system

**Learning Focus**:
- Docker multi-stage builds
- Kubernetes basics
- Cloud deployment
- Horizontal scaling

---

## 📊 Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Latency P95** | < 2ms | 95th percentile response time |
| **Latency P99** | < 5ms | 99th percentile response time |
| **Throughput** | > 50,000 RPS | Requests per second (single instance) |
| **Error Rate** | < 0.01% | Failed requests under normal load |
| **Memory Usage** | < 500MB | Baseline + 10K active buckets |
| **Redis Ops** | 2-3 per check | Number of Redis commands |
| **CPU Usage** | < 10% | At 10K RPS |

---

## 🔧 Technology Stack

### Core Dependencies
```xml
<dependencies>
    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    
    <!-- Redis Client -->
    <dependency>
        <groupId>io.lettuce</groupId>
        <artifactId>lettuce-core</artifactId>
    </dependency>
    
    <!-- Metrics -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    
    <!-- Resilience -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot3</artifactId>
    </dependency>
    
    <!-- Testing -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    
    <!-- Utilities -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
</dependencies>
```

---

## 🎓 Learning Resources

### Books
- "Designing Data-Intensive Applications" by Martin Kleppmann
- "Redis in Action" by Josiah Carlson
- "Spring Boot in Action" by Craig Walls

### Online Resources
- Redis Lua scripting: https://redis.io/docs/interact/programmability/eval-intro/
- Rate limiting algorithms: https://konghq.com/blog/how-to-design-a-scalable-rate-limiting-algorithm
- Spring Boot + Redis: https://spring.io/guides/gs/messaging-redis/
- Prometheus metrics: https://prometheus.io/docs/practices/naming/

### Practice
- Implement each algorithm in isolation first
- Benchmark with different workloads (uniform, bursty, gradual)
- Simulate Redis failures and network partitions
- Load test with increasing concurrency (10, 100, 1000 threads)

---

## 🎯 Success Criteria

### Technical
- ✅ All unit tests passing (90%+ coverage)
- ✅ Integration tests with real Redis
- ✅ Load tests showing 50K+ RPS
- ✅ P95 latency < 2ms
- ✅ Zero downtime during config changes
- ✅ Handles Redis failures gracefully

### Learning
- ✅ Can explain CAP theorem tradeoffs in your design
- ✅ Can justify algorithm choice for different use cases
- ✅ Understand Redis Lua scripting and atomicity
- ✅ Can optimize for low latency vs high throughput
- ✅ Know how to monitor and debug distributed systems

### Portfolio
- ✅ GitHub repo with comprehensive README
- ✅ Architecture diagrams
- ✅ Performance benchmarks documented
- ✅ Live demo deployed to cloud
- ✅ Blog post explaining design decisions

---

## 🚧 Common Pitfalls to Avoid

1. **Not Using Lua Scripts**: Without atomic Lua scripts, you'll have race conditions
2. **Ignoring Connection Pooling**: Each request creating new Redis connection kills performance
3. **Poor Key Design**: Inconsistent key naming makes debugging impossible
4. **No TTL on Keys**: Redis will run out of memory
5. **Synchronous Redis Calls**: Use async/pipeline for batch operations
6. **Hardcoded Configuration**: Make everything configurable via properties/env vars
7. **No Circuit Breaker**: One Redis failure shouldn't kill your app
8. **Insufficient Testing**: Test with Testcontainers, not just mocks

---

## 📝 Next Steps

1. **Start with Phase 1**: Get basic Token Bucket working
2. **Iterate Rapidly**: Don't over-engineer, ship working code first
3. **Measure Everything**: Add metrics from day one
4. **Document as You Go**: Update README with design decisions
5. **Seek Feedback**: Share early, get code reviews
6. **Blog About It**: Document your learning journey

---

**Good luck building your distributed rate limiter!** 🚀

This project will give you hands-on experience with concepts used at companies like Netflix, Cloudflare, and AWS. Take your time, understand each concept deeply, and you'll have an impressive portfolio piece.