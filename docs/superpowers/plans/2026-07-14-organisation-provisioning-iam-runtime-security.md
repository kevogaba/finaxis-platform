# Organisation Provisioning, IAM, and Runtime Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver organisation/branch lifecycle, permission-based IAM, durable user provisioning,
and Keycloak-authenticated runtime authorization on the existing foundation.

**Architecture:** Extend `lifecycle` with organisation-first application services and FSM graphs;
extend `iam` with role mutation and runtime resolution ports; use externalised lifecycle events plus
Namastack Outbox for Keycloak and invitation dispatch. Keep all persistence behind lifecycle/IAM
outbound adapters and all Keycloak calls behind an outbound port.

**Tech Stack:** Kotlin, Spring Boot, Spring Modulith, Spring Security, Flyway, jOOQ, Testcontainers,
Namastack Outbox, RabbitMQ, JobRunr, Keycloak Admin REST API.

## Global Constraints

- Use `organisation`, never a second tenant aggregate or table; retain `tenant_code` as data.
- All mutations write audit events and lifecycle transitions use `TransitionExecutor`.
- External events originate only in `TransitionDefinition.eventFactories` and are routed through
  the existing Namastack outbox pattern.
- Controllers may use permission authorities for coarse checks; services enforce scoped checks.
- No application passwords or distributed Keycloak transaction.
- Every public class/function includes KDoc and Kotlin formatting/static analysis stays clean.

---

### Task 1: Schema and Organisation/Branch Lifecycle Contracts

**Files:**
- Modify: `src/main/resources/db/migration/V1__create_iam_schema.sql`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/domain/FoundationLifecycleDefinitions.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationProvisioningService.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/BranchLifecycleService.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/application/OrganisationProvisioningServiceTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/application/BranchLifecycleServiceTests.kt`

**Interfaces:** expose commands for create, submit, approve, reject, suspend, reactivate,
deprovision, branch assignment and revocation; return IDs/statuses and page-based query records.

- [ ] Write focused failing FSM tests for valid transitions, metadata/tenant guards, setup failure
  rollback, branch ownership, inactive branch/organisation, duplicate assignment, and final
  assignment revocation guard.
- [ ] Implement minimal organisation/branch command records, transition graphs, persistence port
  methods, and transactional services using the common transition executor.
- [ ] Add migration constraints/tables for setup defaults, reference sequences and dispatch logs
  only where the current schema lacks them; retain all existing audit columns.
- [ ] Add explicit externalised events for approval, activation, rejection, suspension, branch
  activation, and assignment. Add tests that inspect emitted `ExternalizedTransitionEvent` targets.
- [ ] Run the two focused suites and the lifecycle persistence integration suite.

### Task 2: Permission Catalogue and Role/Assignment Management

**Files:**
- Modify: `src/main/resources/db/migration/V2__seed_local_iam_smoke_data.sql`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/role/RoleManagementService.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/role/RoleCommands.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/port/outbound/IamAdministrationPersistence.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/persistence/JooqIamAdministrationPersistence.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/application/role/RoleManagementServiceTests.kt`

**Interfaces:** provide create/update/activate/deactivate role; grant/remove permission; assign/revoke
role; `hasPermission(userId, organisationId, ...)` and branch-aware equivalents.

- [ ] Write failing tests for unique role codes, critical-permission auditing, cross-organisation
  mutation denial, inactive organisation denial, active branch prerequisite, and duplicate active
  role assignments.
- [ ] Seed the exact permission codes and risk/module metadata through Flyway, then implement the
  services and jOOQ port with organisation predicates on every query/mutation.
- [ ] Extend `AuthorizationService` with user/organisation/branch overloads and request-scoped
  permission lookup. Keep existing principal APIs compatible.
- [ ] Run role, authorization, resolver, and persistence adapter tests.

### Task 3: Local User Provisioning and Durable Keycloak Coordination

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/provisioning/UserProvisioningService.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/provisioning/UserProvisioningCommands.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/port/outbound/IdentityProvisioningGateway.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/keycloak/KeycloakAdminGateway.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/messaging/IdentityProvisioningListener.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/application/provisioning/UserProvisioningServiceTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/adapter/outbound/keycloak/KeycloakAdminGatewayTests.kt`

- [ ] Write failing tests for a new user, second organisation invitation, inactive organisation,
  missing branch/role, retry-safe external provisioning, duplicate invitation dispatch, and
  pre-approval login rejection.
- [ ] Implement local draft/reuse, pending membership/assignment staging, approval guards, and
  lifecycle events. Use deterministic dispatch keys and persist an external dispatch outcome.
- [ ] Implement the Keycloak port with find-before-create and required-actions-email semantics;
  listener delegates to a JobRunr handler and leaves retryable failures to broker/job retry.
- [ ] Implement suspend/reactivate/deactivate/revoke membership cascades and their audit/events.
- [ ] Run provisioning/unit and Testcontainers-backed lifecycle integration tests.

### Task 4: Runtime Principal and Organisation/Branch Context Enforcement

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/security/SecurityConfiguration.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/security/RuntimeAccessResolver.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/security/RequestPermissionCache.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/security/CurrentUser.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/context/RequestContexts.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/application/security/RuntimeAccessResolverTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/MethodSecurityTests.kt`

- [ ] Write failing tests for every critical precheck: suspended app user, suspended organisation,
  no active membership, no branch assignment, cross-organisation branch, inactive branch, and
  missing concrete permission.
- [ ] Resolve Keycloak subject strictly through the local identity link/pending invitation rule,
  then resolve active organisation and branch through explicit selection contexts.
- [ ] Add tenant- and branch-aware `@authz.hasPermission(...)` method security support and put
  organisation, branch, user, and correlation ID into MDC/request context.
- [ ] Run method-security, auth-flow, security-adapter, and new runtime resolver tests.

### Task 5: Documentation, ADRs, and Complete Verification

**Files:**
- Create: `docs/operations/tenant-provisioning.md`
- Create: `docs/operations/branch-provisioning.md`
- Create: `docs/security/authorization-model.md`
- Create: `docs/security/user-provisioning-keycloak.md`
- Create: `docs/security/login-prechecks.md`
- Create: `docs/adr/0005-organisation-lifecycle-no-hard-delete.md`
- Create: `docs/adr/0006-keycloak-outbox-coordination.md`

- [ ] Document each lifecycle, setup default, retention/export/anonymisation follow-up, and
  operational recovery rule.
- [ ] Document the permission model and add Mermaid diagrams for membership/branch/role/permission
  and Keycloak-to-runtime access resolution.
- [ ] Run `./gradlew test`, `./gradlew qualityGate`, and the local smoke script when services are
  available. Fix all introduced findings without suppressions.
- [ ] Recheck every acceptance criterion against code, tests, docs, and command output.
- [ ] Squash the feature branch changes into one detailed conventional commit.
