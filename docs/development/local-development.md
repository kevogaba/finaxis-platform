# Local Development

The local stack uses Docker Compose for Postgres, Redis, RabbitMQ, Grafana LGTM, and
Keycloak.

## Services

- Spring Boot application: `http://localhost:8081`
- Keycloak: `http://localhost:8080`
- Keycloak admin: `admin` / `admin`
- RabbitMQ management: `http://localhost:15672`
- RabbitMQ AMQP: `localhost:5673`
- Postgres: `localhost:5433`
- Redis: `localhost:6379`

Postgres contains two local databases:

- `platform`
- `keycloak`

## Seeded Identity

Keycloak imports `docker/keycloak/import/finaxis-realm.json` on first startup.

Local users:

- username: `local.admin`
- password: `local-admin`
- Keycloak subject: `11111111-1111-1111-1111-111111111111`

- username: `local.checker`
- password: `local-checker`
- Keycloak subject: `dddddddd-dddd-dddd-dddd-dddddddddd01`

Flyway seeds the matching application users, organisation, membership, branches, role,
and permissions. `local.checker` holds the same `local-admin` role so it can approve
`local.admin`'s invitations - `local.admin` cannot approve its own. The local profile
additionally seeds the platform membership needed for the full smoke path. The seeded
organisation and branch IDs are intentionally stable so smoke tests can be scripted.

## Running Locally

Start infrastructure:

```bash
docker compose up -d postgres redis rabbitmq keycloak
```

Start the application:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Run the smoke script:

```bash
./scripts/local-smoke.sh
```

The script obtains a Keycloak access token, selects the seeded organisation, selects a
branch using the `X-Active-Organisation-Context` header, and calls `GET /api/v1/auth/me`.

## Smoke Endpoint

`GET /api/v1/auth/me` returns the authenticated application user profile, including:

- selected organisation
- selected branch
- assigned branches
- assigned roles
- effective permissions exposed as Spring Security authorities

This endpoint proves that Keycloak JWT validation, app-user mapping, tenant context,
database access, Redis session/header context, and permission resolution are wired.
