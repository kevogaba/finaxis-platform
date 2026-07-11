# Repository Agent Instructions

This project uses Kotlin, Java 25, Spring Boot Web MVC, Flyway, Spring Data JDBC, and a hexagonal architecture.

Keep authentication, identity, tenant context, and authorization separate. Keycloak is for authentication only; application permissions are the runtime authorization source of truth.
This application is an OAuth2 resource server. Do not add application-managed password handling,
password authentication endpoints, password storage, or password checks. Local smoke scripts may
obtain dev-only Keycloak tokens, but application code must not handle user credentials.

Before adding code, pause and evaluate whether the implementation is the simplest production-grade option. Avoid unnecessary abstractions, but keep domain, application ports/services, and adapters separated.

Business authorization must use permission codes, not role names. Controllers may use `@PreAuthorize("hasAuthority('permission.code')")`; services must use `AuthorizationService` for resource-specific checks.

Reusable FSM transition infrastructure lives in `com.finaxis.platform.common.transitions`.
Domain modules must define their own state enums, transition enums, transition graphs, guards,
policies, persistence adapters, and explicit business events. Transition direction must be
deterministic and declared through `TransitionDefinition`; do not infer legal moves from enum order,
role names, UI actions, or old status strings.

Keep state mutation, transition validation, transition log creation, event publication, broker
publishing, and background jobs separated. Use Spring Modulith for module boundaries, application
events, cross-module reactions, observability, and module verification. Use Namastack Outbox as the
transactional outbox engine. Externalize only selected Modulith events to RabbitMQ with strongly
named routing destinations. Keep RabbitMQ listeners thin: deserialize, validate, delegate to an
application service, handle idempotency where needed, and ack/nack based on outcome. Use JobRunr for
durable background jobs such as email, SMS, reports, imports, exports, retries, scheduled jobs, and
long-running operational work. Do not use JobRunr as the primary outbox or event externalization
engine. See `docs/architecture/fsm-transitions.md` and
`docs/adr/0002-fsm-transition-infrastructure.md` before touching transitions, events, modular
boundaries, outbox, RabbitMQ, or background processing.

Do not introduce WebFlux or reactive types. This is a Spring Web MVC application.

Use Springdoc annotations for all public APIs. Use centralized API exception handling for predictable error responses.

API governance is mandatory:

- All public API endpoints must be versioned under `/api/v1`, `/api/v2`, etc.
- Never add an unversioned public API endpoint.
- All listing APIs must use pagination.
- Never return unbounded collections from listing endpoints.
- Use DTOs at API boundaries unless an exception is explicitly documented.
- Use Bean Validation for request DTOs and typed configuration properties.
- Update smoke tests, docs, and examples whenever endpoint paths change.

Rate limiting, logging, and audit rules:

- Use Bucket4j + Redis for distributed production rate limiting.
- Keep the Redis implementation Lettuce-based so standalone Redis, Sentinel, and Cluster remain
  deployment options. Local development may use standalone Redis.
- Do not implement per-instance or in-memory-only rate limiting for production paths.
- Rate-limit values must be configurable.
- Preserve request correlation with `X-Request-Id` and MDC cleanup.
- Do not log secrets, bearer tokens, passwords, authorization headers, session cookies, API keys,
  or sensitive PII.
- Audit admin actions and critical state-changing operations through the common audit service or
  event listeners. Do not scatter audit logging in controllers.

Sentry must be disabled for local development by default. Enable it only through environment-specific configuration.

Every implementation must maintain both unit tests and Spring integration tests in the test
hierarchy. Unit tests should cover focused domain, application-service, and adapter behavior.
Integration tests should exercise the wired Spring application path across controllers,
security, application services, persistence adapters, Flyway-managed schema/data, and external
infrastructure boundaries. Use Testcontainers for infrastructure dependencies such as
PostgreSQL, Redis, RabbitMQ, observability backends, or other services rather than relying on
manually running local instances.

Because this project uses hexagonal architecture, public interfaces for every module must have
regression-focused integration coverage. This includes public-facing controllers and other
inbound adapters, plus meaningful public application ports/services where behavior crosses
module or adapter boundaries. Prefer tests that verify stable external behavior and contracts
over tests coupled to private implementation details.

Static analysis is part of the project quality gate. Keep Spotless, ktlint, Detekt,
Checkstyle, PMD, SpotBugs, Error Prone, ArchUnit, Spring Modulith tests, JaCoCo
coverage, and `bootJar` green before handing work back. Prefer fixing code over
suppressing rules; add suppressions only when there is no practical alternative, and include a
narrow explanation.

Detekt uses the `dev.detekt` 2.x alpha line because the project is on Kotlin 2.3.21.
See `docs/development/static-analysis.md` for the static-analysis setup.

## Mandatory quality gates

After every implementation, run the relevant checks before finalizing work:

```bash
./gradlew spotlessCheck
./gradlew ktlintCheck
./gradlew detekt
./gradlew checkstyleMain checkstyleTest
./gradlew pmdMain pmdTest
./gradlew spotbugsMain spotbugsTest
./gradlew test
./gradlew check
```

The shortcut command is:

```bash
./gradlew qualityGate
```

For Kotlin sources, Spotless, ktlint, and Detekt must pass.

For Java sources, Checkstyle, PMD, SpotBugs, Error Prone, and tests must pass.

For architecture-sensitive changes, both ArchUnit and Spring Modulith verification
tests must pass. Spring Modulith verification is mandatory and should be treated as a
first-class architecture quality gate, just like ArchUnit and the static-analysis tools.

Do not bypass, disable, weaken, or suppress these checks without documenting the reason.
