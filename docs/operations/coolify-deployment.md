# Coolify Deployment

CI builds the JVM image (see [native-image-deployment.md](native-image-deployment.md) for how the
JVM and native variants differ) and pushes it to GHCR on every push to `main`
(`.github/workflows/container-image.yml`), then calls a Coolify deploy webhook. Coolify pulls the
image rather than building it — the application resource is configured as a **Docker Image**
deployment, not a Coolify-native build from source.

## One-time Coolify application setup

- **Resource type:** Docker Image.
- **Image:** `ghcr.io/finaxis/platform:main-jvm` — the floating tag CI pushes on every merge to
  `main`. `ghcr.io/finaxis/platform:sha-<short-sha>-run<run-number>-jvm` tags are also pushed,
  immutable, and are the rollback target if `main-jvm` ever needs to roll back to a specific
  build. The run number is part of the tag because re-running the workflow for the *same* commit
  with different repository variables — the documented way to flip a CI-build-time switch below
  — would otherwise silently overwrite the previous rollback tag with a differently-configured
  image under the same name.
- **Registry credentials:** the Coolify **host** needs `docker login ghcr.io` configured with a
  `read:packages` personal access token, because GHCR packages are private by default. This is
  configured once in Coolify's registry credentials, not per-deployment. **This is the most common
  cause of "the webhook fired but nothing deployed"** — check it first if a deploy silently does
  nothing.
- **Port:** `8081`.
- **Health check path:** `/actuator/health` — **exactly this path, not a sub-path.**
  `SecurityConfiguration` permits only the literal string `/actuator/health` unauthenticated;
  `/actuator/health/readiness` or `/actuator/health/liveness` will 401 against Coolify's check even
  though the probes themselves are enabled (`management.endpoint.health.probes.enabled: true`).
- Coolify sits in front of the application as a reverse proxy (Traefik); `server.forward-headers-
  strategy: framework` is already set for that.

## Required environment variables

Existing infrastructure — Redis, Postgres, RabbitMQ, and Keycloak already run outside this
deployment and are not provisioned by it:

| Variable | Notes |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `production` |
| `FINAXIS_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | |
| `FINAXIS_REDIS_HOST` / `_PORT` | |
| `FINAXIS_RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | |
| `FINAXIS_KEYCLOAK_ISSUER_URI` | **Must be the public URL** [^issuer] |
| `FINAXIS_KEYCLOAK_AUDIENCE` | |
| `FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET` | No default — startup fails fast without it |
| `FINAXIS_CORS_ALLOWED_ORIGINS` | |

[^issuer]: Reachable both by the app container and by whatever URL clients use to obtain tokens.
  Keycloak stamps each token with the issuer URL the caller used; an internal-only hostname here
  401s every request — the same trap `compose.yaml` documents for local host networking.

## The operational switches

