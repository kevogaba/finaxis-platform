# Finaxis Platform

Finaxis Platform is a Kotlin-first Spring Boot OAuth2 resource server for a greenfield SACCO
and core-banking modular monolith. It uses hexagonal architecture and Spring Modulith.

## Architecture and modules

| Module | Responsibility | Current status |
| --- | --- | --- |
| `iam` | Identity, authorization, and tenant context | REST: auth, users, roles, permissions. |
| `lifecycle` | Organisation, branch, setup FSMs | REST: tenants, branches, dates, audit. |
| `notifications` | RabbitMQ listener and JobRunr welcome-email stub | Reference event pipeline. |
| `common` | Transitions, audit, context, persistence, and web infrastructure | Shared interfaces. |
| `config` | Application configuration | Infrastructure wiring, not a domain module. |

Authentication is provided by Keycloak; application permissions are the runtime authorization
source of truth.

## Tech stack

| Technology | Version or management |
| --- | --- |
| Kotlin | 2.4.10 |
| Spring Boot | 4.1.0 |
| Java toolchain | 25 |
| jOOQ | 3.21.6 |
| Spring Modulith | 2.1.0 |
| Namastack Outbox | 1.7.1 |
| JobRunr | 8.7.1 (`jobrunr-spring-boot-4-starter`) |
| Bucket4j | 8.19.0 |
| springdoc | 3.0.3 |
| Spring Cloud | 2025.1.2 |
| Flyway and Testcontainers | Spring Boot BOM-managed |
| Spotless | 8.8.0 |
| ktlint | 1.8.0 |
| Detekt | 2.0.0-alpha.5 |
| Checkstyle | 13.7.0 |
| PMD | 7.25.0 |
| SpotBugs tool | 4.10.2 |
| Error Prone core | 2.50.0 |
| ArchUnit | 1.4.2 |
| JaCoCo | 0.8.14 |

## Prerequisites

- JDK 25. The checked-in `.sdkmanrc` selects `25.0.2-graalce`.
- Docker, including Docker Compose.

## Local infrastructure

| Service | Image | Host ports | Notes |
| --- | --- | --- | --- |
| PostgreSQL | `postgres:18.4` | `5433:5432` | `platform` (from `POSTGRES_DB`) and `keycloak`. |
| RabbitMQ | `rabbitmq:4.3.2-management` | `5673:5672`, `15672:15672` | AMQP and management UI. |
| Redis | `redis:8.8.0` | `6379:6379` | Local standalone Redis. |
| Keycloak | `quay.io/keycloak/keycloak:26.6.4` | `8080:8080`, `9000:9000` | `finaxis` realm. |
| Grafana LGTM | `grafana/otel-lgtm:0.28.0` | `3000:3000`, `4318:4318` | Grafana, OTLP HTTP. |

The local infrastructure username and password are both `finaxis` where configured. Keycloak
uses the development administrator credentials `admin` / `admin`, and the imported `finaxis`
realm includes `local.admin` / `local-admin` for the smoke path.

## Getting started

```bash
docker compose up -d postgres redis rabbitmq keycloak
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
./scripts/local-smoke.sh
```

The three Flyway migrations create the schema, seed the permission catalogue, and seed a bootstrap
tenant with a first administrator (`local.admin`) already linked to the Keycloak realm, so the
smoke path works against an empty database with no manual setup.

If you have a database from before the greenfield migration reset, Flyway will refuse to start with
a checksum mismatch. Wipe it:

```bash
docker compose down -v && docker compose up -d postgres redis rabbitmq keycloak
```

The application listens on <http://localhost:8081>. The local smoke script obtains a development
Keycloak token, selects an organisation and branch, then calls the profile endpoint.

JobRunr's local dashboard is available at <http://localhost:8000/dashboard>. It is disabled in
tests and production.

## Database and identifiers

The schema is three Flyway migrations: `V1__foundation_schema.sql` (all 23 tables),
`V2__platform_reference_data.sql` (54-code permission catalogue, `PLATFORM` organisation and
roles), and `V3__bootstrap_tenant_and_administrator.sql` (bootstrap tenant and first
administrator). Every future change is forward-only.

