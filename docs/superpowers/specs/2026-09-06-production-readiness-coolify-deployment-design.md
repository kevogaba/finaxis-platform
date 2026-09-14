# Production Readiness and Coolify Deployment via GitHub Actions

Status: approved. Date: 2026-09-06.

## Context

CI (`container-image.yml`) already builds a deployable JVM image with Spring Boot's
`bootBuildImage` (Cloud Native Buildpacks; there is no hand-written Dockerfile) on every push to
`main`, but pushes it nowhere. Redis, Postgres, RabbitMQ, and Keycloak already run outside this
repository; this work does not provision them. The goal is: push that image to GHCR, deploy it to
an existing Coolify instance via Coolify's documented GitHub Actions webhook pattern
(`docs/applications/ci-cd/github/actions` on Coolify's docs), and make four operational switches
(email, OpenTelemetry, Sentry, API-docs exposure) configurable for the deployed environment.

Observability infrastructure has come back online faster than expected, and Sentry is going live
in production alongside it, which changes the original "OTel off until further notice" framing to
"OTel and Sentry on now, but every switch here must survive a future outage without a rebuild
wherever that is technically possible."

## The governing constraint: Spring AOT freezes `@ConditionalOnProperty` beans at build time

`build.gradle.kts` runs Spring AOT unconditionally for `bootBuildImage` (both JVM and native
variants) — this is not optional, per `docs/operations/native-image-deployment.md`. Verified
empirically for this spec (`./gradlew -PenableAot=true --rerun-tasks processAot` with different
env values, diffing `build/generated/aotSources/**/*BeanDefinitions.java`):

- A bean gated by `@ConditionalOnProperty` has its condition evaluated once, at `processAot` time,
  using whatever value was in the build environment. The losing branch's bean-registration method
  is not merely skipped at startup — it does not exist in the generated source at all. No
  environment variable set at container start or restart can bring it back; only rebuilding the
  image with a different build-time value can. Confirmed for both `finaxis.email.enabled` (this
  codebase's own `EmailConfiguration`) and `management.opentelemetry.enabled` plus its three
  per-signal export flags (`management.tracing.export.otlp.enabled`,
  `management.otlp.metrics.export.enabled`, `logging.export.otlp.enabled` — all Spring Boot
  framework auto-configuration we do not own and will not fork).
- A property read *inside* an unconditionally-registered bean's method body, at call time, is not
  frozen — it resolves the running container's actual environment every time. Confirmed for the
  OTLP exporter endpoint URLs, sampling probability, and resource attributes, and for Sentry's
  `enabled`/`dsn` (Sentry's Spring Boot auto-configuration registers its hub unconditionally; the
  SDK checks `enabled` internally during its own `Sentry.init`).

This determines which of the four switches below can be genuine Coolify-side environment-variable
toggles (edit the variable, Coolify restarts the container, done) versus which require a CI
rebuild (a repo variable flip plus a workflow re-run, ~90 seconds for the JVM image, still no code
edit and no PR).

## The four switches

### Email — genuine runtime toggle (owned code, restructured)

`finaxis.email.enabled` must become a live Coolify toggle per explicit direction. Since we own
`EmailConfiguration.kt`, replace the two `@ConditionalOnProperty`/`@ConditionalOnMissingBean`
split beans with a single, unconditionally-registered `@Bean` method that branches on
`EmailProperties.enabled` inside its body:

```kotlin
@Configuration
@EnableConfigurationProperties(EmailProperties::class)
class EmailConfiguration {
    @Bean
    fun emailGateway(
        properties: EmailProperties,
        mailSenderProvider: ObjectProvider<JavaMailSender>,
        rendererProvider: ObjectProvider<EmailTemplateRenderer>,
        meterRegistry: MeterRegistry,
    ): EmailGateway {
        val delegate: EmailGateway =
            if (properties.enabled) {
                SpringMailEmailGateway(mailSenderProvider.getObject(), rendererProvider.getObject(), properties)
            } else {
                DisabledEmailGateway()
            }
        return MeteredEmailGateway(delegate, meterRegistry)
    }
}
```

Using `ObjectProvider` for the mail sender and renderer avoids requiring either bean to exist
when email is disabled. `DisabledEmailGateway` and `MeteredEmailGateway` are unchanged.

Two boot-blockers surfaced during investigation must be fixed alongside this, or the toggle is
moot:

1. **Unresolvable SMTP placeholders.** `application-production.yaml` currently declares
   `${FINAXIS_SMTP_HOST}`, `${FINAXIS_SMTP_PORT}`, `${FINAXIS_SMTP_USERNAME}`,
   `${FINAXIS_SMTP_PASSWORD}`, `${FINAXIS_EMAIL_FROM_ADDRESS}`, `${FINAXIS_EMAIL_APP_BASE_URL}`
   with no defaults, while the base `application.yaml` already has sensible defaults for all six
   (e.g. `${FINAXIS_SMTP_HOST:localhost}`). Because `spring.mail.*` binds regardless of
   `finaxis.email.enabled`, an unresolvable placeholder is a hard startup failure with none of
   these six variables set — exactly the state Coolify will be in immediately after this change,
   with email off and no mail provider yet acquired. Give the production overrides the same
   defaults as the base file (or drop the redundant override entirely where the base default is
   already correct for production).
2. **Mail health indicator ignores the toggle.** `spring-boot-starter-mail` contributes a health
   indicator that dials the configured SMTP host regardless of `finaxis.email.enabled`, and
   nothing in the current config disables it. With email off and no real SMTP host, `/actuator/
   health` reports `DOWN` — which is exactly what Coolify polls to decide whether the deploy
   succeeded. Add `management.health.mail.enabled: false` unconditionally (this indicator reflects
   a best-effort downstream integration, not whether the app can serve traffic; it should never
   gate liveness/readiness).

### API docs / Scalar — genuine runtime toggle (moved to a layer we own)

Rather than depend on springdoc/Scalar's own (third-party, unverified) enable-flag behavior under
AOT, leave `springdoc.api-docs.enabled` and `scalar.enabled` always `true` — both are stateless,
side-effect-free documentation endpoints — and instead gate *unauthenticated* access at the
Spring Security layer, which is a single unconditionally-registered `@Bean`
(`SecurityConfiguration.securityFilterChain`) and therefore not subject to the AOT-freeze problem.

Add a new properties class alongside `CorsProperties`/`SecurityHeadersProperties` in
`SecurityProperties.kt`:

```kotlin
@ConfigurationProperties(prefix = "finaxis.security.api-docs")
@Validated
data class ApiDocsProperties(
    val publicAccessEnabled: Boolean = true,
)
```

In `SecurityConfiguration`, inject `ApiDocsProperties` and build the `requestMatchers(...)
.permitAll()` list conditionally: always permit `/actuator/health`; permit `/scalar/**`,
`/v3/api-docs/**`, `/swagger-ui/**` only when `apiDocsProperties.publicAccessEnabled` is true.
When false, those paths simply fall through to `anyRequest().authenticated()` — the operationally
meaningful "off" (hidden from anonymous callers), without touching springdoc/Scalar's own
auto-configuration at all. Default `true` (matches "expose Scalar for now" and keeps
`FoundationOpenApiContractTests`, which calls `/v3/api-docs` unauthenticated in the default test
profile, passing unchanged). Wire `${FINAXIS_API_DOCS_PUBLIC_ACCESS_ENABLED:true}` in
`application.yaml`; no override needed in `application-production.yaml` unless the production
default should differ (it should not, per the "expose it for now" direction).

Note for the design record, not a new requirement: enabling this makes the full API surface
(operation shapes, schemas, but no live data) publicly readable in production. That is what
"expose Scalar for now" means in practice.

### OpenTelemetry — endpoint/sampling live at runtime; on/off is a CI-build-time repo variable

No code change is required for the master switch: `management.opentelemetry.enabled` already
defaults to `true` and observability infrastructure is back, so the desired production state
matches the existing default. If full trace/metric/log export should also be on now (the three
per-signal flags default `false` in `application.yaml`), set them at CI build time as GitHub
Actions repository variables (`vars.FINAXIS_OTEL_TRACES_ENABLED`, `vars.FINAXIS_OTEL_METRICS_ENABLED`,
`vars.FINAXIS_OTEL_LOGS_ENABLED`) feeding the `bootBuildImage` invocation's environment, alongside
the existing `FINAXIS_NATIVE_IMAGE=false`. A future outage is handled the same way: flip the repo
variable, re-run the workflow (~90 seconds), Coolify's webhook auto-deploys the new image. This is
not a code change, not a PR, and not a Coolify environment edit — document it as the accepted
mechanism for switches Spring Boot's own framework auto-configuration owns.

Genuinely live at Coolify runtime, no rebuild ever: `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`,
`OTEL_EXPORTER_OTLP_LOGS_ENDPOINT`, `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`,
`FINAXIS_TRACING_SAMPLING_PROBABILITY`, `OTEL_SERVICE_NAME`, `OTEL_SERVICE_NAMESPACE`,
`FINAXIS_DEPLOYMENT_ENVIRONMENT`, `FINAXIS_SERVICE_VERSION` — point these at the now-available
production collector.

### Sentry — genuine runtime toggle (new wiring, activating for production)

`sentry.enabled: false` / `dsn: ""` in `application.yaml` are literal values today, not
env-parameterized, and `application-production.yaml` does not mention Sentry at all. Add, in
`application-production.yaml`:

```yaml
sentry:
  enabled: ${FINAXIS_SENTRY_ENABLED:true}
  dsn: ${FINAXIS_SENTRY_DSN:}
  environment: ${FINAXIS_DEPLOYMENT_ENVIRONMENT:production}
```

Confirmed via the same `processAot` methodology that Sentry's Spring Boot auto-configuration
registers its hub unconditionally regardless of `sentry.enabled` at build time — this is a live
Coolify-side toggle with no rebuild, matching the OTel-endpoint pattern. An empty `dsn` with
`enabled: true` is inert (Sentry no-ops without a DSN), so the safe default ships even before the
real DSN is supplied as a Coolify secret.

## CI: GHCR push and tagging

`container-image.yml`'s `jvm-image` job already builds `ghcr.io/kevogaba/finaxis-platform:0.0.1-SNAPSHOT-jvm`
into the local Docker daemon via `bootBuildImage` (no Dockerfile; `docker/build-push-action`, which
Coolify's own example workflow uses, does not apply here). Add, after the existing build step:

1. `permissions: packages: write` alongside the existing `contents: read`.
2. Authenticate to GHCR: `docker login ghcr.io -u ${{ github.actor }} -p ${{ secrets.GITHUB_TOKEN }}`
   (or `docker/login-action`).
3. Tag the already-built local image with two additional references and push both:
   - `ghcr.io/kevogaba/finaxis-platform:sha-<short-sha>-jvm` — immutable, the rollback target.
   - `ghcr.io/kevogaba/finaxis-platform:main-jvm` — floating; this is what the Coolify application tracks.
4. Keep the existing "Report the built image" step; it still reads the locally-built tag.

Only the JVM variant joins this automated path, per the existing workflow's own reasoning (native
OOMs on a standard GitHub-hosted runner) and the earlier decision to track JVM only in Coolify.
The `native-image` job is untouched (still `workflow_dispatch`-only, still builds
`ghcr.io/kevogaba/finaxis-platform:<version>` with no `-jvm` suffix, still not pushed).

If the OTel per-signal repo variables above are adopted, thread them into the `jvm-image` job's
`Build the JVM image` step alongside the existing `FINAXIS_NATIVE_IMAGE: 'false'`.

## CI: trigger the Coolify deployment

Final step of the `jvm-image` job, after the push:

```yaml
- name: Deploy to Coolify
  run: |
    curl --fail --request GET '${{ secrets.COOLIFY_WEBHOOK }}' \
      --header 'Authorization: Bearer ${{ secrets.COOLIFY_TOKEN }}'
```

Two new GitHub repository secrets: `COOLIFY_WEBHOOK` (the application's deploy webhook URL, from
its Coolify UI) and `COOLIFY_TOKEN` (a Coolify API token). Fully automatic — this step runs on
every push to `main`, matching the workflow's existing trigger and the "continuous deployment"
decision already made. `--fail` so a webhook error surfaces as a failed CI run rather than a
silently ignored one.

## Coolify application configuration (operator-side, documented not automated)

- Resource type: **Docker Image**, not a Coolify-native build — Coolify pulls
  `ghcr.io/kevogaba/finaxis-platform:main-jvm` rather than building from source.
- **The Coolify host itself needs `docker login ghcr.io`** with a `read:packages` PAT, configured
  once in Coolify's registry credentials — GHCR packages are private by default, and this is the
  most common cause of "webhook fired, nothing deployed."
- Port: `8081`. Health check: path `/actuator/health` **exactly** — `SecurityConfiguration`
  permits only that literal path unauthenticated, not `/actuator/health/**`, so
  `/actuator/health/readiness` or `/liveness` will 401 against Coolify's check.
  `server.forward-headers-strategy: framework` is already correct for sitting behind Coolify's
  Traefik.
- Required environment variables (existing infrastructure, not provisioned by this work):
  `FINAXIS_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`, `FINAXIS_REDIS_HOST`/`_PORT`,
  `FINAXIS_RABBITMQ_HOST`/`_PORT`/`_USERNAME`/`_PASSWORD`, `FINAXIS_KEYCLOAK_ISSUER_URI`,
  `FINAXIS_KEYCLOAK_AUDIENCE`, `FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET` (no default — startup
  fails fast without it), `FINAXIS_CORS_ALLOWED_ORIGINS`, `SPRING_PROFILES_ACTIVE=production`.
- **`FINAXIS_KEYCLOAK_ISSUER_URI` must be the public URL**, reachable both by the app container
  and by whatever URL clients use to obtain tokens — Keycloak stamps each token with the issuer
  URL the caller used, so an internal-only hostname here 401s every request. This is the same trap
  `compose.yaml` documents for local host networking.
- The four switches above: `FINAXIS_EMAIL_ENABLED` (default `false` until a mail provider exists),
  `FINAXIS_SENTRY_ENABLED`/`FINAXIS_SENTRY_DSN`, `FINAXIS_API_DOCS_PUBLIC_ACCESS_ENABLED` (default
  `true`), and the OTel endpoint/sampling variables above.

This inventory is written up as `docs/operations/coolify-deployment.md`.

## Testing

- `EmailConfiguration`: replace any implicit coverage with an explicit Spring context test
  asserting `FINAXIS_EMAIL_ENABLED=true` yields a gateway that delegates to
  `SpringMailEmailGateway` and `=false` yields one that delegates to `DisabledEmailGateway` —
  behavioral proof independent of the AOT question, which only bites the built image.
- `SecurityConfiguration`: extend `SecurityHeadersIntegrationTests` (or a new focused test) to
  assert `/scalar/**` and `/v3/api-docs/**` are permitted when `publicAccessEnabled=true` and
  require authentication when `false`.
- Add a fast regression for the mail health indicator: with email disabled and no reachable SMTP
  host, `/actuator/health` still reports `UP`.
- `FoundationOpenApiContractTests` must keep passing unchanged (default profile keeps
  `publicAccessEnabled=true`).
- No new Testcontainers-level infrastructure is introduced; existing suites cover the touched
  modules already per `docs/architecture/financial-transaction-atomicity.md`'s and this repo's
  general integration-test expectations. `container-image.yml` changes are validated by the
  workflow itself running once merged (CI cannot be unit-tested locally beyond a manual read).

## Stack (per CLAUDE.md's one-PR-per-conventional-commit, stacked-small rule)

1. **Config toggles** — `EmailConfiguration` restructure, SMTP placeholder defaults, mail health
   indicator disable, `ApiDocsProperties` + `SecurityConfiguration` change, Sentry production
   wiring, and the accompanying tests. Branches off `main`.
2. **CI: GHCR push and tagging** — `container-image.yml` changes for permissions, login, tag,
   push, and (if adopted) the OTel repo-variable build args. Branches off (1).
3. **CI: Coolify deploy trigger + operator documentation** — the webhook `curl` step and
   `docs/operations/coolify-deployment.md`. Branches off (2).

Each branch must pass `./gradlew qualityGate` on its own base before its PR is opened.

## Out of scope

- Provisioning Redis/Postgres/RabbitMQ/Keycloak in Coolify — they already exist elsewhere.
- Native-image publishing or Coolify deployment of the native variant.
- Restructuring Spring Boot's own OpenTelemetry auto-configuration to make its on/off switch
  live without a rebuild — assessed and rejected as disproportionate risk against framework
  internals this repository does not own.
- Bumping the semantic application version in `build.gradle.kts` — tagging uses git SHA plus a
  floating tag instead, per explicit direction.
