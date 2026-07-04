# Project Guidance

This is a Kotlin-first Spring Boot Web MVC service running on Java 25. Prefer Kotlin for application code. Do not mix Java into new code unless there is a specific framework or runtime reason, and document that reason in the change.

The architecture is hexagonal:

- `domain`: framework-independent model and value objects
- `application`: ports and use-case services
- `adapter`: persistence, web, and security adapters
- `config`: application configuration

Before implementing or changing code, pause and ask whether the approach is overly complex. Simplify where possible while preserving security boundaries and testability.

Authorization rules:

- Keycloak authenticates users only.
- The application owns users, organisations, memberships, roles, permissions, scopes, and authorization rules.
- Runtime authorization evaluates permission codes, never role names.
- Active organisation is request/session context, not a permanent `app_user` field.
- Controllers use permission authorities for coarse gates.
- Application services enforce resource-specific authorization.

All public APIs must be documented with Springdoc/OpenAPI annotations. API errors should go through centralized exception handling.

Sentry is disabled for local development by default. Use environment configuration to enable it outside local development.
