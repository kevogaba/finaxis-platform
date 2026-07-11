# Project Guidance

This is a Kotlin-first Spring Boot Web MVC service running on Java 25. Prefer Kotlin
for application code. Do not mix Java into new code unless there is a specific
framework or runtime reason, and document that reason in the change.

The architecture is hexagonal:

- `domain`: framework-independent model and value objects
- `application`: ports and use-case services
- `adapter`: persistence, web, and security adapters
- `config`: application configuration

Before implementing or changing code, pause and ask whether the approach is overly
complex. Simplify where possible while preserving security boundaries and testability.

Authorization rules:

- Keycloak authenticates users only.
- This application is an OAuth2 resource server. Do not add application-managed password handling,
  password authentication endpoints, password storage, or password checks. Local smoke scripts may
  obtain dev-only Keycloak tokens, but application code must not handle user credentials.
- The application owns users, organisations, memberships, roles, permissions, scopes,
  and authorization rules.
- Runtime authorization evaluates permission codes, never role names.
- Active organisation is request/session context, not a permanent `app_user` field.
- Controllers use permission authorities for coarse gates.
- Application services enforce resource-specific authorization.

FSM/event architecture:

- Reusable transition infrastructure lives in `com.finaxis.platform.common.transitions`.
- Domain modules define their own state enums, transition enums, graphs, guards, policies,
  persistence adapters, and explicit domain events.
- Transition direction must be deterministic; declare every legal source state, transition name,
  and target state in a `TransitionDefinition`.
- Keep state mutation, transition validation, log creation, event publication, broker publishing,
  and background jobs separated.
- Use Spring Modulith for module boundaries, application events, cross-module listeners, and
  verification.
- Use Namastack Outbox for transactional event externalization.
- Externalize only selected events to RabbitMQ with clear routing names.
- Keep RabbitMQ listeners thin: deserialize, validate, delegate to an application service, handle
  idempotency, and ack/nack based on outcome.
- Use JobRunr for durable background jobs such as email, SMS, reports, imports, exports, retries,
  and recurring work. Do not use JobRunr as the primary outbox/event externalization engine.
- See `docs/architecture/fsm-transitions.md` and
  `docs/adr/0002-fsm-transition-infrastructure.md` before touching transitions, events,
  Modulith boundaries, outbox, RabbitMQ, or background processing.

All public APIs must be documented with Springdoc/OpenAPI annotations. API errors
should go through centralized exception handling.

API governance:

- All public API endpoints must be versioned under `/api/v1`, `/api/v2`, etc.
- Never add an unversioned public API endpoint.
- All listing APIs must use pagination.
- Never return unbounded collections from listing endpoints.
- Use DTOs at API boundaries unless explicitly documented otherwise.
- Use Bean Validation for request DTOs and typed configuration properties.
- Update smoke tests, docs, and examples whenever endpoint paths change.

Rate limiting, logging, and audit:

- Use Bucket4j + Redis for distributed production rate limiting.
- Keep Redis access Lettuce-based so standalone Redis, Sentinel, and Cluster remain deployment
  options. Local development may use standalone Redis.
- Do not implement per-instance or in-memory-only rate limiting for production paths.
- Rate-limit values must be configurable.
- Preserve request correlation with `X-Request-Id` and MDC cleanup.
- Do not log secrets, bearer tokens, passwords, authorization headers, session cookies, API keys,
  or sensitive PII.
- Audit admin actions and critical state-changing operations through the common audit service or
  event listeners. Do not scatter audit logging in controllers.

Sentry is disabled for local development by default. Use environment configuration to
enable it outside local development.

## Testing

Every implementation must maintain both unit tests and Spring integration tests in the
test hierarchy.

- Unit tests cover focused domain, application-service, and adapter behavior.
- Spring integration tests exercise wired application paths across controllers, security,
  application services, persistence adapters, Flyway-managed schema/data, and external
  infrastructure boundaries.
- Use Testcontainers for infrastructure dependencies such as PostgreSQL, Redis, RabbitMQ,
  observability backends, or other services instead of relying on manually running local
  instances.

Because this project uses hexagonal architecture, public interfaces for every module must
have regression-focused integration coverage. This includes public-facing controllers and
other inbound adapters, plus meaningful public application ports/services where behavior
crosses module or adapter boundaries. Prefer tests that verify stable external behavior and
contracts over tests coupled to private implementation details.

## Static Analysis

Static checks are intentionally strict while the project is still small:

- Spotless formats Kotlin, Gradle Kotlin DSL, Java, Markdown, YAML, XML, and related
  text files.
- ktlint runs through Spotless for Kotlin and Gradle Kotlin DSL.
- Detekt analyzes Kotlin with all rules enabled, zero allowed findings, and KDoc
  required for public production classes/functions.
- Checkstyle enforces Java style, naming, import, line-length, and JavaDoc rules.
- PMD enforces Java source-level maintainability and best-practice rules.
- SpotBugs enforces high-confidence Java bytecode bug checks.
- Error Prone runs as a `javac` plugin for Java compilation only.
- ArchUnit enforces project-specific hexagonal package boundaries.
- Spring Modulith verifies Spring application module boundaries.

The maximum line length is 100 characters across Kotlin, Java, Gradle files, YAML,
XML, and Markdown where feasible. Spotless is the primary formatter.

Prefer code cleanup over suppressions. When a suppression is unavoidable, keep it
narrow and explain why.

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

See `docs/development/static-analysis.md` for the static-analysis setup and command
reference.
