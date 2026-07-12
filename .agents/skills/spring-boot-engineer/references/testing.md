# Testing - Spring Web MVC, Spring Data JDBC, Testcontainers

Use focused unit tests plus Spring integration tests. Integration tests should exercise the wired application path across controllers, security, services, persistence adapters, Flyway schema/data, and infrastructure boundaries.

## Web Integration

- Use `@SpringBootTest` with `@AutoConfigureMockMvc` for public API contract coverage.
- Use `MockMvc` or `MockMvcTester` for Spring Web MVC endpoints.
- Use `spring-security-test` JWT request post-processors for resource-server tests.
- Public endpoints must be versioned under `/api/v1`, `/api/v2`, etc.

## Data Integration

- Use Testcontainers PostgreSQL for persistence adapter tests.
- Use Spring Data JDBC repositories and jOOQ against the real migrated schema.
- Do not rely on embedded databases for production-parity persistence tests.

## Architecture Gates

- Keep ArchUnit and Spring Modulith verification tests green.
- Run the repository quality gate before handoff when the environment provides Docker.
