# Configuration: Properties First

Framework and library behaviour is configured through **properties** (`application*.yaml` and the
environment variables it names) wherever a property exists: Spring Boot, Spring Modulith,
Namastack, JobRunr, springdoc, Resilience4j and the rest. A property is read where the next
maintainer looks for it, is documented by its library, changes with the library's upgrade notes,
and can be set per environment without a rebuild. Code that does the same job has none of that.

## The rule

1. **Look for the property first.** Before writing a `@Bean`, a `*Customizer`, a `*Configurer` or
   a `WebServerFactoryCustomizer`, search the library's configuration metadata and reference
   documentation (below).
2. **Write custom configuration only when it is absolutely necessary**: no property exists, or
   the one that exists cannot express the requirement.
3. **Justify it with evidence.** The code's KDoc names the property you looked for and why it
   does not fit (it does not exist in the library's metadata, it takes a different shape, it
   applies to more than it should). Add the code to the inventory below in the same change.
4. **Re-check on upgrade.** A framework or library upgrade can add the property that was
   missing. Every upgrade goes through the inventory and replaces what a new property now covers,
   as the Spring Boot 4.2 and Namastack 2.0 upgrade did (see "Replaced by properties" below).

A reviewer asks for the evidence; "it was easier in code" is not.

## Finding a property

- **Configuration metadata in the jar.** Every Spring Boot module and most starters ship
  `META-INF/spring-configuration-metadata.json`: every key, its type, its default and its
  deprecation. To list a library's keys from the Gradle cache:

  ```bash
  unzip -p "$(find ~/.gradle/caches -name 'namastack-outbox-core-2.0.0.jar' | head -1)" \
    META-INF/spring-configuration-metadata.json | jq -r '.properties[].name'
  ```

- **Reference documentation.** Spring Boot's "Common Application Properties" appendix, Spring
  Modulith's appendix, Namastack's configuration reference, each library's own.
- **Release notes and configuration changelogs** of the version being adopted, for new keys and
  changed defaults.
- **Environment variables.** Spring Boot maps `OTEL_*` variables onto `management.*` properties
  ahead of `application.yaml` (`management.opentelemetry.map-environment-variables`), so a
  placeholder that only forwards such a variable is redundant.

## Inventory of custom configuration

Each entry is configuration code that stays because no property can do its job. The "Evidence"
column says what was checked.

