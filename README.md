# Finaxis Platform

Finaxis Platform is a Kotlin-first Spring Boot OAuth2 resource server for a greenfield SACCO
and core-banking modular monolith. It uses hexagonal architecture and Spring Modulith.

## Architecture and modules

| Module | Responsibility | Current status |
| --- | --- | --- |
| `iam` | Identity, authorization, and active-organisation context | The only REST surface today. |
| `lifecycle` | FSM services for organisation, branch, user, and membership | No inbound adapter. |
| `notifications` | RabbitMQ listener and JobRunr welcome-email stub | Reference event pipeline. |
| `common` | Transitions, audit, context, persistence, and web infrastructure | Shared interfaces. |
| `config` | Application configuration | Supporting configuration package. |

Authentication is provided by Keycloak; application permissions are the runtime authorization
source of truth.

## Tech stack

| Technology | Version or management |
| --- | --- |
| Kotlin | 2.4.0 |
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
| PostgreSQL | `postgres:18.4` | `5433:5432` | Initializes `platform` and `keycloak`. |
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
./gradlew bootRun
./scripts/local-smoke.sh
```

The application listens on <http://localhost:8081>. The local smoke script obtains a development
Keycloak token, selects an organisation and branch, then calls the profile endpoint.

JobRunr's local dashboard is available at <http://localhost:8000/dashboard>. It is disabled in
tests and production.

## Reference event pipeline

Membership activation is the pattern for future domain events: membership activation → Modulith
event → Namastack outbox → RabbitMQ → notifications listener → JobRunr welcome-email stub.

## API

Public endpoints are versioned under `/api/v1`. The current endpoints are:

- `POST /api/v1/auth/select-organisation`
- `POST /api/v1/auth/select-branch`
- `GET /api/v1/auth/me`

In local development, Scalar API documentation is available at `/scalar` and OpenAPI JSON at
`/v3/api-docs`. Both are disabled in the production profile.

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

- `lifecycle` has no inbound adapter yet; transitions are currently internal or test-driven.
- The welcome-email provider integration is pending; the current JobRunr handler is a stub.
- `iam` and `config` do not yet declare explicit `@ApplicationModule` package metadata.
- JaCoCo's enforced coverage rule is scoped to `iam` only.

## Documentation index

| Area | Document |
| --- | --- |
| Architecture | [FSM transitions](docs/architecture/fsm-transitions.md) |
| Architecture | [Foundation plan](docs/architecture/foundation-implementation-plan.md) |
| Architecture | [Lifecycle FSM](docs/architecture/lifecycle-fsm.md) |
| Architecture | [API governance](docs/architecture/api-governance.md) |
| Architecture | [API versioning](docs/architecture/api-versioning.md) |
| Architecture | [JDBC auditing and context](docs/architecture/jdbc-auditing-and-context.md) |
| Architecture | [Rate limiting](docs/architecture/rate-limiting.md) |
| Architecture | [Audit logging](docs/architecture/audit-logging.md) |
| Decisions | [ADR 0001](docs/adr/0001-active-organisation-context-transport.md) |
| Decisions | [ADR 0002](docs/adr/0002-fsm-transition-infrastructure.md) |
| Decisions | [ADR 0003](docs/adr/0003-api-governance-rate-limiting-versioning-pagination.md) |
| Decisions | [ADR 0004](docs/adr/0004-membership-activation-notification-pipeline.md) |
| Database | [Foundation schema](docs/database/foundation-schema.md) |
| Security | [Active organisation context](docs/security/active-organisation-context.md) |
| Security | [Production hardening](docs/security/production-hardening.md) |
| Development | [Project context](docs/development/common-project-context.md) |
| Development | [Local development](docs/development/local-development.md) |
| Development | [Static analysis](docs/development/static-analysis.md) |
| Audits | [Repository health audit](docs/audits/repository-health-audit.md) |