Each of these behaves differently depending on whether it's read as a plain property at runtime,
or gates which Spring bean gets registered — the latter is frozen at CI build time under this
repository's mandatory Spring AOT processing (`docs/superpowers/specs/2026-09-06-production-
readiness-coolify-deployment-design.md` has the full empirical verification).

| Switch | Coolify runtime toggle? | Variable(s) |
| --- | --- | --- |
| Email | **Yes** | `FINAXIS_EMAIL_ENABLED` (default `false`) [^email] |
| API docs (Scalar/OpenAPI) public exposure | **Yes** [^api-docs] | `FINAXIS_API_DOCS_PUBLIC_ACCESS_ENABLED` (default `true`) |
| Sentry | **Yes** | `FINAXIS_SENTRY_ENABLED` (default `true`), `FINAXIS_SENTRY_DSN`, `FINAXIS_DEPLOYMENT_ENVIRONMENT` |
| OTel endpoints/sampling/resource attributes | **Yes** | `OTEL_EXPORTER_OTLP_*_ENDPOINT`, `FINAXIS_TRACING_SAMPLING_PROBABILITY` [^otel-endpoints] |
| OTel master on/off + per-signal export | **No** — CI build-time only [^otel] | not a Coolify variable |
| Keycloak admin (user provisioning into Keycloak) | **No** — CI build-time only [^keycloak-admin] | not a Coolify variable |

[^email]: Once enabled, also needs `FINAXIS_SMTP_HOST`/`_PORT`/`_USERNAME`/`_PASSWORD`,
  `FINAXIS_EMAIL_FROM_ADDRESS`, `FINAXIS_EMAIL_APP_BASE_URL`.
[^api-docs]: Enabling this also serves a relaxed, docs-compatible Content-Security-Policy on
  *every* response in place of the configured strict one — see production-hardening.md.
[^otel-endpoints]: Also `OTEL_SERVICE_NAME`/`_NAMESPACE`, `FINAXIS_DEPLOYMENT_ENVIRONMENT`,
  `FINAXIS_SERVICE_VERSION`.
[^otel]: `management.opentelemetry.enabled` and its three per-signal export flags are Spring
  Boot's own auto-configuration, frozen at image-build time. All four are set at build time via
  the GitHub Actions repo variables `FINAXIS_OTEL_ENABLED`, `FINAXIS_OTEL_TRACES_ENABLED`,
  `FINAXIS_OTEL_METRICS_ENABLED`, `FINAXIS_OTEL_LOGS_ENABLED` (each defaults `'true'`) — flipping
  one means setting the repo variable and re-running the workflow, not a Coolify environment
  change.
[^keycloak-admin]: `finaxis.keycloak.admin.enabled` gates `KeycloakAdminGateway`, the client used
  to provision an approved user into Keycloak; its no-op fallback silently no-ops that step
  instead. Frozen the same way as the OTel switches, via the `FINAXIS_KEYCLOAK_ADMIN_ENABLED` repo
  variable (default `'false'`, unchanged from this property's own default). **Deliberately not
  turned on by default here** — enabling it is a decision for whoever owns the Coolify inventory,
  because it also needs real credentials supplied at runtime: `FINAXIS_KEYCLOAK_ADMIN_SERVER_URL`,
  `FINAXIS_KEYCLOAK_ADMIN_REALM`, `FINAXIS_KEYCLOAK_ADMIN_CLIENT_ID`,
  `FINAXIS_KEYCLOAK_ADMIN_CLIENT_SECRET`.

## Repository setup (once, in GitHub)

- **Secrets:** `COOLIFY_WEBHOOK` (the application's deploy webhook URL, from its Coolify UI) and
  `COOLIFY_TOKEN` (a Coolify API token).
- **Variables** (optional; each already defaults to its current behaviour in the workflow):
  `FINAXIS_OTEL_ENABLED`, `FINAXIS_OTEL_TRACES_ENABLED`, `FINAXIS_OTEL_METRICS_ENABLED`,
  `FINAXIS_OTEL_LOGS_ENABLED` (all default `'true'`), `FINAXIS_KEYCLOAK_ADMIN_ENABLED` (default
  `'false'`). Set one and re-run the workflow to flip a build-time switch without an image
  rebuild's worth of code changes — just a workflow re-run.

## Deployment flow

1. A pull request merges to `main`.
2. `.github/workflows/container-image.yml`'s `jvm-image` job builds the image with
   `./gradlew bootBuildImage`, pushes `sha-<short-sha>-run<run-number>-jvm` and `main-jvm` to
   GHCR, then calls the Coolify deploy webhook.
3. Coolify pulls `main-jvm` and restarts the application container.
4. Coolify polls `/actuator/health` to judge whether the deploy succeeded.

This is fully automatic — every merge to `main` deploys. `jvm-image` only runs when
`github.ref == 'refs/heads/main'`, so a manual `workflow_dispatch` against some other branch
builds nothing and deploys nothing; re-running an existing push-triggered run (the way to pick up
a changed repository variable for the same commit) keeps its original `main` ref and still runs.
The `native-image` job is untouched by any of this: it stays `workflow_dispatch`-only regardless
of branch, is never pushed to GHCR, and Coolify never deploys it.
