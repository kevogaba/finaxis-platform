# Repository Health Audit

Date: 2026-07-06

## Scope

Reviewed build health, security baseline, API governance, rate limiting, logging, audit trails,
observability, Spring Modulith boundaries, outbox/eventing, Docker/local development, CI readiness,
documentation, ADRs, and agent instructions.

## Commands Run

```bash
pwd
git status --short
find . -maxdepth 4 -type f | sort
find src -maxdepth 8 -type f | sort
rg -n "@(RestController|RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)" src
rg -n '"versioned api path"|"/auth/|/auth\\b' src scripts docs .github AGENTS.md CLAUDE.md HELP.md
rg -n "Pageable|Page<|Slice<|List<|Collection<" src/main src/test
rg -n "SecurityFilterChain|@PreAuthorize|@PostAuthorize|permitAll|csrf|cors" src
rg -n "password|secret|token|apiKey|api-key|apikey" . --glob '!build/**' --glob '!.git/**'
rg -n "TODO|FIXME|HACK|XXX|System\\.out|printStackTrace|MDC|Logger|log\\." src docs scripts
rg -n "Transactional|ApplicationModules|Externalized|Rabbit|JobRunr|Namastack|outbox" src test build.gradle.kts gradle/libs.versions.toml docs AGENTS.md CLAUDE.md
./gradlew test --tests 'com.finaxis.platform.common.web.*' --tests 'com.finaxis.platform.common.audit.*' --tests 'com.finaxis.platform.config.HttpAccessLogFilterTests' --tests 'com.finaxis.platform.iam.adapter.inbound.web.AuthFlowIntegrationTests'
./gradlew qualityGate
./scripts/local-smoke.sh
```

Context7 CLI was attempted for current documentation lookup but returned no usable output in this
environment. Official/reference web documentation was consulted for Spring Boot Actuator, Spring
Framework API versioning, Spring Security authorization, Spring Modulith verification, Bucket4j
Lettuce artifacts, Lettuce topology support, and OWASP API Security.

## Summary

The repository has a strong baseline for a young codebase: strict static analysis, Spring Modulith
verification, ArchUnit rules, Testcontainers, Flyway, centralized API error handling, local smoke
data, and CI quality gates. The main gaps were early API governance enforcement, distributed
rate-limit implementation, request correlation, audit foundation, and production-readiness
documentation.

## Findings

### F-001: Unversioned public auth API

Severity: High

Area: API governance

Description: Auth context endpoints were mounted under `/auth`.

Impact: Public API clients would depend on unversioned paths.

Status: Fixed

Files changed: `AuthController.kt`, `UserProfileController.kt`, tests, docs, `scripts/local-smoke.sh`.

### F-002: Missing distributed API rate limiting

Severity: High

Area: Security and abuse protection

Description: No production-grade distributed token-bucket limiter existed.

Impact: Multi-instance deployments could not enforce request quotas consistently.

Status: Fixed

Files changed: `common/web/ratelimit`, `application.yaml`, tests, docs.

### F-003: Redis topology support needed for production variability

Severity: Medium

Area: Operations

Description: Local development uses standalone Redis, but production may require Sentinel or
Cluster.

Impact: A standalone-only integration would constrain deployment architecture.

Status: Fixed

Files changed: `RateLimitConfiguration.kt`, `docs/architecture/rate-limiting.md`.

### F-004: Missing request correlation

Severity: Medium

Area: Logging and observability

Description: Access logs did not propagate `X-Request-Id`.

Impact: Request tracing across clients, logs, and errors was weaker.

Status: Fixed

Files changed: `HttpAccessLogFilter.kt`, `HttpAccessLogFilterTests.kt`.

### F-005: Missing audit foundation

Severity: Medium

Area: Audit trails

Description: No common audit service/port existed for security-sensitive actions.

Impact: Modules would likely scatter audit logging in adapters.

Status: Fixed

Files changed: `common/audit`, `docs/architecture/audit-logging.md`.

### F-006: Pagination rule not enforced

Severity: Medium

Area: API governance

Description: No architecture test enforced the no-unbounded-listing rule.

Impact: Future listing endpoints could return unbounded collections.

Status: Fixed

Files changed: `common/web/pagination`, `PaginationArchitectureTest.kt`, docs.

### F-007: Active context signing secret hardcoded as a literal

Severity: Medium

Area: Configuration hygiene

Description: The default active-context secret was a committed literal.

Impact: Operators could miss the need to provide a real secret.

Status: Fixed

Files changed: `application.yaml`, `ActiveOrganisationContext.kt`, tests.

### F-008: Actuator exposure includes metrics and Prometheus

Severity: Medium

Area: Operations and security

Description: Health, info, metrics, and Prometheus are exposed. Security still requires
authentication except health/docs, but production ingress must be explicit.

Impact: Misconfigured edge routing could expose operational metadata.

Status: Deferred

Recommended next action: Decide production management port/network policy and document ingress
rules before deployment.

### F-009: Durable audit persistence not implemented

Severity: Medium

Area: Audit trails

Description: The current audit repository logs events but does not persist them.

Impact: Logs may not satisfy compliance retention or query requirements.

Status: Deferred

Recommended next action: Add a Flyway-backed audit table and JDBC adapter when the first
admin/critical mutation workflows are introduced.

### F-010: CI security scanning baseline is limited

Severity: Low

Area: CI/CD readiness

Description: CI runs quality gates and Qodana, and Dependabot is configured. Dedicated dependency
vulnerability scanning is not yet configured as a blocking gate.

Impact: Vulnerability detection depends on Dependabot/Qodana rather than a local Gradle task.

Status: Deferred

Recommended next action: Evaluate OWASP Dependency Check, Dependency Track, or GitHub dependency
review once CI policy is finalized.

### F-011: Password grant appears in local smoke script

Severity: Informational

Area: Authentication

Description: `scripts/local-smoke.sh` uses Keycloak's token endpoint with password grant against
seeded local data.

Impact: This is acceptable for local smoke automation but must not be confused with application
password handling. The application remains an OAuth2 resource server.

Status: Deferred

Recommended next action: Replace with a non-password smoke credential flow if Keycloak/local CI
provides a better automation path.

## Verification Results

The focused governance/auth slice passed:

```bash
./gradlew test --tests 'com.finaxis.platform.common.web.*' --tests 'com.finaxis.platform.common.audit.*' --tests 'com.finaxis.platform.config.HttpAccessLogFilterTests' --tests 'com.finaxis.platform.iam.adapter.inbound.web.AuthFlowIntegrationTests'
```

The full local quality gate passed:

```bash
./gradlew qualityGate
```

The local smoke test passed after starting the application with `./gradlew bootRun`:

```bash
./scripts/local-smoke.sh
```

The smoke path exercised:

- `POST /api/v1/auth/select-organisation`
- `POST /api/v1/auth/select-branch`
- `GET /api/v1/auth/me`

## Next Hardening Tasks

- Add durable audit persistence when the first critical mutation APIs are introduced.
- Decide production actuator network exposure and management port policy.
- Add dependency vulnerability scanning as a CI gate if the team accepts the runtime cost.
- Add OpenAPI contract governance once the API surface grows beyond the auth context endpoints.
- Add Redis Sentinel and Redis Cluster integration tests if those topologies are used in CI.
