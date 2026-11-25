# Deployment Notes: Spring Boot 4.2, Namastack 2.0 and AMQP 1.0 Consumers

For the deployment team. These notes cover three pull requests merged in order:

| PR | Change |
| --- | --- |
| #349 | Spring Boot 4.2, Spring Modulith 2.2, Namastack outbox 2.0 and the other dependency upgrades, with their configuration |
| #350 | The `@RabbitListener` consumers move to RabbitMQ's native AMQP 1.0; the Namastack outbox keeps publishing over AMQP 0.9.1 |
| The properties-first PR | Custom configuration code replaced by properties: the rate limiter's Redis client built from Spring Boot's Redis configuration, Namastack's idempotency header, an unused pagination customizer and the production profile's repeated mail settings removed |

Nothing here needs a new required variable. The checklists say what to check before the first
deploy, what may need changing, and what to verify afterwards.

## Before the first deploy

- [ ] **RabbitMQ is 4.0 or later** (#350). AMQP 1.0 is native to RabbitMQ 4; compose and the
  tests run 4.3.2. Check with `rabbitmq-diagnostics server_version`. Both protocols use the same
  port and credentials (`FINAXIS_RABBITMQ_*`); nothing else changes on the broker.
- [ ] **RabbitMQ is up and reachable before the application starts** (#350). The application now
  refuses to start while RabbitMQ cannot be reached (it used to start without its consumers);
  Coolify restarts it until the broker answers. The outbox keeps every event in PostgreSQL in the
  meantime, so nothing is lost.
- [ ] **Schedule the first start for a quiet period** (#349). On its first start Namastack 2.0
  builds four new indexes on `outbox_record` with a plain `CREATE INDEX IF NOT EXISTS`, which
  blocks writes to `outbox_record` (and so every transaction that emits an event) until it
  finishes. The table keeps completed records; check its size first:
  `SELECT count(*) FROM outbox_record;`. The five Namastack 1.x indexes stay in place.
- [ ] **No `SERVER_FORWARD_HEADERS_STRATEGY` in any environment** (#349). Spring Boot 4.2
  deprecated `server.forward-headers-strategy`; the application now sets
  `server.request.header.forwarded.strategy: web-server`, and Spring Boot refuses to start when
  both are set. Remove the old variable if an environment has it. Forwarded headers are still
  believed only from `FINAXIS_CLIENT_IP_TRUSTED_PROXIES`.
- [ ] **No override of `SPRING_RABBITMQ_PUBLISHER_CONFIRM_TYPE`, `SPRING_RABBITMQ_PUBLISHER_RETURNS`
  or `SPRING_RABBITMQ_TEMPLATE_MANDATORY`** (#349). Namastack 2.0's publisher refuses to start
  without `correlated` confirms, and unroutable messages are failed (and retried) only with
  returns and mandatory on.

## Environment variables

No `FINAXIS_*` variable is renamed or removed.

| Variable | Change | PR | Action |
| --- | --- | --- | --- |
| `FINAXIS_RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | Now also configure the AMQP 1.0 client (`spring.amqp.rabbitmq.*`) | #350 | None |
| `FINAXIS_REDIS_TIMEOUT` | **New, optional**, default `60s`: the command timeout of the permission cache's and the session's Redis calls (`spring.data.redis.timeout`), the Lettuce default they already ran with | Properties-first | None |
| `FINAXIS_RATE_LIMIT_REDIS_TIMEOUT` | **New, optional**, default `5s`: the rate limiter's own Redis command timeout (`finaxis.rate-limit.redis-timeout`), how long a request waits on a Redis that stops answering before the limiter fails open; the 5 s it always had | Properties-first | None |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Now sets the collector for all three signals: its base URL, e.g. `http://collector:4318`; each exporter appends `/v1/traces`, `/v1/logs` or `/v1/metrics` | #349 | Optional: replace three per-signal variables with this one |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` / `_LOGS_` / `_METRICS_` | Unchanged; still override one signal (a full URL) | #349 | None |
| `SERVER_FORWARD_HEADERS_STRATEGY` | Must not be set (see above) | #349 | Remove if present |
| `JAVA_TOOL_OPTIONS` | The JVM image adds `--enable-native-access=ALL-UNNAMED` (Netty, under the AMQP 1.0 client) | #350 | None for the image. A JVM started outside it other than with `java -jar` (whose manifest grants it) should pass the flag, or the JDK logs a restricted-method warning |

## Images and the build

- **Spring Cloud Bindings are switched off in the image build** (#349): the Paketo Spring Boot
  buildpack adds them, and they fail Spring Boot 4.2 at startup (`EnvironmentPostProcessor` class
  not found). `bootBuildImage` sets `BP_SPRING_CLOUD_BINDINGS_DISABLED=true`. Anyone building the
  image another way (`pack`, a different builder) must set it too.
- **The builder stays `paketobuildpacks/builder-noble-java-tiny`** (Ubuntu 24.04) (#349). Spring
  Boot 4.2 moved its default to `paketobuildpacks/ubuntu-resolute-builder` (Ubuntu 26.04); the
  build pins the previous one, so the image's operating system does not change with this
  upgrade.
- **The native image has not been rebuilt** with the AMQP 1.0 client (its build needs more memory
  than CI has). The JVM image is what deploys, as before.

## Behaviour to know

- **Health.** `/actuator/health` gains an `amqprabbit` component (the AMQP 1.0 connection) beside
  `rabbit` (AMQP 0.9.1, the outbox publisher) (#350).
- **Two RabbitMQ connections per instance** (#350): in the RabbitMQ management UI, one AMQP 0-9-1
  connection (the outbox publisher, Java client 5.x) and one AMQP 1.0 connection (the consumers
  and the topology, client 1.x).
- **Metrics** (#349): `outbox.records` no longer reports `status="completed"` (Namastack 2.0;
  `NEW` and `FAILED` remain). Update any dashboard or alert that reads the completed count. The
  `<name>.active` long-task timers (`http.server.requests.active`, `outbox.record.process.active`)
  are kept.
- **Message headers** (properties-first PR): messages the outbox publishes carry
  `Idempotency-Key` (the outbox record id, stable across retries) instead of
  `X-Outbox-Record-Key`. Nothing in the application read the old header; anything outside it
  that inspects messages must switch.
- **Rate limiting during a Redis outage** (properties-first PR): unchanged. The limiter's client
  is now built from Spring Boot's Redis configuration, but keeps its own 5 s command timeout
  (`FINAXIS_RATE_LIMIT_REDIS_TIMEOUT`), so when Redis stops answering without refusing
  connections a request still waits at most 5 s before the limiter fails open. The permission
  cache and the session keep the 60 s Lettuce default (`FINAXIS_REDIS_TIMEOUT`).
- **Mail** (properties-first PR): no change in behaviour. `application-production.yaml` no longer
  repeats the base file's SMTP server, credentials and `finaxis.email.*`; it adds only SMTP
  authentication and STARTTLS, as before. SMTP is set entirely with `FINAXIS_SMTP_*`
  (`spring.mail.*`).

## After the deploy

- [ ] `/actuator/health` is `UP`, including `rabbit` and `amqprabbit`.
- [ ] The RabbitMQ management UI shows the two connections per instance described above.
- [ ] `SELECT status, count(*) FROM outbox_record GROUP BY status;` shows `NEW` draining to
  `COMPLETED` and no growing `FAILED` count.
- [ ] A membership activation in a test tenant produces its welcome-email job in JobRunr.

## Rollback

- **To before #350:** the AMQP 0.9.1 listeners resume; the queues, exchanges and messages are the
  same, and nothing persisted differs.
- **To before #349:** the `outbox_record` table and its payloads are unchanged between Namastack
  1.11 and 2.0, and the 1.x indexes were kept for exactly this rollback window (Namastack's 2.0
  migration guide); the four 2.0 indexes stay and 1.11 ignores them. Keep
  `namastack.outbox.processing-groups.enabled` at its default (`false`) until no 1.11 instance
  runs.
- **The properties-first PR** changes no stored data; rolling it back restores the old header.
