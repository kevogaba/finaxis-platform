# Data Access - Spring Data JDBC and jOOQ

Finaxis uses Flyway-managed PostgreSQL schemas, Spring Data JDBC for aggregate persistence, and jOOQ for type-safe SQL where repositories are not expressive enough.

## Spring Data JDBC Aggregate Pattern

```kotlin
@Table("products")
data class Product(
    @Id val id: UUID?,
    val name: String,
    val price: BigDecimal,
)

interface ProductRepository : CrudRepository<Product, UUID> {
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<Product>
}
```

## Hexagonal Boundary

- Domain/application code depends on ports.
- JDBC repositories and jOOQ queries live in outbound adapters.
- Keep transactions on application services for multi-step use cases.
- Prefer explicit SQL and projections for reporting or cross-aggregate reads.
- Avoid implicit ORM relationship assumptions; model aggregates deliberately.

## Flyway

- All schema changes go through `src/main/resources/db/migration`.
- Tests should exercise Flyway-managed schema with Testcontainers PostgreSQL.
