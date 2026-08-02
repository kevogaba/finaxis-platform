# Multi-Tenant Identity Authorization Implementation Plan

> **Archived historical artifact.** This records work that has since shipped. It is kept for
> the design rationale it contains, not as a description of current behaviour. For current
> state see the ADRs in `docs/adr/` and the reference docs under `docs/architecture/`,
> `docs/security/`, `docs/operations/`, and `docs/database/`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build app-owned multi-tenant IAM and permission authorization for a Spring Boot Web MVC Keycloak resource server.

**Architecture:** Add a Kotlin-only `iam` module with Flyway schema, Spring Data JDBC repositories, effective permission resolution, method-security authorities, active organisation context tokens, and service-level resource authorization. Roles remain permission bundles only; runtime decisions use permission codes.

**Tech Stack:** Kotlin 2.3.21, Java 25, Spring Boot 4.1, Spring Security 7 servlet resource server, Spring Data JDBC, Flyway, Spring Cache, JUnit 5, MockMvc, JaCoCo.

---

### Task 1: Project Guidance And Build Support

**Files:**
- Create: `AGENTS.md`
- Create: `CLAUDE.md`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yaml`

- [ ] Add local agent guidance requiring simple, permission-based IAM changes and explicit complexity review.
- [ ] Add JaCoCo coverage verification for `com.finaxis.platform.iam`.
- [ ] Enable Spring cache and virtual threads through configuration.
- [ ] Run `./gradlew test` and expect existing compile failures only where tests reference not-yet-created IAM code.

### Task 2: Schema And Domain Model

**Files:**
- Create: `src/main/resources/db/migration/V1__create_iam_schema.sql`
- Create: `src/main/kotlin/com/finaxis/platform/iam/domain/IamEnums.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/domain/IamEntities.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/domain/Audit.kt`

- [ ] Write schema for app users, organisations, memberships, permissions, roles, role permissions, membership roles, membership permissions, and placeholder branch/warehouse scope tables.
- [ ] Create Spring Data JDBC aggregate records and enums.
- [ ] Run focused compile and fix mapping issues.

### Task 3: Repositories And Test Fixtures

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/iam/persistence/IamRepositories.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/IamTestFixtures.kt`

- [ ] Add repositories with explicit query methods for effective permission lookup and active membership lookup.
- [ ] Add test fixtures that create users, organisations, memberships, permissions, roles, and grants.
- [ ] Run repository-backed tests after the resolver tests are introduced.

### Task 4: Permission Resolver And Cache Invalidation

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/iam/security/EffectivePermissionResolver.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/security/EffectivePermissionResolverTests.kt`

- [ ] Write failing tests for one organisation permissions, two organisation differences, DENY precedence, suspended membership, and cache invalidation.
- [ ] Implement resolver and mutation services that evict cache entries.
- [ ] Run resolver tests to green.

### Task 5: Principal, Current User, And Authorization Service

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/iam/security/AppPrincipal.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/security/CurrentUser.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/security/AuthorizationService.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/security/AuthorizationServiceTests.kt`

- [ ] Write failing tests for permission checks and cross-organisation resource blocking.
- [ ] Implement `AppPrincipal`, `ResourceRef`, current user provider, and `AuthorizationService`.
- [ ] Run authorization tests to green.

### Task 6: Active Organisation Flow

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/iam/security/ActiveOrganisationContext.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/web/AuthController.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/web/AuthControllerTests.kt`

- [ ] Write failing tests for successful selection and rejection when no ACTIVE membership exists.
- [ ] Implement HMAC-signed active organisation context tokens and controller endpoint.
- [ ] Run web tests to green.

### Task 7: Spring Security Integration

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/iam/security/SecurityConfiguration.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/web/MethodSecurityTests.kt`

- [ ] Write failing MockMvc test proving `@PreAuthorize` rejects missing permissions and accepts role-derived permissions exposed as authorities.
- [ ] Implement servlet `Jwt` authentication converter, method security, and filter chain.
- [ ] Run security tests to green.

### Task 8: Verification And Squash

**Files:**
- Review all IAM files and docs.

- [ ] Run `./gradlew test jacocoTestCoverageVerification`.
- [ ] Run `./gradlew bootJar` to exercise AOT-compatible configuration without native image build.
- [ ] Review for role-name authorization, WebFlux imports, permanent active organisation fields, and unnecessary complexity.
- [ ] Squash local work into a single commit.
