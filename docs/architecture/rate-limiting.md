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
    enabled: true            # FINAXIS_RATE_LIMIT_ENABLED
    include-headers: true    # FINAXIS_RATE_LIMIT_INCLUDE_HEADERS
    fail-open: true          # FINAXIS_RATE_LIMIT_FAIL_OPEN
    policies:                # exactly the RateLimitPolicyId entries, no more, no fewer
      tenant-read:
        capacity: 600
        refill-tokens: 600
        refill-period: 1m
      # auth-selection, platform-read, platform-command, tenant-command and
      # tenant-export have the same three keys (defaults in the table below)
    paths:
      enabled: true
      excluded: [/, /actuator/health/**, /actuator/prometheus, /error, /scalar/**,
                 /v3/api-docs/**, /swagger-ui/**]
      rules:                 # first matching method and path wins
        - { method: GET, path: /api/v1/tenant/audit-events/export, policy: tenant-export }
        - { method: GET, path: /api/v1/**, policy: tenant-read }
```

The shape is `RateLimitProperties` (`enabled`, `include-headers`, `fail-open`, `policies`, `paths`)
and `RateLimitPathProperties` (`enabled`, `excluded`, `rules`); the complete set of policies and
rules is in `application.yaml` and mirrors the defaults in `RateLimitProperties.kt` and
`RateLimitPathProperties.kt`. Excluded paths and `OPTIONS` requests are not limited; any other
request that matches no rule is refused with `429` (`rate_limit_policy_unavailable`). For example
`HEAD /api/v1/branches` matches none (the rules name `GET`, `POST`, `PUT`, `PATCH` and `DELETE`),
and neither does a path outside `/api/v1` that is not excluded. `paths.enabled` switches only the
`excluded` list off, never the rules.

The switches and every policy's `capacity`, `refill-tokens` and `refill-period` have
`FINAXIS_RATE_LIMIT_*` environment variables; `paths.excluded` and `paths.rules` have none and
change through configuration properties. Keep limits out of business logic.

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

## Named Policies And Path Rules

The policy set is a closed enum (`RateLimitPolicyId`); `RateLimitProperties` refuses to start unless
the configured `finaxis.rate-limit.policies` are exactly its entries. A request is matched against
`finaxis.rate-limit.paths.rules` in order and the **first** matching method and path wins, so a
specific rule must come before a catch-all. Each policy takes `capacity`, `refill-tokens` and
`refill-period` from `FINAXIS_RATE_LIMIT_<POLICY>_CAPACITY`, `..._REFILL_TOKENS` and
`..._REFILL_PERIOD` (the policy id upper-cased, `-` as `_`).

| Policy            | Default (per minute) | Used by                                                   |
|-------------------|----------------------|-----------------------------------------------------------|
| `auth-selection`  | 20                   | `POST /api/v1/auth/select-organisation`, `select-branch`  |
| `platform-read`   | 600                  | `GET /api/v1/auth/**`, `GET /api/v1/platform/**`          |
| `platform-command`| 120                  | other methods of `/api/v1/auth/**` and `/api/v1/platform/**` |
| `tenant-read`     | 600                  | `GET /api/v1/**`                                          |
| `tenant-command`  | 120                  | `POST`, `PUT`, `PATCH`, `DELETE` of `/api/v1/**`          |
| `tenant-export`   | 5                    | `GET /api/v1/tenant/audit-events/export`                  |

### Audit export (`tenant-export`)

The audit CSV export ([ADR 0039](../adr/0039-global-search-and-audit-export.md), point 18) is a bulk
extraction, so it has its own, tighter policy: **5 requests per minute** by default (capacity 5,
refill 5 per 1 minute), set with `FINAXIS_RATE_LIMIT_TENANT_EXPORT_CAPACITY`,
`FINAXIS_RATE_LIMIT_TENANT_EXPORT_REFILL_TOKENS` and
`FINAXIS_RATE_LIMIT_TENANT_EXPORT_REFILL_PERIOD`.
The key is the usual per-user and tenant one, `rate-limit:tenant-export:auth:{tenantId}:{userId}`.
Its path rule `GET /api/v1/tenant/audit-events/export` sits **before** `GET /api/v1/**` in both
the defaults in `RateLimitPathProperties.kt` and `application.yaml`: without it the route would
fall under `tenant-read` and could be called 600 times a minute. A `429` carries the headers
above.

The limiter is fail-open like every other policy (`fail-open: true`); a per-policy fail-closed mode
is not offered. During a Redis outage the 31-day window and the 10,000-row cap remain the bound on
one export. The global search, which is an ordinary read, reuses `tenant-read` and has no policy of
its own.

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

The default is fail-open for availability if Redis is temporarily unavailable
(`finaxis.rate-limit.fail-open`, one switch for every policy; none is fail-closed on its own).
Sensitive routes get a tighter policy by path rule instead, as the audit export does with
`tenant-export`. Failures are logged as warnings.

## Testing

Use unit tests for policy decisions and Testcontainers-backed integration tests for wired Redis
configuration. Do not add a second rate limiter.
