# ADR 0003: API Governance, Distributed Rate Limiting, Versioning, And Pagination

## Status

Accepted

## Context

The repository is young but expected to grow into a multi-instance modular monolith. Public API
shape, abuse protection, auditability, and operational conventions need to be enforced early.

OWASP API Security 2023 identifies unrestricted resource consumption as a material API risk.
Spring Boot Actuator documentation recommends intentional endpoint exposure, and Spring Modulith
verification requires module dependencies to remain acyclic.

## Decision

- All public APIs are versioned under `/api/v1`, `/api/v2`, and future explicit path versions.
- Listing APIs must be paginated and must not return unbounded raw collections.
- API defaults use `finaxis.pagination`.
- Production rate limiting uses Bucket4j with Redis-backed distributed buckets.
- Redis access for rate limiting uses Lettuce so deployments can choose standalone Redis, Sentinel,
  or Redis Cluster.
- Anonymous and authenticated rate limits are separately configurable.
- Rate-limit rejection returns HTTP 429 with standard rate-limit headers.
- Request correlation uses `X-Request-Id` and MDC.
- Audit is exposed through a common application service and repository port.
- Spring Modulith boundaries remain mandatory; common governance packages expose named interfaces.

## Consequences

- In-memory throttling cannot be the production implementation.
- Controllers and smoke scripts must be updated whenever public API paths change.
- New listing endpoints need pagination design before merging.
- Redis must be treated as production infrastructure for API abuse protection.
- Future durable audit persistence can replace the default logging repository without changing
  callers.

## Alternatives Considered

Use an in-memory rate limiter:

- Rejected. It fails in a multi-instance deployment.

Use a Bucket4j Spring Boot starter:

- Deferred. The direct Bucket4j + Lettuce integration is explicit and avoids starter compatibility
  risk with Spring Boot 4.1.

Use header-only API versioning:

- Rejected for the baseline. Path versions are easier for clients, smoke tests, routing, and docs.

Use JobRunr as an outbox or event bus:

- Rejected. JobRunr is for background jobs. Namastack Outbox remains the transactional event
  externalization engine.

## Verification

- `ApiVersioningArchitectureTest`
- `PaginationArchitectureTest`
- `RateLimitFilterTests`
- `RateLimitPropertiesTests`
- `ModulithArchitectureTest`
- `scripts/local-smoke.sh`