Every table carries a unique `guid`, a client-suppliable alternate key defaulting to `uuidv7()`
when omitted. Every table that has an `id` uses `id UUID PRIMARY KEY DEFAULT uuidv7()` — generated
by the application (in practice by the database, read back with `RETURNING`) and never supplied by
a client. Two tables have no `id` at all: `api_idempotency_record` keys on
`(scope_organisation_id, idempotency_key)` and `organisation_initial_administrator_bootstrap` keys
on `organisation_id`, so do not expect a `TABLE.ID` field for either. See
[Foundation schema](docs/database/foundation-schema.md),
[ADR 0010](docs/adr/0010-greenfield-migration-reset-and-schema-rewrite.md), and
[ADR 0015](docs/adr/0015-client-suppliable-guid-alternate-key.md).

## Reference event pipeline

Membership activation is the pattern for future domain events: membership activation → Modulith
event → Namastack outbox → RabbitMQ → notifications listener → JobRunr welcome-email stub.

## API

Public endpoints are versioned under `/api/v1`. The implemented foundation REST surface covers
auth/profile selection, platform tenant administration, tenant branches, tenant users,
memberships, branch assignments, roles, role assignments, permissions, audit events, business
date, and tenant settings. The canonical contract reference is
[Foundation REST API](docs/api/foundation-api.md).

In local development, Scalar API documentation is available at `/scalar` and OpenAPI JSON at
`/v3/api-docs`. Both are disabled in the production profile.

Foundation REST rules:

- Inbound adapters stay thin and delegate to module-owned application services.
- Queries are bounded and tenant-filtered; every collection endpoint returns the standard
  paginated envelope.
- Every endpoint has an explicit application-layer permission check. Organisation/branch
  selection and tenant settings are intentional service-enforced exceptions.
- Every mutation is idempotent with optional/generated UUID `Idempotency-Key` handling.
- Tenant and branch context are enforced before tenant data is returned or mutated.
- Public JSON uses `snake_case`; business dates use `dd-MM-yyyy`, times use `HH:mm:ss`, and
  datetimes use ISO-8601 offset format.

## Security profiles

The base configuration is for local development. Activate production settings with
`SPRING_PROFILES_ACTIVE=production`. Production enables secure, strict session cookies, explicit
CORS origins, HSTS and CSP, disables development tooling and API documentation, and requires the
active-organisation HMAC secret without a default. See
[production hardening](docs/security/production-hardening.md).

## Testing

The project maintains unit tests and Spring integration tests. Integration coverage uses
Testcontainers for PostgreSQL, Redis, RabbitMQ, and Grafana LGTM infrastructure boundaries.

## Quality gates

Run the complete local gate with:

```bash
./gradlew qualityGate
```

It runs static analysis, checks, IAM-scoped JaCoCo verification, and `bootJar`. CI also runs a
Qodana job in `.github/workflows/static-analysis-and-tests.yml`.

## Project status and follow-ups

- The welcome-email provider integration is pending; the current JobRunr handler is a stub.
- `config` intentionally does not declare `@ApplicationModule`; it is infrastructure wiring
  rather than a domain module.
- JaCoCo's enforced coverage rule is scoped to `iam` only.

## Documentation index

