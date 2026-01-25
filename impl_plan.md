# Distributed Rate Limiter - Strategic Implementation Plan

## 📅 Timeline Overview

**Total Duration**: 7-8 weeks (part-time, 10-15 hours/week)
**Weekly Commitment**: 1.5-2 hours/day on weekdays, 3-4 hours on weekends
**Project Structure**: 6 phases, 42 detailed steps

---

## 🎯 Phase 1: Foundation & Token Bucket (Week 1-2)

**Goal**: Working rate limiter with Token Bucket algorithm, REST API, and basic tests

### Day 1-2: Project Setup & Infrastructure

#### Step 1: Initialize Project (2 hours)
```bash
# Create project directory
mkdir distributed-rate-limiter
cd distributed-rate-limiter

# Initialize Spring Boot project using Spring Initializr
# Visit https://start.spring.io with these settings:
# - Project: Maven
# - Language: Java
# - Spring Boot: 3.2.x (latest stable)
# - Java: 21
# - Packaging: Jar
# - Dependencies: Web, Data Redis, Actuator, Lombok, Validation

# Or use command line:
curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=3.2.1 \
  -d baseDir=distributed-rate-limiter \
  -d groupId=com.ratelimiter \
  -d artifactId=rate-limiter \
  -d name=RateLimiter \
  -d packageName=com.ratelimiter \
  -d dependencies=web,data-redis,actuator,lombok,validation \
  -o rate-limiter.zip

unzip rate-limiter.zip
```

**Deliverable**: 
- ✅ Spring Boot project initialized
- ✅ Maven/Gradle builds successfully
- ✅ Application starts on port 8080

**Git Commit**: `git commit -m "Initial project setup with Spring Boot 3.2"`

---

#### Step 2: Configure Dependencies (1 hour)

Update `pom.xml` with additional dependencies:

```xml
<dependencies>
    <!-- Existing Spring Boot starters -->
    
    <!-- Redis Client (Lettuce) -->
    <dependency>
        <groupId>io.lettuce</groupId>
        <artifactId>lettuce-core</artifactId>
    </dependency>
    
    <!-- Prometheus Metrics -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
    
    <!-- Resilience4j for Circuit Breaker -->
    <dependency>
        <groupId>io.github.resilience4j</groupId>
        <artifactId>resilience4j-spring-boot3</artifactId>
        <version>2.1.0</version>
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
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>1.19.3</version>
        <scope>test</scope>
    </dependency>
    
    <!-- API Documentation -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>2.3.0</version>
    </dependency>
    
    <!-- JSON Processing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </exclude>
                </excludes>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Action**: Run `./mvnw clean install` to download dependencies

**Deliverable**:
- ✅ All dependencies resolved
- ✅ Project builds without errors

**Git Commit**: `git commit -m "Add core dependencies: Redis, Metrics, Testing"`

---

#### Step 3: Local Redis Setup (1 hour)

**Option A: Docker (Recommended)**
```bash
# Create docker-compose.yml in project root
cat > docker-compose.yml << 'EOF'
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    container_name: rate-limiter-redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  redis-data:
EOF

# Start Redis
docker-compose up -d

# Verify Redis is running
docker-compose ps
redis-cli ping  # Should return PONG
```

**Option B: Local Installation**
```bash
# macOS
brew install redis
brew services start redis

# Ubuntu/Debian
sudo apt-get install redis-server
sudo systemctl start redis

# Verify
redis-cli ping
```

**Deliverable**:
- ✅ Redis running on localhost:6379
- ✅ Can connect via `redis-cli`

**Git Commit**: `git commit -m "Add docker-compose for Redis"`

---

### Day 3-4: Core Package Structure & Configuration

#### Step 4: Create Package Structure (1 hour)

```bash
cd src/main/java/com/ratelimiter

# Create package structure
mkdir -p {config,controller,service,repository,model,dto,exception,util,algorithm}
mkdir -p ../resources/lua

# Create test structure
cd ../../test/java/com/ratelimiter
mkdir -p {controller,service,algorithm,integration}
```

**Final Structure**:
```
src/main/java/com/ratelimiter/
├── RateLimiterApplication.java
├── algorithm/              # Rate limiting algorithms
├── config/                 # Configuration classes
├── controller/             # REST controllers
├── dto/                    # Data Transfer Objects
├── exception/              # Custom exceptions
├── model/                  # Domain models
├── repository/             # Redis repositories
├── service/                # Business logic
└── util/                   # Utility classes