| Code | What it does | Evidence that no property fits |
| --- | --- | --- |
| `TrustedProxyValveCustomizer` (`iam.adapter.inbound.security`) | Tomcat's `RemoteIpValve` believes `X-Forwarded-*` only from `finaxis.security.client-ip.trusted-proxies`, with four pinned header names, checked at startup (#256) | `server.tomcat.remoteip.internal-proxies` and `trusted-proxies` take regular expressions; the one CIDR list here also feeds `ClientIpResolver`, so properties would need it twice in two syntaxes, unvalidated |
| `AmqpListenerConfiguration` (`common.adapter.inbound.messaging`) | The AMQP 1.0 `@RabbitListener` factory and admin, and `finaxis.amqp.enabled` | Both Boot AMQP starters register `rabbitListenerContainerFactory`; no property chooses between them. `spring.amqp.listener.auto-startup` is generic AMQP 1.0, and Spring AMQP's registry starts containers on a context restart whatever their auto-startup (ADR 0045) |
| `SecurityConfiguration` | CORS, security headers, CSP, entry points | Spring Boot has no properties for Spring Security's CORS or response headers; `management.endpoints.web.cors.*` covers the actuator only |
| `EffectivePermissionCacheConfiguration` | Immediate Redis writes and a schema-versioned key prefix for `iam.effective-permissions` | `spring.cache.redis.key-prefix` is a fixed string; no property makes `RedisCacheWriter` write immediately. `spring.cache.redis.time-to-live` is used |
| `EmailConfiguration` | Chooses the email gateway inside the bean method | SMTP itself is `spring.mail.*` only (Boot's `JavaMailSender`: host, port, credentials, auth, STARTTLS, timeouts; `production` adds auth and STARTTLS). `finaxis.email.*` holds what Boot has no property for: the on/off switch, read at run time because Spring AOT freezes `@ConditionalOnProperty` at image build (see the class KDoc), the From address and name, and the app URL in links |
| `ApplicationConfiguration.httpAccessLogFilter` | Access log through SLF4J, and so OpenTelemetry | `server.tomcat.accesslog.*` writes Tomcat's own file only |
| `ApplicationConfiguration.jackson2ObjectMapper` | The Jackson 2 mapper JSONB persistence uses | Spring Boot 4 auto-configures Jackson 3 only; its Jackson 2 support is a deprecated module |
| `WebJsonConfiguration` | snake_case JSON on the HTTP API only | `spring.jackson.*` configures the application `JsonMapper`, which the outbox and Spring Modulith serialise events with in camelCase |
| `KeycloakAdminConfiguration` | The Keycloak admin client | Spring Boot has no auto-configuration for it |
| `TransitionModuleConfiguration` | Externalization selection and outbox routing | The exchange is each event's own `target`; `@Externalized` and `namastack.outbox.rabbit.default-exchange` take a fixed name. The deduplication header is the `namastack.outbox.rabbit.idempotency-header-enabled` property |
| `RateLimitConfiguration` | The Bucket4j proxy manager, its Redis connection and the rate-limit filter | Bucket4j has no Spring Boot integration. Its Lettuce client is built from the configuration Boot derives from `spring.data.redis.*` (credentials, TLS, topology), so that is its whole connection configuration. It is a client of its own rather than Boot's, because Boot's factory disposes and recreates its client on every context stop and start, which closed the limiter's connection (five integration tests failed when it shared Boot's). Its command timeout is its own property, `finaxis.rate-limit.redis-timeout` (5s), set on its connection: every API request waits on it, Boot has no property for a second client's timeout, and inheriting `spring.data.redis.timeout` (60s) made a Redis stall hold requests for 60 s (flagged by Sentry's review on #353) |
| `PaginationConfiguration` | Refuses malformed `page` and `size` with a 400 | `spring.data.web.pageable.*` clamps a bad value silently instead of refusing it, and no endpoint takes a `Pageable` |
| `*AmqpConfiguration` topology classes | Exchanges, queues and bindings | Spring AMQP declares topology from beans; there is no property form |
| `FoundationOpenApiConfiguration`, `PermissionRequirementOpenApiConfiguration` | Schemas, header parameters and permission notes computed from code | `springdoc.open-api.*` could hold the static title and description, but they stay beside the computed parts of the same document |
| `NativeImageProxyConfiguration` and the `*Hints` registrars | GraalVM reachability metadata | Runtime hints are code by design |
| `JdbcAuditingConfiguration`, `AccountingModuleConfiguration`, `AuditConfiguration` | Auditor, clock and use-case wiring | Application wiring, not framework settings |

## Replaced by properties

What the Spring Boot 4.2, Namastack 2.0 and Spring Modulith 2.2 upgrade moved from code or
duplicated configuration to properties:

| Was | Now |
| --- | --- |
| Three per-signal OTLP endpoint placeholders | `management.opentelemetry.otlp.endpoint` (Spring Boot 4.2), each exporter appending its path |
| The custom `X-Outbox-Record-Key` header in `rabbitOutboxRouting` | `namastack.outbox.rabbit.idempotency-header-enabled: true` (Namastack 2.0): `Idempotency-Key`, the outbox record id |
| About 90 lines building a Redis URI from Boot's Redis properties for the rate limiter | Boot's derived Redis configuration, copied onto the limiter's own factory; its timeout `finaxis.rate-limit.redis-timeout` |
| A `PageableHandlerMethodArgumentResolverCustomizer` for a resolver no endpoint used | Removed; `spring.data.web.pageable.*` exists if one ever does |
| `application-production.yaml` repeating the base file's `spring.mail` server and credentials and `finaxis.email.*` | Only what production changes: SMTP authentication and STARTTLS |
