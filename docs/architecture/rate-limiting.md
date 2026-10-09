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

- Authenticated: `rate-limit:{policy}:auth:{tenantId}:{userId}` when tenant context exists.
- Authenticated without tenant context: `rate-limit:{policy}:auth:global:{subject}`.
- Anonymous: `rate-limit:{policy}:anon:{remoteAddress}`.

`remoteAddress` is `request.remoteAddr`, which Tomcat's `RemoteIpValve` sets
(`server.forward-headers-strategy: native`, #256) from `X-Forwarded-For` **only** when the direct
peer is listed in `finaxis.security.client-ip.trusted-proxies`; with the default empty list it is
always the peer. A client therefore cannot pick its anonymous bucket by sending a forged
`X-Forwarded-For` (or `Forwarded`, which is never read), and the number of anonymous keys is
bounded by the number of real client addresses. Two limits remain: an IPv6 client usually owns at
least a `/64`, so per-address anonymous buckets do not bound one IPv6 client; and the key is the
remote address text verbatim, so a listed proxy that writes `ip:port` entries would give one
bucket per source port (Traefik writes plain addresses; such a proxy is unsupported). Behind an
unlisted proxy every anonymous caller shares the proxy's bucket, so a deployment behind Traefik
must list it (see
`docs/security/production-hardening.md`, "Forwarded headers"). Do not parse `X-Forwarded-For`
headers directly in application code; `ClientIpResolver` (the audit address) is the one exception.

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