| Area | Document |
| --- | --- |
| API | [Foundation REST API](docs/api/foundation-api.md) |
| Architecture | [API governance](docs/architecture/api-governance.md) |
| Architecture | [API versioning](docs/architecture/api-versioning.md) |
| Architecture | [Audit logging](docs/architecture/audit-logging.md) |
| Architecture | [BIAN service landscape](docs/architecture/bian-service-landscape.md) |
| Architecture | [Email delivery](docs/architecture/email-delivery.md) |
| Architecture | [Financial transaction atomicity](docs/architecture/financial-transaction-atomicity.md) |
| Architecture | [Foundation plan](docs/architecture/foundation-implementation-plan.md) |
| Architecture | [FSM transitions](docs/architecture/fsm-transitions.md) |
| Architecture | [JDBC auditing and context](docs/architecture/jdbc-auditing-and-context.md) |
| Architecture | [Lifecycle FSM](docs/architecture/lifecycle-fsm.md) |
| Architecture | [Rate limiting](docs/architecture/rate-limiting.md) |
| Architecture | [Transactional outbox and AMQP](docs/architecture/transactional-outbox-amqp.md) |
| Database | [Accounting schema](docs/database/accounting-erd.md) |
| Database | [Foundation schema](docs/database/foundation-schema.md) |
| Operations | [Branch provisioning](docs/operations/branch-provisioning.md) |
| Operations | [Business date and COB](docs/operations/business-date.md) |
| Operations | [Tenant provisioning](docs/operations/tenant-provisioning.md) |
| Operations | [Tenant settings](docs/operations/tenant-settings.md) |
| Security | [Active organisation context](docs/security/active-organisation-context.md) |
| Security | [Audit logging](docs/security/audit-logging.md) |
| Security | [Authorization model](docs/security/authorization-model.md) |
| Security | [Login pre-checks](docs/security/login-prechecks.md) |
| Security | [Production hardening](docs/security/production-hardening.md) |
| Security | [User provisioning and Keycloak](docs/security/user-provisioning-keycloak.md) |
| Development | [Project context](docs/development/common-project-context.md) |
| Development | [Local development](docs/development/local-development.md) |
| Development | [Static analysis](docs/development/static-analysis.md) |

## Decision records

| ADR | Decision |
| --- | --- |
| [0001](docs/adr/0001-active-organisation-context-transport.md) | Active organisation context transport |
| [0002](docs/adr/0002-fsm-transition-infrastructure.md) | FSM transition infrastructure |
| [0003](docs/adr/0003-api-governance-rate-limiting-versioning-pagination.md) | API governance, rate limiting, versioning, pagination |
| [0004](docs/adr/0004-membership-activation-notification-pipeline.md) | Membership activation notification pipeline |
| [0005](docs/adr/0005-organisation-lifecycle-no-hard-delete.md) | Metadata-only deprovisioning, no hard delete |
| [0006](docs/adr/0006-keycloak-outbox-coordination.md) | Keycloak coordination via outbox and idempotent jobs |
| [0007](docs/adr/0007-append-only-audit-log-and-redaction-policy.md) | Append-only audit log with structural redaction |
| [0008](docs/adr/0008-transactional-outbox-over-direct-amqp.md) | Namastack-only transactional outbox |
| [0009](docs/adr/0009-tenant-settings-scope-and-authn-boundary.md) | Tenant settings scope and authentication boundary |
| [0010](docs/adr/0010-greenfield-migration-reset-and-schema-rewrite.md) | Greenfield migration reset and schema rewrite |
| [0011](docs/adr/0011-global-user-with-tenant-membership.md) | Global user account with tenant membership |
| [0012](docs/adr/0012-keycloak-authentication-application-authorization.md) | Keycloak authenticates, the application authorizes |
| [0013](docs/adr/0013-foundation-lifecycle-state-machines.md) | Explicit state machines for tenant, branch, user, membership |
| [0014](docs/adr/0014-spring-data-jdbc-auditing.md) | Spring Data JDBC auditing alongside jOOQ write paths |
| [0015](docs/adr/0015-client-suppliable-guid-alternate-key.md) | Application-owned UUIDv7 keys and a client-suppliable `guid` |
| [0016](docs/adr/0016-email-delivery-transport-and-retry-classification.md) | Email delivery transport and retry classification |
| [0017](docs/adr/0017-bian-semantic-reference-architecture.md) | BIAN as semantic reference architecture |
| [0018](docs/adr/0018-financial-transaction-atomicity-invariant.md) | Financial transaction atomicity invariant |
| [0019](docs/adr/0019-accounting-money-representation-and-rounding.md) | Accounting money representation and rounding |
| [0020](docs/adr/0020-immutable-ledger-and-reversal-only-correction.md) | Immutable ledger and reversal-only correction |

Historical planning, specification, and audit artifacts are archived under `docs/archive/`.
