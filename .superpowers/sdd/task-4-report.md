# Task 4 report — Production security profile

## Status

Completed on `feat/event-pipeline-security-docs`.

## Commit hashes

- `8255e9e4afae83cbb053dbfeb25c5dc726653fb8` — production security profile and tests.

## Design decisions

- `CorsProperties` and `SecurityHeadersProperties` are validated configuration properties in the
  IAM inbound-security adapter. The existing configuration-properties scan registers them.
- A `CorsConfigurationSource` bean registers no mapping when CORS is disabled; enabling CORS
  registers one `/**` mapping using only configured origins, methods, headers, and credentials.
- `X-Content-Type-Options`, `X-Frame-Options: DENY`, and the strict referrer policy are always
  emitted. HSTS is explicitly disabled outside production, while CSP is emitted only for a
  non-blank configured directive.
- CSRF remains disabled: every request is authenticated by its bearer JWT; Redis-backed HTTP
  sessions carry only active-organisation context, never authentication.
- The production secret uses an un-defaulted environment placeholder. An additional validation
  constraint rejects an unresolved placeholder, closing the gap where its literal text would
  otherwise satisfy the existing minimum-length validation.
- The test YAML imports main configuration once and has a `test` profile overlay only for the two
  JobRunr overrides. Two direct YAML-binding tests now load the canonical main YAML explicitly,
  preventing the test resource from reintroducing duplicate rate-limit, RabbitMQ, Modulith, and
  Namastack configuration.

## Files changed

- `src/main/resources/application-production.yaml`
- `src/main/resources/application.yaml`
- `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/security/SecurityProperties.kt`
- `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/security/SecurityConfiguration.kt`
- `src/main/kotlin/com/finaxis/platform/iam/application/context/ActiveOrganisationContext.kt`
- `src/test/resources/application.yaml`
- Security integration and production-profile tests under
  `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/security/`
- Canonical-YAML source updates in the rate-limit and transition configuration tests.

## Proof

```text
$ ./gradlew spotlessApply
> Task :spotlessJava UP-TO-DATE
> Task :spotlessJavaApply UP-TO-DATE
> Task :spotlessKotlin
> Task :spotlessKotlinApply
> Task :spotlessKotlinGradle UP-TO-DATE
> Task :spotlessKotlinGradleApply UP-TO-DATE
> Task :spotlessMisc UP-TO-DATE
> Task :spotlessMiscApply UP-TO-DATE
> Task :spotlessApply

BUILD SUCCESSFUL in 2s
```

```text
$ ./gradlew detekt
> Task :detekt

BUILD SUCCESSFUL in 2s
```

```text
$ ./gradlew test
> Task :test
> Task :jacocoTestReport

36 JUnit XML reports: 143 tests, 0 failures, 0 errors.
This includes HexagonalArchitectureTest and ModulithArchitectureTest.
```

Focused proof also passed for the new security headers, enabled/disabled CORS, and production
profile tests. The production test verified both a supplied secret and a startup failure when the
secret is absent.

## Concerns

None. jOOQ continues to emit its pre-existing ambiguous-key generation warning during Gradle
builds; it does not fail this task's checks.
