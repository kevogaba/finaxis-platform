# Web Layer - Spring Web MVC

Finaxis uses Spring Boot Web MVC, Springdoc, Bean Validation, DTOs at API boundaries, centralized API exception handling, and versioned public APIs.

## Required API Patterns

- Version public endpoints under `/api/v1`, `/api/v2`, etc.
- Use DTOs for request and response bodies.
- Use Bean Validation on request DTOs.
- Use pagination for all listing endpoints.
- Never return unbounded collections from listing endpoints.
- Preserve `X-Request-Id` correlation and avoid logging secrets or sensitive PII.
- Keep controllers thin: validate, authorize endpoint access, delegate to application services.

## Controller Sketch

```kotlin
@RestController
@RequestMapping("/api/v1/products")
class ProductController(private val service: ProductService) {
    @GetMapping
    @PreAuthorize("hasAuthority('product.read')")
    fun list(pageable: Pageable): Page<ProductResponse> = service.list(pageable)
}
```
