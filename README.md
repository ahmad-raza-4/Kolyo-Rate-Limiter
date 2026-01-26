# Rate Limiter

## Overview
This project implements a robust, scalable rate limiting service designed for distributed systems. It provides fine-grained control over API usage, protecting backend resources from abuse and ensuring fair access for all clients.

## Aim
- Prevent API abuse and ensure system stability
- Support multiple rate limiting algorithms (e.g., Token Bucket)
- Integrate with Redis for distributed state management
- Expose RESTful endpoints for configuration and metrics

## Design
- **Modular Architecture:** Organized by domain (algorithm, config, controller, service, util)
- **Algorithm Flexibility:** Easily extendable to support new rate limiting strategies
- **Redis Integration:** Centralized, high-performance state management
- **Configurable:** Dynamic configuration via REST API and YAML files
- **Metrics & Monitoring:** Real-time metrics for observability
- **Extensible:** Clean separation of concerns for future enhancements

## Current State
- **Token Bucket Algorithm:** Fully implemented and tested
- **Redis Integration:** Configured for distributed rate limiting
- **REST API:** Endpoints for rate limit checks, configuration, and metrics
- **Configuration:** Supports environment-based YAML configs
- **Testing:** Unit and integration tests for core components
- **Docker Support:** Containerized for easy deployment

## Getting Started
1. **Clone the repository**
2. **Configure Redis** (see `application.yml`)
3. **Build and run**
   ```bash
   mvn clean package
   java -jar target/rate_limiter.jar
   ```
4. **API Usage**
   - Rate limit check: `/api/ratelimit/check`
   - Metrics: `/api/ratelimit/metrics`
   - Configuration: `/api/ratelimit/config`

## Project Structure
- `src/main/java/com/ratelimiter/` - Source code
- `src/main/resources/` - Configurations and Lua scripts
- `src/test/java/com/ratelimiter/` - Tests
- `Dockerfile`, `docker-compose.yml` - Containerization

## Future Work
- Add support for additional algorithms (e.g., Leaky Bucket, Fixed Window)
- Advanced metrics and alerting integration
- Admin dashboard for configuration and monitoring
- Multi-tenant support
- Enhanced security and authentication
- Performance benchmarking and optimization

---
For questions or contributions, please open an issue or submit a pull request.