src/main/resources/
├── application.yml
├── application-dev.yml
├── application-prod.yml
└── lua/                    # Lua scripts for Redis
    └── token_bucket.lua

src/test/java/com/ratelimiter/
├── algorithm/              # Algorithm unit tests
├── controller/             # API tests
├── integration/            # Integration tests
└── service/                # Service tests
```

**Git Commit**: `git commit -m "Create package structure"`

---

#### Step 5: Configure Application Properties (1 hour)

Create `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: distributed-rate-limiter
  
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: 0
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 50
          max-idle: 20
          min-idle: 5
          max-wait: 1000ms
        shutdown-timeout: 100ms

server:
  port: 8080
  compression:
    enabled: true
  http2:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,info
      base-path: /actuator
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: ${spring.application.name}

# Custom Rate Limiter Configuration
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
  performance:
    metrics-enabled: true
    detailed-logging: false

logging:
  level:
    com.ratelimiter: DEBUG
    org.springframework.data.redis: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
```

Create `src/main/resources/application-dev.yml`:
```yaml
logging:
  level:
    com.ratelimiter: DEBUG

ratelimiter:
  performance:
    detailed-logging: true
```

Create `src/main/resources/application-prod.yml`:
```yaml
logging:
  level:
    com.ratelimiter: INFO

server:
  port: ${PORT:8080}

ratelimiter:
  performance:
    detailed-logging: false
