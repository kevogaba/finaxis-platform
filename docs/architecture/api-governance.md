# API Governance

Finaxis public APIs are governed from the start because this service is expected to run as a
multi-instance production system and to grow across multiple modules.

## Baseline Rules

- Public API routes must live under an explicit path version such as `/api/v1`.
- Do not add unversioned public business routes outside the explicit version namespace.
- Listing endpoints must be paginated. Do not return unbounded domain collections from listings.
- Request DTOs must use Jakarta Bean Validation for required fields, sizes, and formats.
- Controllers should return DTOs, not persistence entities.
- API errors must go through centralized exception handling.
- Smoke scripts, docs, and tests must be updated whenever paths change.

## Pagination

Application defaults are configured under `finaxis.pagination`:

```yaml
finaxis:
  pagination:
    default-page-size: 25
    max-page-size: 100
```

These are the shipped defaults. `max-page-size` may be lowered but never raised above `100`:
`PaginationProperties` refuses to start with a larger value.

Listing endpoints should accept `Pageable` and return a bounded page/slice/envelope. Exceptions are
allowed only for small static reference values, metadata, health, and other explicitly documented
bounded values. The architecture test fails controller `GET` methods that return raw collections.

Invalid paging or sort input from a client is a 400, never a 500. The pagination interceptor
rejects a malformed or out-of-range `page` or `size` (any value of a repeated parameter), and the
query services reject an unknown `sort_by` or a `sort_dir` other than `ASC`/`DESC` through
`requireValidPage` and `requireValidSort`. Both throw `InvalidPageRequestException`, which
`ApiExceptionHandler` maps to a `400` `invalid_parameter` problem (with a `sort_by`/`sort_dir`
violation where applicable). Do not use `require()` on a client-supplied paging, sort or filter
value: an `IllegalArgumentException` has no mapping and surfaces as `internal_error`.

## Versioning

`/api/v1` is the baseline namespace. New versions should be introduced as new path namespaces such
as `/api/v2`, with compatibility rules documented in an ADR or migration note.

The project currently uses explicit path constants in `ApiPaths` because it makes the canonical
namespace obvious and keeps existing Springdoc and MockMvc tests simple. Spring Framework 7 native
API versioning remains available for future handler-level version negotiation if the route surface
becomes large enough to justify it.

## Error Shape

REST controllers use `ApiExceptionHandler` and return `ApiErrorResponse`. Do not leak stack traces,
internal class names, credentials, tokens, or persistence details in API responses. Unexpected
exceptions are logged server-side and returned as `internal_error`.

## Architecture Tests

The current guardrails include:

- controller mappings must use a versioned public base path;
- local smoke tests must call versioned paths;
- docs containing public API examples must use versioned paths;
- listing endpoints must not return raw collections.

Add more ArchUnit or reflection tests when introducing new adapters.
