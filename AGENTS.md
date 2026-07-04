# Repository Agent Instructions

This project uses Kotlin, Java 25, Spring Boot Web MVC, Flyway, Spring Data JDBC, and a hexagonal architecture.

Keep authentication, identity, tenant context, and authorization separate. Keycloak is for authentication only; application permissions are the runtime authorization source of truth.

Before adding code, pause and evaluate whether the implementation is the simplest production-grade option. Avoid unnecessary abstractions, but keep domain, application ports/services, and adapters separated.

Business authorization must use permission codes, not role names. Controllers may use `@PreAuthorize("hasAuthority('permission.code')")`; services must use `AuthorizationService` for resource-specific checks.

Do not introduce WebFlux or reactive types. This is a Spring Web MVC application.

Use Springdoc annotations for all public APIs. Use centralized API exception handling for predictable error responses.

Sentry must be disabled for local development by default. Enable it only through environment-specific configuration.