```

**Deliverable**:
- ✅ Configuration files created
- ✅ Environment-specific profiles

**Git Commit**: `git commit -m "Add application configuration with profiles"`

---

#### Step 6: Redis Configuration Class (1.5 hours)

Create `src/main/java/com/ratelimiter/config/RedisConfig.java`:

```java
package com.ratelimiter.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:#{null}}")
    private String redisPassword;

    @Value("${spring.data.redis.timeout}")
    private Duration timeout;

    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.create();
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(ClientResources clientResources) {
        RedisStandaloneConfiguration redisConfig = new RedisStandaloneConfiguration();
        redisConfig.setHostName(redisHost);
        redisConfig.setPort(redisPort);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            redisConfig.setPassword(redisPassword);
        }

        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .clientResources(clientResources)
                .commandTimeout(timeout)
                .build();

        return new LettuceConnectionFactory(redisConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        // Use String serializer for keys
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use JSON serializer for values
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

**Test Redis Connection**:

Create `src/test/java/com/ratelimiter/config/RedisConfigTest.java`:

```java
package com.ratelimiter.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisConfigTest {

    @Container
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void shouldConnectToRedis() {
        // Given
        String key = "test:key";
        String value = "test-value";

        // When
        redisTemplate.opsForValue().set(key, value);
        String result = redisTemplate.opsForValue().get(key);

        // Then
        assertThat(result).isEqualTo(value);
        
        // Cleanup
        redisTemplate.delete(key);
    }
}
```

**Run Test**: `./mvnw test -Dtest=RedisConfigTest`

**Deliverable**:
- ✅ Redis configuration with connection pooling
- ✅ RedisTemplate beans configured
- ✅ Test verifies Redis connectivity

**Git Commit**: `git commit -m "Configure Redis with Lettuce connection pooling"`

---

### Day 5-6: Token Bucket Algorithm Implementation

#### Step 7: Create Domain Models (1 hour)

Create `src/main/java/com/ratelimiter/model/RateLimitAlgorithm.java`:

```java
package com.ratelimiter.model;

public enum RateLimitAlgorithm {
    TOKEN_BUCKET,
    SLIDING_WINDOW,
    FIXED_WINDOW,
    LEAKY_BUCKET
}
```

Create `src/main/java/com/ratelimiter/model/RateLimitConfig.java`:

```java
package com.ratelimiter.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitConfig {
    private String keyPattern;
    private RateLimitAlgorithm algorithm;
    
    // Token Bucket parameters
    private Integer capacity;
    private Double refillRate;
    private Integer refillPeriodSeconds;
    
    // Priority for pattern matching (higher = more specific)
    private Integer priority;
    
    // Metadata
    private Instant createdAt;
    private Instant updatedAt;

    // Validation
    public void validate() {
        if (capacity == null || capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        if (refillRate == null || refillRate <= 0) {
            throw new IllegalArgumentException("Refill rate must be positive");
        }
        if (refillPeriodSeconds == null || refillPeriodSeconds <= 0) {
            throw new IllegalArgumentException("Refill period must be positive");
        }
    }
}
```

Create `src/main/java/com/ratelimiter/dto/RateLimitRequest.java`:

```java
package com.ratelimiter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitRequest {
    @NotBlank(message = "Key cannot be blank")
    private String key;
    
    @Min(value = 1, message = "Tokens must be at least 1")
    private int tokens = 1;
    
    // Optional metadata
    private String clientIp;
    private String endpoint;
}
```

Create `src/main/java/com/ratelimiter/dto/RateLimitResponse.java`:

```java
package com.ratelimiter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RateLimitResponse {
    private boolean allowed;
    private long remainingTokens;
    private Instant resetTime;
    private Long retryAfterSeconds;
    private String algorithm;
    private RateLimitMetadata metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitMetadata {
        private String key;
        private String matchedPattern;
        private long latencyMicros;
    }

    public static RateLimitResponse allowed(long remaining, Instant resetTime, String algorithm) {
        return RateLimitResponse.builder()
                .allowed(true)
                .remainingTokens(remaining)
                .resetTime(resetTime)
                .algorithm(algorithm)
                .build();
    }

    public static RateLimitResponse denied(long remaining, Instant resetTime, long retryAfter, String algorithm) {
        return RateLimitResponse.builder()
                .allowed(false)
                .remainingTokens(remaining)
                .resetTime(resetTime)
                .retryAfterSeconds(retryAfter)
                .algorithm(algorithm)
                .build();
    }
}
```

**Git Commit**: `git commit -m "Add domain models and DTOs"`

---

#### Step 8: Create Lua Script for Token Bucket (1.5 hours)

Create `src/main/resources/lua/token_bucket.lua`:

```lua
-- Token Bucket Algorithm
-- KEYS[1] = bucket key (e.g., "ratelimit:bucket:user:123")
-- ARGV[1] = requested tokens (int)
-- ARGV[2] = capacity (int)
-- ARGV[3] = refill rate (tokens per second, float)
-- ARGV[4] = current timestamp (milliseconds, long)
-- ARGV[5] = TTL in seconds (int)

-- Returns: {allowed (0/1), remaining_tokens (float), retry_after_seconds (float or 0)}

local bucket_key = KEYS[1]
local requested = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refill_rate = tonumber(ARGV[3])
local now_millis = tonumber(ARGV[4])
local ttl = tonumber(ARGV[5])

-- Fetch current bucket state
local bucket = redis.call('HMGET', bucket_key, 'tokens', 'last_refill_ms')
local current_tokens = tonumber(bucket[1])
local last_refill_ms = tonumber(bucket[2])

-- Initialize bucket if it doesn't exist
if current_tokens == nil then
    current_tokens = capacity
    last_refill_ms = now_millis
end

-- Calculate elapsed time and refill
local elapsed_ms = math.max(0, now_millis - last_refill_ms)
local elapsed_seconds = elapsed_ms / 1000.0
local tokens_to_add = elapsed_seconds * refill_rate
local new_tokens = math.min(capacity, current_tokens + tokens_to_add)

-- Check if request can be allowed
if new_tokens >= requested then
    -- Allow request: deduct tokens
    local remaining = new_tokens - requested
    redis.call('HMSET', bucket_key, 
        'tokens', tostring(remaining),
        'last_refill_ms', tostring(now_millis),
        'capacity', tostring(capacity),
        'refill_rate', tostring(refill_rate)
    )
    redis.call('EXPIRE', bucket_key, ttl)
    return {1, remaining, 0}  -- allowed=1, remaining, retry_after=0
else
    -- Deny request: calculate retry-after
    local tokens_needed = requested - new_tokens
    local retry_after_seconds = tokens_needed / refill_rate
    
    -- Update tokens without deducting
    redis.call('HMSET', bucket_key,
        'tokens', tostring(new_tokens),
        'last_refill_ms', tostring(now_millis)
    )
    redis.call('EXPIRE', bucket_key, ttl)
    return {0, new_tokens, retry_after_seconds}  -- allowed=0, remaining, retry_after
end
```

**Git Commit**: `git commit -m "Add Token Bucket Lua script"`

---

#### Step 9: Implement Token Bucket Algorithm Service (2 hours)

Create `src/main/java/com/ratelimiter/algorithm/TokenBucketAlgorithm.java`:

```java
package com.ratelimiter.algorithm;

import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketAlgorithm {

    private final RedisTemplate<String, Object> redisTemplate;
    private RedisScript<List> tokenBucketScript;

    @PostConstruct
    public void init() throws IOException {
        // Load Lua script
        ClassPathResource resource = new ClassPathResource("lua/token_bucket.lua");
        String scriptContent = new String(
            resource.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );
        this.tokenBucketScript = RedisScript.of(scriptContent, List.class);
        log.info("Token Bucket Lua script loaded successfully");
    }

    public RateLimitResponse checkLimit(String key, int requestedTokens, RateLimitConfig config) {
        long startTime = System.nanoTime();
        
        try {
            String bucketKey = "ratelimit:bucket:" + key;
            long nowMillis = System.currentTimeMillis();
            
            // Execute Lua script
            List<Object> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(bucketKey),
                requestedTokens,
                config.getCapacity(),
                config.getRefillRate() / config.getRefillPeriodSeconds(), // tokens per second
                nowMillis,
                3600  // TTL: 1 hour
            );

            if (result == null || result.size() < 3) {
                throw new IllegalStateException("Invalid response from Redis Lua script");
            }

            // Parse results
            int allowed = ((Number) result.get(0)).intValue();
            double remaining = ((Number) result.get(1)).doubleValue();
            double retryAfter = ((Number) result.get(2)).doubleValue();

            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            
            RateLimitResponse response;
            if (allowed == 1) {
                response = RateLimitResponse.allowed(
                    (long) remaining,
                    calculateResetTime(config),
                    "TOKEN_BUCKET"
                );
            } else {
                response = RateLimitResponse.denied(
                    (long) remaining,
                    calculateResetTime(config),
                    (long) Math.ceil(retryAfter),
                    "TOKEN_BUCKET"
                );
            }

            // Add metadata
            response.setMetadata(RateLimitResponse.RateLimitMetadata.builder()
                    .key(key)
                    .latencyMicros(latencyMicros)
                    .build());

            log.debug("Rate limit check for key={}: allowed={}, remaining={}, latency={}μs",
                    key, allowed == 1, remaining, latencyMicros);

            return response;

        } catch (Exception e) {
            log.error("Error checking rate limit for key: {}", key, e);
            throw new RuntimeException("Rate limit check failed", e);
        }
    }

    private Instant calculateResetTime(RateLimitConfig config) {
        return Instant.now().plusSeconds(config.getRefillPeriodSeconds());
    }
}
```

**Git Commit**: `git commit -m "Implement Token Bucket algorithm with Lua script"`

---

### Day 7-8: Service Layer & REST API

#### Step 10: Create Configuration Service (1.5 hours)

Create `src/main/java/com/ratelimiter/service/ConfigService.java`:

```java
package com.ratelimiter.service;

import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${ratelimiter.default.capacity}")
    private Integer defaultCapacity;

    @Value("${ratelimiter.default.refill-rate}")
    private Integer defaultRefillRate;

    @Value("${ratelimiter.default.refill-period-seconds}")
    private Integer defaultRefillPeriod;

    public RateLimitConfig getConfig(String key) {
        // Try exact key match first
        RateLimitConfig config = getConfigFromRedis("config:key:" + key);
        if (config != null) {
            return config;
        }

        // Try pattern matching (simplified for now)
        // TODO: Implement proper pattern matching in Phase 3
        
        // Return default configuration
        return getDefaultConfig();
    }

    public void saveConfig(String key, RateLimitConfig config) {
        config.validate();
        config.setUpdatedAt(Instant.now());
        if (config.getCreatedAt() == null) {
            config.setCreatedAt(Instant.now());
        }

        String redisKey = "config:key:" + key;
        redisTemplate.opsForHash().put(redisKey, "algorithm", config.getAlgorithm().name());
        redisTemplate.opsForHash().put(redisKey, "capacity", config.getCapacity());
        redisTemplate.opsForHash().put(redisKey, "refillRate", config.getRefillRate());
        redisTemplate.opsForHash().put(redisKey, "refillPeriodSeconds", config.getRefillPeriodSeconds());
        redisTemplate.expire(redisKey, 30, TimeUnit.DAYS);

        log.info("Saved configuration for key: {}", key);
    }

    public void deleteConfig(String key) {
        String redisKey = "config:key:" + key;
        redisTemplate.delete(redisKey);
        log.info("Deleted configuration for key: {}", key);
    }

    private RateLimitConfig getConfigFromRedis(String redisKey) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(redisKey))) {
            return null;
        }

        try {
            String algorithmStr = (String) redisTemplate.opsForHash().get(redisKey, "algorithm");
            Integer capacity = (Integer) redisTemplate.opsForHash().get(redisKey, "capacity");
            Double refillRate = (Double) redisTemplate.opsForHash().get(redisKey, "refillRate");
            Integer refillPeriod = (Integer) redisTemplate.opsForHash().get(redisKey, "refillPeriodSeconds");

            return RateLimitConfig.builder()
                    .algorithm(RateLimitAlgorithm.valueOf(algorithmStr))
                    .capacity(capacity)
                    .refillRate(refillRate)
                    .refillPeriodSeconds(refillPeriod)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load config from Redis key: {}", redisKey, e);
            return null;
        }
    }

    private RateLimitConfig getDefaultConfig() {
        return RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(defaultCapacity)
                .refillRate(defaultRefillRate.doubleValue())
                .refillPeriodSeconds(defaultRefillPeriod)
                .priority(0)
                .build();
    }
}
```

**Git Commit**: `git commit -m "Add configuration service with Redis storage"`

---

#### Step 11: Create Rate Limit Service (2 hours)

Create `src/main/java/com/ratelimiter/service/RateLimitService.java`:

```java
package com.ratelimiter.service;

import com.ratelimiter.algorithm.TokenBucketAlgorithm;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final TokenBucketAlgorithm tokenBucketAlgorithm;
    private final ConfigService configService;
    private final MetricsService metricsService;

    public RateLimitResponse checkLimit(RateLimitRequest request) {
        long startTime = System.nanoTime();
        
        try {
            // Get configuration for this key
            RateLimitConfig config = configService.getConfig(request.getKey());
            
            // Execute rate limit check (only Token Bucket for Phase 1)
            RateLimitResponse response = tokenBucketAlgorithm.checkLimit(
                request.getKey(),
                request.getTokens(),
                config
            );

            // Record metrics
            long latencyMicros = (System.nanoTime() - startTime) / 1000;
            metricsService.recordCheck(response, latencyMicros);

            return response;

        } catch (Exception e) {
            log.error("Error processing rate limit check for key: {}", request.getKey(), e);
            // Fail open: allow request on error but log it
            metricsService.recordError();
            return RateLimitResponse.builder()
                    .allowed(true)
                    .remainingTokens(-1)
                    .build();
        }
    }
}
```

**Git Commit**: `git commit -m "Add rate limit service with metrics"`

---

#### Step 12: Create REST Controller (1.5 hours)

Create `src/main/java/com/ratelimiter/controller/RateLimitController.java`:

```java
package com.ratelimiter.controller;

import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitConfig;
import com.ratelimiter.service.ConfigService;
import com.ratelimiter.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/ratelimit")
@RequiredArgsConstructor
@Tag(name = "Rate Limiter", description = "Rate limiting operations")
public class RateLimitController {

    private final RateLimitService rateLimitService;
    private final ConfigService configService;

    @PostMapping("/check")
    @Operation(summary = "Check rate limit", description = "Check if request is allowed for given key")
    public ResponseEntity<RateLimitResponse> checkRateLimit(
            @Valid @RequestBody RateLimitRequest request) {
        
        log.debug("Rate limit check request: {}", request);
        RateLimitResponse response = rateLimitService.checkLimit(request);

        if (response.isAllowed()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("X-RateLimit-Remaining", String.valueOf(response.getRemainingTokens()))
                    .header("X-RateLimit-Reset", response.getResetTime().toString())
                    .header("Retry-After", String.valueOf(response.getRetryAfterSeconds()))
                    .body(response);
        }
    }

    @GetMapping("/config/{key}")
    @Operation(summary = "Get configuration", description = "Get rate limit configuration for a key")
    public ResponseEntity<RateLimitConfig> getConfig(@PathVariable String key) {
        RateLimitConfig config = configService.getConfig(key);
        return ResponseEntity.ok(config);
    }

    @PostMapping("/config")
    @Operation(summary = "Save configuration", description = "Save rate limit configuration for a key")
    public ResponseEntity<Void> saveConfig(
            @RequestParam String key,
            @Valid @RequestBody RateLimitConfig config) {
        
        configService.saveConfig(key, config);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/config/{key}")
    @Operation(summary = "Delete configuration", description = "Delete rate limit configuration for a key")
    public ResponseEntity<Void> deleteConfig(@PathVariable String key) {
        configService.deleteConfig(key);
        return ResponseEntity.noContent().build();
    }
}
```

**Git Commit**: `git commit -m "Add REST API endpoints for rate limiting"`

---

### Day 9-10: Metrics, Testing & Documentation

#### Step 13: Implement Metrics Service (1.5 hours)

Create `src/main/java/com/ratelimiter/service/MetricsService.java`:

```java
package com.ratelimiter.service;

import com.ratelimiter.dto.RateLimitResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MetricsService {

    private final Counter allowedCounter;
    private final Counter deniedCounter;
    private final Counter errorCounter;
    private final Timer checkLatencyTimer;

    public MetricsService(MeterRegistry registry) {
        this.allowedCounter = Counter.builder("ratelimit.checks.allowed")
                .description("Number of allowed rate limit checks")
                .register(registry);

        this.deniedCounter = Counter.builder("ratelimit.checks.denied")
                .description("Number of denied rate limit checks")
                .register(registry);

        this.errorCounter = Counter.builder("ratelimit.checks.errors")
                .description("Number of rate limit check errors")
                .register(registry);

        this.checkLatencyTimer = Timer.builder("ratelimit.check.latency")
                .description("Rate limit check latency")
                .register(registry);
    }

    public void recordCheck(RateLimitResponse response, long latencyMicros) {
        if (response.isAllowed()) {
            allowedCounter.increment();
        } else {
            deniedCounter.increment();
        }
        
        checkLatencyTimer.record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    public void recordError() {
        errorCounter.increment();
    }
}
```

**Git Commit**: `git commit -m "Add Prometheus metrics for rate limiter"`

---

#### Step 14: Write Unit Tests (3 hours)

Create `src/test/java/com/ratelimiter/algorithm/TokenBucketAlgorithmTest.java`:

```java
package com.ratelimiter.algorithm;

import com.ratelimiter.config.RedisConfig;
import com.ratelimiter.dto.RateLimitResponse;
import com.ratelimiter.model.RateLimitAlgorithm;
import com.ratelimiter.model.RateLimitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {RedisConfig.class, TokenBucketAlgorithm.class})
@Testcontainers
class TokenBucketAlgorithmTest {

    @Container
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    private TokenBucketAlgorithm tokenBucketAlgorithm;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private RateLimitConfig testConfig;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        // Create test configuration
        testConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(10)
                .refillRate(10.0)
                .refillPeriodSeconds(60)
                .build();
    }

    @Test
    void shouldAllowRequestWithinLimit() {
        // Given
        String key = "test:user:1";

        // When
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 1, testConfig);

        // Then
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(9);
        assertThat(response.getAlgorithm()).isEqualTo("TOKEN_BUCKET");
    }

    @Test
    void shouldDenyRequestExceedingLimit() {
        // Given
        String key = "test:user:2";

        // When - consume all tokens
        for (int i = 0; i < 10; i++) {
            tokenBucketAlgorithm.checkLimit(key, 1, testConfig);
        }
        
        // Then - next request should be denied
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 1, testConfig);
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.getRemainingTokens()).isEqualTo(0);
        assertThat(response.getRetryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void shouldRefillTokensOverTime() throws InterruptedException {
        // Given
        String key = "test:user:3";
        RateLimitConfig quickRefillConfig = RateLimitConfig.builder()
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .capacity(5)
                .refillRate(5.0)  // 5 tokens per second
                .refillPeriodSeconds(1)
                .build();

        // When - consume all tokens
        for (int i = 0; i < 5; i++) {
            tokenBucketAlgorithm.checkLimit(key, 1, quickRefillConfig);
        }

        // Wait for refill
        Thread.sleep(1100);  // 1.1 seconds

        // Then - should have refilled approximately 5 tokens
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 1, quickRefillConfig);
        assertThat(response.isAllowed()).isTrue();
    }

    @Test
    void shouldHandleMultipleTokenRequests() {
        // Given
        String key = "test:user:4";

        // When
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 5, testConfig);

        // Then
        assertThat(response.isAllowed()).isTrue();
        assertThat(response.getRemainingTokens()).isEqualTo(5);
    }

    @Test
    void shouldNotAllowMoreTokensThanCapacity() {
        // Given
        String key = "test:user:5";

        // When
        RateLimitResponse response = tokenBucketAlgorithm.checkLimit(key, 15, testConfig);

        // Then
        assertThat(response.isAllowed()).isFalse();
    }
}
```

**Run Tests**: `./mvnw test -Dtest=TokenBucketAlgorithmTest`

**Git Commit**: `git commit -m "Add comprehensive unit tests for Token Bucket"`

---

#### Step 15: Integration Tests (2 hours)

Create `src/test/java/com/ratelimiter/integration/RateLimitIntegrationTest.java`:

```java
package com.ratelimiter.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.dto.RateLimitRequest;
import com.ratelimiter.dto.RateLimitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RateLimitIntegrationTest {

    @Container
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnOkForAllowedRequest() throws Exception {
        RateLimitRequest request = RateLimitRequest.builder()
                .key("integration:test:1")
                .tokens(1)
                .build();

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"));
    }

    @Test
    void shouldReturn429ForExceededLimit() throws Exception {
        String key = "integration:test:2";
        RateLimitRequest request = RateLimitRequest.builder()
                .key(key)
                .tokens(1)
                .build();

        // Exhaust the limit (default is 10)
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/ratelimit/check")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // Next request should be denied
        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(header().exists("Retry-After"));
    }
}
```

**Run Tests**: `./mvnw test -Dtest=RateLimitIntegrationTest`

**Git Commit**: `git commit -m "Add integration tests with Testcontainers"`

---

#### Step 16: Health Checks & Documentation (1.5 hours)

Create `src/main/java/com/ratelimiter/config/HealthConfig.java`:

```java
package com.ratelimiter.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@RequiredArgsConstructor
public class HealthConfig {

    private final RedisTemplate<String, Object> redisTemplate;

    @Bean
    public HealthIndicator rateLimiterHealthIndicator() {
        return () -> {
            try {
                // Test Redis connectivity
                redisTemplate.getConnectionFactory()
                    .getConnection()
                    .serverCommands()
                    .ping();
                
                return Health.up()
                        .withDetail("redis", "connected")
                        .build();
            } catch (Exception e) {
                return Health.down()
                        .withDetail("redis", "disconnected")
                        .withException(e)
                        .build();
            }
        };
    }
}
```

Create `README.md` in project root:

```markdown
# Distributed Rate Limiter

High-performance distributed rate limiter with Token Bucket algorithm.

## Features
- Token Bucket rate limiting
- Redis-backed distributed state
- REST API
- Prometheus metrics
- Health checks

## Quick Start

### Prerequisites
- Java 21+
- Docker (for Redis)

### Run Locally
```bash
# Start Redis
docker-compose up -d

# Run application
./mvnw spring-boot:run

# Test
curl -X POST http://localhost:8080/api/ratelimit/check \
  -H "Content-Type: application/json" \
  -d '{"key":"user:123","tokens":1}'
```

## API Endpoints
- `POST /api/ratelimit/check` - Check rate limit
- `GET /api/ratelimit/config/{key}` - Get configuration
- `GET /actuator/health` - Health check
- `GET /actuator/prometheus` - Metrics

## Configuration
See `application.yml` for configuration options.
```

**Git Commit**: `git commit -m "Add health checks and documentation"`

---

## Phase 1 Completion Checklist

✅ **Deliverables**:
- Working Spring Boot application
- Token Bucket algorithm implemented
- REST API (check, config endpoints)
- Redis integration with Lua scripts
- Unit tests (90%+ coverage)
- Integration tests with Testcontainers
- Prometheus metrics
- Health checks
- Basic documentation

✅ **Performance Targets**:
- Latency: < 5ms P95 (will optimize in later phases)
- Throughput: 10,000+ RPS
- Success rate: 100% under normal load

✅ **Git History**:
- 16 meaningful commits
- Clean commit messages
- Buildable at each commit

---

## 🚀 Phase 2-6 Overview

I'll create similarly detailed plans for remaining phases. Would you like me to:

1. **Continue with Phase 2** (Multi-Algorithm Support)?
2. **Create a summary roadmap** for all phases?
3. **Provide deployment instructions** for Phase 1?
4. **Help debug/troubleshoot** any Phase 1 steps?

Let me know which direction you'd like to go next!