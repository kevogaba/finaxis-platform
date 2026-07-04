# Repository Agent Instructions

This project uses Kotlin, Java 25, Spring Boot Web MVC, Flyway, Spring Data JDBC, and a hexagonal architecture.

Keep authentication, identity, tenant context, and authorization separate. Keycloak is for authentication only; application permissions are the runtime authorization source of truth.

Before adding code, pause and evaluate whether the implementation is the simplest production-grade option. Avoid unnecessary abstractions, but keep domain, application ports/services, and adapters separated.

Business authorization must use permission codes, not role names. Controllers may use `@PreAuthorize("hasAuthority('permission.code')")`; services must use `AuthorizationService` for resource-specific checks.

Do not introduce WebFlux or reactive types. This is a Spring Web MVC application.

Use Springdoc annotations for all public APIs. Use centralized API exception handling for predictable error responses.

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
suppressing rules; add suppressions only with a narrow explanation.

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
