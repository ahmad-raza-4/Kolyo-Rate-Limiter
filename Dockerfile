# ============================================
# Stage 1: Dependencies (Cached Layer)
# ============================================
FROM maven:3.9-eclipse-temurin-21-alpine AS dependencies
WORKDIR /app

# Copy only POM file first to cache dependencies
COPY pom.xml .

# Download dependencies (this layer is cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# ============================================
# Stage 2: Build Application
# ============================================
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy cached dependencies from previous stage
COPY --from=dependencies /root/.m2 /root/.m2

# Copy POM and source code
COPY pom.xml .
COPY src ./src

# Build the application (skip tests for faster builds, run tests in CI/CD)
RUN mvn clean package -DskipTests -B

# ============================================
# Stage 3: Runtime (Production)
# ============================================
FROM eclipse-temurin:21-jre-alpine

# Metadata labels
LABEL maintainer="Ahmad Raza"
LABEL description="Distributed Rate Limiter Service with Redis"
LABEL version="1.0.0"
LABEL org.opencontainers.image.source="https://github.com/ahmad-raza-4/Kolyo-Rate-Limiter.git"

# Build arguments for flexibility
ARG APP_USER=ratelimiter
ARG APP_UID=1000
ARG APP_GID=1000

# Install curl for health checks and dumb-init for proper signal handling
RUN apk add --no-cache curl dumb-init

# Create non-root user for security
RUN addgroup -g ${APP_GID} ${APP_USER} && \
    adduser -D -u ${APP_UID} -G ${APP_USER} ${APP_USER}

# Set working directory
WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/target/*.jar app.jar

# Change ownership to non-root user
RUN chown -R ${APP_USER}:${APP_USER} /app

# Switch to non-root user
USER ${APP_USER}

# Environment variables with sensible defaults
ENV JAVA_OPTS="-Xmx512m -Xms512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    SERVER_PORT="8080" \
    REDIS_HOST="redis" \
    REDIS_PORT="6379"

# Expose application port
EXPOSE 8080

# Health check (checks actuator health endpoint)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Use dumb-init to handle signals properly (PID 1 problem)
ENTRYPOINT ["dumb-init", "--"]

# Run application with JVM optimization flags
CMD ["sh", "-c", "java ${JAVA_OPTS} -jar app.jar"]
