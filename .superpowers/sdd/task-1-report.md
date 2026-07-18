# Task 1 report: shared REST contract

## Implementation summary

- Added the `common.web.api` named interface with MVC-only Jackson 3 configuration:
  snake-case JSON, strict `dd-MM-yyyy` dates, `HH:mm:ss` times, and explicit-offset datetimes.
  The existing Jackson 2 persistence/Namastack mapper was not changed.
- Added bounded `ApiPage` / `ApiPageMetadata` conversion with zero-based pages and a strict
  size range of 1 through 100.
- Added transport-neutral, safely coded application errors exposed through the
  `common::application` named interface.
- Replaced the IAM-local error DTO/handler with central RFC 9457 `ApiProblem` mapping,
  including request ID, safe type/code/detail, bounded validation violations, framework JSON,
  media-type, and Spring Security access-denied mappings.
- Migrated IAM OpenAPI error schemas and JSON integration expectations to the shared contract.

## Files changed

- Added `src/main/kotlin/com/finaxis/platform/common/application/ApplicationErrors.kt`
- Added `src/main/java/com/finaxis/platform/common/application/package-info.java`
- Added `src/main/kotlin/com/finaxis/platform/common/web/api/*`
- Added `src/main/java/com/finaxis/platform/common/web/api/package-info.java`
- Deleted `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/ApiExceptionHandler.kt`
- Updated IAM controllers, safe IAM denial types, Jackson configuration, and affected web tests.

## TDD evidence

### RED

Command:

```text
./gradlew test --tests '*WebJsonContractTests'
```

Result: failed as expected during test compilation because `WebJsonConfiguration`, `ApiPage`,
and the common API problem types did not yet exist.

### GREEN

Commands:

```text
./gradlew test --tests '*WebJsonContractTests'
./gradlew test --tests '*WebJsonContractTests' --tests '*ApiExceptionHandlerTests' --tests '*WebAdapterUnitTests'
```

Relevant result: both commands completed `BUILD SUCCESSFUL`; the focused suite covers strict
format serialization/deserialization, page bounds, RFC 9457 mappings, safe disclosure, and
the migrated IAM adapter behavior.

## Final verification

- `./gradlew spotlessApply` — passed.
- `./gradlew test --tests '*AuthFlowIntegrationTests' --tests '*MethodSecurityTests' --tests '*WebJsonContractTests' --tests '*ApiExceptionHandlerTests' --tests '*WebAdapterUnitTests' --tests '*PlatformApplicationTests'` — passed (36 tests).
- `./gradlew test` was run twice during the task. The last full run before the final narrow
  test adjustments reported the existing `ApiVersioningArchitectureTest > documentation does not
  introduce unversioned api examples()` false positive against tracked `docs/superpowers` plan/spec
  file paths, plus a flaky `MembershipActivationPipelineIntegrationTests` JobRunr timeout.
  The final focused verification above passed after the last code/test adjustments; a clean
  final-tree full-suite result is therefore not claimed.

## Self-review

- Safe disclosure: no raw exception text, rejected values, permissions, SQL, or parser details
  are returned in problems; only server logs retain unexpected exceptions.
- Module boundaries: `common.application` is explicitly exposed as `common::application`, so IAM
  can use the safe errors without violating Spring Modulith boundaries.
- Mapper isolation: the customizer targets Spring Boot's Jackson 3 MVC mapper only.
- Tests exercise contract behavior rather than private implementation details; MVC integration
  tests now send and assert snake-case JSON.

## Concerns

- The versioning documentation architecture test has a known false positive on Java/Kotlin source
  paths containing `/api/` inside the tracked Task 12 plan/spec documentation. Its correction is
  deferred to Task 12 as directed.
- The full-suite membership activation timeout occurred independently of this task and was not
  changed. Focused Task 1, IAM flow/security, and application-context tests pass.

## Review-fix pass (2026-07-18)

### TDD evidence

Added the review regression tests first, then ran:

```text
./gradlew test --tests '*ApiExceptionHandlerTests' --tests '*ApiProblemWriterTests' --tests '*HttpAccessLogFilterTests' --tests '*WebJsonContractTests' --tests '*ApiVersioningArchitectureTest'
```

The first run failed at compilation as expected because the new writer, correlated request-id
attribute, missing-parameter/resource mappings, and pagination exception did not yet exist.
After implementation, the expanded focused suite including `SecurityAdapterTests` and
`PaginationConfigurationTests` passed.

### Fixes delivered

- Pagination defaults are now 25 with a hard maximum of 100 in properties and YAML. Invalid
  `page`/`size` input is rejected before Spring can clamp it, and the common page mapper raises a
  handler-mapped 400 rather than `require` producing a 500.
- Active-tenant filter failures now use `ApiProblemWriter`, returning correlated
  `application/problem+json` with stable `invalid_active_tenant_context` and safe detail.
- Request IDs are stored on the servlet request. The access logger, filter writer, response
  header, and problem body therefore share a generated or caller-provided ID.
- MVC now maps omitted parameters, method validation, and missing resources to safe 4xx problems;
  validation field names are converted to public snake_case (including `org_id`).
- IAM OpenAPI error content explicitly declares `application/problem+json`.
- The documentation versioning scanner only examines endpoint-like `/api/...` examples and ignores
  internal source/documentation paths containing an `/api/` segment.

### Final review-fix verification

The final expanded focused command was:

```text
./gradlew test --tests '*PaginationConfigurationTests' --tests '*ApiExceptionHandlerTests' --tests '*ApiProblemWriterTests' --tests '*HttpAccessLogFilterTests' --tests '*WebJsonContractTests' --tests '*ApiVersioningArchitectureTest' --tests '*SecurityAdapterTests'
```

Result: `BUILD SUCCESSFUL`; 42 tests passed (zero failures and zero errors).

- `./gradlew spotlessApply` — passed after review fixes.
- A final `./gradlew test` was attempted, but its detached Gradle worker disappeared before an
  exit status or updated report was available. A final-tree full-suite green result is therefore
  not claimed.
- `MembershipActivationPipelineIntegrationTests` remained unverified in that full-suite attempt.

## Controller-run follow-up (2026-07-18)

The authoritative controller run used `./gradlew --no-daemon test` and completed with 338 tests
and 7 failures. Six failures were Task 1 test regressions: all five `MethodSecurityTests` cases
could not load the Web MVC slice after `ActiveOrganisationContextFilter` gained its required
`ApiProblemWriter` dependency, and `AuthFlowIntegrationTests` still expected the obsolete public
violation field `organisationId`. The remaining failure was the independent
`MembershipActivationPipelineIntegrationTests` timeout after one minute, caused by JobRunr
`JobNotFound` behavior.

The two Task 1 test corrections were verified with:

```text
./gradlew test --tests '*MethodSecurityTests' --tests '*AuthFlowIntegrationTests' --tests '*MembershipActivationPipelineIntegrationTests'
```

Results: `MethodSecurityTests` passed 5/5 and `AuthFlowIntegrationTests` passed 15/15.
`MembershipActivationPipelineIntegrationTests` was rerun unchanged and failed again after
85.564 seconds with `org.awaitility.core.ConditionTimeoutException` (the awaited condition did
not complete within one minute). No membership production code was changed.

`./gradlew spotlessApply` passed before this focused run.
