# Distributed Rate Limiting

Finaxis uses Bucket4j with Lettuce-backed Redis state for production API throttling. In-memory
rate limiting is acceptable only as a unit-test double and must not be the production path.

## Why Redis And Bucket4j

The application runs multiple instances. Redis gives all instances shared token-bucket state.
Bucket4j provides deterministic token-bucket semantics and Redis integration without tying the
application to a Spring Boot starter that may lag Spring Boot 4.x compatibility.

## Configuration

```yaml
finaxis:
  rate-limit:
    enabled: true
    include-headers: true
    fail-open: true
    anonymous:
      capacity: 100
      refill-tokens: 100
      refill-period: 1m
    authenticated:
      capacity: 1000
      refill-tokens: 1000
      refill-period: 1m
```

All values can be overridden with environment variables. Keep limits out of business logic.

## Keys

- Authenticated: `rate-limit:auth:{tenantId}:{userId}` when tenant context exists.
- Authenticated without tenant context: `rate-limit:auth:global:{subject}`.
- Anonymous: `rate-limit:anon:{remoteAddress}`.

`remoteAddress` relies on Spring's configured forwarded-header handling. Do not parse arbitrary
`X-Forwarded-For` headers directly in application code.

## Redis Topologies

The rate limiter uses Lettuce because deployments may use:

- standalone Redis for local development and small environments;
- Redis Sentinel for failover with a primary endpoint selected by Sentinel;
- Redis Cluster for sharded production deployments.

Local Compose uses a simple standalone Redis instance. Production should configure the appropriate
`spring.data.redis.*` topology:

```yaml
spring:
  data:
    redis:
      host: redis
      port: 6379
```

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - redis-sentinel-1:26379
          - redis-sentinel-2:26379
```

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - redis-cluster-1:6379
          - redis-cluster-2:6379
```

Use Redis credentials from environment or secret stores. Do not commit production credentials.

## Response

Rejected requests return HTTP `429 Too Many Requests` with:

- `RateLimit-Limit`
- `RateLimit-Remaining`
- `RateLimit-Reset`
- `Retry-After`

The response body uses `application/problem+json` and does not expose internal keys or policy
details.

## Failure Mode

The default is fail-open for availability if Redis is temporarily unavailable. Sensitive/admin
paths may later use stricter policies if explicitly configured. Failures are logged as warnings.

## Testing

Use unit tests for policy decisions and Testcontainers-backed integration tests for wired Redis
configuration. Do not add a second rate limiter.
