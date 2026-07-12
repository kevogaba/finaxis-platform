---
name: kotlin-springboot
description: 'Get best practices for developing applications with Spring Boot and Kotlin.'
---

# Spring Boot with Kotlin Best Practices

Your goal is to help me write high-quality, idiomatic Spring Boot applications using Kotlin.

## Project Setup & Structure

- **Build Tool:** Use Maven (`pom.xml`) or Gradle (`build.gradle`) with the Kotlin plugins (`kotlin-maven-plugin` or `org.jetbrains.kotlin.jvm`).
- **Kotlin Plugins:** Use `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.spring`, and serialization as needed; do not add the ORM plugin for this JDBC project.
- **Starters:** Use Spring Boot starters (e.g., `spring-boot-starter-webmvc`, `spring-boot-starter-data-jdbc`) as usual.
- **Package Structure:** Organize code by feature/domain (e.g., `com.example.app.order`, `com.example.app.user`) rather than by layer.

## Dependency Injection & Components

- **Primary Constructors:** Always use the primary constructor for required dependency injection. It's the most idiomatic and concise approach in Kotlin.
- **Immutability:** Declare dependencies as `private val` in the primary constructor. Prefer `val` over `var` everywhere to promote immutability.
- **Component Stereotypes:** Use `@Service`, `@Repository`, and `@RestController` annotations just as you would in Java.

## Configuration

- **Externalized Configuration:** Use `application.yml` for its readability and hierarchical structure.
- **Type-Safe Properties:** Use `@ConfigurationProperties` with `data class` to create immutable, type-safe configuration objects.
- **Profiles:** Use Spring Profiles (`application-dev.yml`, `application-prod.yml`) to manage environment-specific configurations.
- **Secrets Management:** Never hardcode secrets. Use environment variables or a dedicated secret management tool like HashiCorp Vault or AWS Secrets Manager.

## Web Layer (Controllers)

- **RESTful APIs:** Design clear and consistent RESTful endpoints.
- **Data Classes for DTOs:** Use Kotlin `data class` for all DTOs. This provides `equals()`, `hashCode()`, `toString()`, and `copy()` for free and promotes immutability.
- **Validation:** Use Java Bean Validation (JSR 380) with annotations (`@Valid`, `@NotNull`, `@Size`) on your DTO data classes.
- **Error Handling:** Implement a global exception handler using `@ControllerAdvice` and `@ExceptionHandler` for consistent error responses.

## Service Layer

- **Business Logic:** Encapsulate business logic within `@Service` classes.
- **Statelessness:** Services should be stateless.
- **Transaction Management:** Use `@Transactional` on service methods. In Kotlin, this can be applied to class or function level.

## Data Layer (Repositories)

- **Spring Data JDBC Aggregates:** Model aggregate roots explicitly for Spring Data JDBC; prefer immutable data classes/records where practical and avoid lazy-loading assumptions.
- **Null Safety:** Leverage Kotlin's null-safety (`?`) to clearly define optional or required fields at the type level.
- **Spring Data JDBC:** Use Spring Data JDBC repositories by extending `CrudRepository` or custom ports/adapters when hexagonal boundaries require them.

## Logging

- **Companion Object Logger:** The idiomatic way to declare a logger is in a companion object.
  ```kotlin
  companion object {
      private val logger = LoggerFactory.getLogger(MyClass::class.java)
  }
  ```
- **Parameterized Logging:** Use parameterized messages (`logger.info("Processing user {}...", userId)`) for performance and clarity.

## Testing

- **JUnit 5:** JUnit 5 is the default and works seamlessly with Kotlin.
- **Idiomatic Testing Libraries:** For more fluent and idiomatic tests, consider using **Kotest** for assertions and **MockK** for mocking. They are designed for Kotlin and offer a more expressive syntax.
- **Test Slices:** Use test slice annotations like `@WebMvcTest` or `@JdbcTest` to test specific parts of the application.
- **Testcontainers:** Use Testcontainers for reliable integration tests with real databases, message brokers, etc.

## Background and Asynchronous Work

- **Durable jobs:** Use JobRunr for email, SMS, reports, imports, exports, retries, scheduled jobs, and long-running operational work.
- **Application events:** Use Spring Modulith events and Namastack Outbox for selected externalization; do not add non-MVC web APIs.
