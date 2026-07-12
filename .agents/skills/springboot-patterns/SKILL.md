---
name: springboot-patterns
description: Spring Boot architecture patterns for Finaxis: Spring Web MVC, Spring Data JDBC, caching, async jobs, logging, hexagonal boundaries, and production API design.
---

# Spring Boot Patterns for Finaxis

- Use Spring Boot Web MVC, not non-MVC web stacks.
- Use Spring Data JDBC and jOOQ; do not introduce ORM persistence.
- Keep domain, application ports/services, and adapters separated.
- Use DTOs, Bean Validation, Springdoc annotations, centralized exception handling, versioned APIs, and pagination.
- Treat Keycloak as authentication provider only; the application is an OAuth2 resource server and authorization uses permission codes.
- Use Bucket4j + Redis for production rate limiting and preserve `X-Request-Id` correlation.
- Use Spring Modulith events, Namastack Outbox, RabbitMQ for selected event publication, and JobRunr for durable background jobs.
