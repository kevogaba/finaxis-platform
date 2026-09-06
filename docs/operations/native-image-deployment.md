# Native Image Deployment

`./gradlew bootBuildImage` builds one of two deployable images with Cloud Native Buildpacks.
**Both run their bean definitions from Spring AOT output; AOT is not optional for either.**
What the switch changes is what consumes that output:

| | Native image | JVM image |
| --- | --- | --- |
| Tag | `ghcr.io/finaxis/platform:<version>` | `…:<version>-jvm` |
| Built by | `bootBuildImage` | `FINAXIS_NATIVE_IMAGE=false ./gradlew bootBuildImage` |
| Contains | A self-contained executable, no JVM | An AOT-processed jar on Liberica JDK 25 |
| Build time | ~13 minutes, ~10 GB of memory | ~1.5 minutes |
| Start-up | ~8 seconds | ~40 seconds |
| Spring Modulith runtime support | dropped, see below | present |

Both are published from the same commit and pick up the same configuration, so a deployment
can build the pair and choose per environment: the native image where start-up latency and
footprint matter, the JVM image where the runtime features it keeps matter more, or as a
fallback if a native-only defect appears.

`FINAXIS_NATIVE_IMAGE` (or `-PnativeImage`) selects the variant, and `FINAXIS_IMAGE_NAME`
overrides the image reference entirely for a registry that names things differently.
`./gradlew nativeCompile` produces the native executable on the host when a container is not
wanted.

This document covers how the build is wired, what the native build freezes at build time, and
how to run and verify either image locally.

## Prerequisites

- A GraalVM JDK 25 toolchain. `.sdkmanrc` selects `25.0.2-graalce`; `sdk env` installs and
  activates it. Gradle's toolchain resolution needs it on `JAVA_HOME` or in a directory it
  auto-detects.
- Docker, for `bootBuildImage` and for running the image.
- Roughly 10 GB of memory and 4 CPUs free for the `native-image` compiler. It sizes itself
  from the host and will use what is available.

## Building the images

```bash
./gradlew bootBuildImage                             # native, :0.0.1-SNAPSHOT
FINAXIS_NATIVE_IMAGE=false ./gradlew bootBuildImage   # JVM,    :0.0.1-SNAPSHOT-jvm
```

The version comes from `build.gradle.kts`. The two tags differ so both can sit in a registry
at once; `FINAXIS_IMAGE_NAME` replaces the reference wholesale if a pipeline needs its own
naming.

CI builds both on every push (`.github/workflows/container-image.yml`), independently, so a
failure in one still reports the other. Nothing is pushed to a registry yet.

The buildpacks download their toolchain from inside the build container. Behind a
TLS-inspecting egress proxy those downloads are re-signed with a private CA the container
does not trust and the build fails on certificate verification; mount a PEM bundle over the
container's trust store to fix it:

```bash
FINAXIS_BUILD_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt ./gradlew bootBuildImage
```

To produce only the executable, without Docker:

```bash
./gradlew nativeCompile
build/native/nativeCompile/platform
```

That binary is the artefact for a **direct VM deployment**, where the container image is the
artefact for an orchestrated environment. Both are built from the same AOT output and the same
runtime hints; they differ only in what wraps the result, which is why the two deployment shapes
can be chosen per environment rather than being separate builds to maintain.

Both are slow compared with a JVM build - the `native-image` compiler does whole-program
static analysis - so neither is part of `qualityGate`.

### Memory, and why this is not in CI

**A native build needs more memory than a GitHub-hosted standard runner has.** The `Container
Image` workflow therefore builds only the JVM image on every push, and its `native image` job is
`workflow_dispatch`-only.

That is measured rather than assumed. Two runs on `ubuntu-latest` both ended in `The Native Image
build process ran out of memory`: the first after 28 minutes, the second after 37 even with `-Ob`
quick-build mode and a 12 GB swapfile - and the second had completed the entire reachability
analysis, 66,380 types reachable, before dying while writing the image. The runner reports
`6.29GB of memory (75.6% of system memory, in container)` across two cores. The same build on a
15 GB developer machine peaks at **8.09 GB**, above the runner's whole budget.

Swap does not rescue the container build, because that failure is a Java heap exhaustion *inside*
`native-image` rather than the kernel running out of pages to hand out.

Locally the ceiling shows up differently and is worth knowing about, because the error does not
name memory:

```
=== Used heap size: 9595   Maximum heap size: 10325
=== Image generator watchdog is aborting image generation.
```

`native-image` sizes its own heap at roughly 80% of RAM, and when that is not enough it does not
fail cleanly - it thrashes the collector until the deadlock watchdog decides the build has stalled
and aborts it. **It can leave an executable but incomplete binary behind**, so a build that ended
this way must not be trusted just because `build/native/nativeCompile/platform` exists; check for
`BUILD SUCCESSFUL`. On a machine near the limit, give it swap and a heap that swap can back:

```bash
NATIVE_IMAGE_OPTIONS=-J-Xmx13g ./gradlew nativeCompile
```

The practical guidance: build the native artefact on a machine with at least 16 GB, or on a
larger CI runner. The JVM image has no such constraint and builds on a standard runner in about
seven minutes.

### The bare binary, verified

`nativeCompile` was run end to end against the compose stack, because "it builds" is not the
claim that matters for a VM deployment:

| Step | Result |
| --- | --- |
| `./gradlew nativeCompile` | `BUILD SUCCESSFUL`, generated in 7m 47s, 334 MB ELF executable |
| Start | `Started PlatformApplicationKt in 6.229 seconds`, Tomcat on 8081 |
| Flyway on an empty database | 6 migrations applied, latest `V6`; all five accounting tables created |
| `./scripts/local-smoke.sh` | organisation selection, branch selection, `/api/v1/auth/me` and a paginated tenant endpoint all pass |
| Outbox | instance registered, `OutboxProcessingScheduler scheduled` |
| Reflection, serialization and proxy errors | none |

Two warnings appear and both are expected. `Unable to scan location: /db/migration (unsupported
protocol: resource)` is Flyway finding it cannot walk the classpath in a closed world - the
migrations still apply, from the resource hints registered for them, which the `V6` row above
confirms. `GC notifications will not be available because no GarbageCollectorMXBean...` is
inherent to SubstrateVM's collector.

The smoke script exits non-zero on its final step, platform-organisation selection, which returns
403 without `SPRING_PROFILES_ACTIVE=local`. That is the same on the JVM and the script explains
it; every tenant-scoped step before it passes.

The compiler targets the `x86-64-v3` baseline (its AMD64 default, not the build host's own
feature set), so an image built on one machine runs on any Haswell-or-later host. Older
hardware needs `-march=compatibility` passed through
`BP_NATIVE_IMAGE_BUILD_ARGUMENTS`, at a performance cost.

## How AOT and the native build are tied together

A native image is only correct when Spring AOT has run first. `processAot` generates the
bean definitions, proxy classes and reachability metadata that `native-image` compiles
against, and without them the image either fails to build or fails at startup.

Two mechanisms have to agree for that to happen, and the build now derives both from one
flag:

- `processAot` must actually run. It is expensive - it refreshes the application context at
  build time - so it stays off for ordinary `bootJar` and `qualityGate` builds.
- The bootJar manifest must carry `Spring-Boot-Native-Processed`. The Spring Boot Gradle
  plugin adds that entry to *every* bootJar as soon as the GraalVM plugin is applied, and
  Paketo's `spring-boot` buildpack turns the presence of that entry alone into a native-image
  build plan. A jar that carries the entry without carrying the AOT output is what
  `bootBuildImage` used to hand to `native-image`.

`build.gradle.kts` therefore runs AOT unconditionally for `bootBuildImage` and the native
tasks - it is not something a deployable build can switch off - and strips the manifest entry
whenever AOT did not run *or* the JVM image was asked for. That second case matters: the JVM
image is AOT-processed but must not be compiled to a binary, so it ships the AOT classes
without the entry and is told to use them by `BP_SPRING_AOT_ENABLED`, which adds
`-Dspring.aot.enabled=true` to its launcher.

Only `bootJar` and `qualityGate` run without AOT, because they produce nothing that is
deployed and refreshing the context at build time costs about a minute; `-PenableAot=true`
adds it to a plain `bootJar`.

Spring AOT also emits generated Java sources that the project does not own and cannot edit.
They inherit raw-type and unchecked warnings from the framework signatures they call, so the
repository-wide `-Xlint:all -Werror` policy is scoped to hand-written sources and
`compileAotJava` compiles without lint. See
[Static analysis](../development/static-analysis.md).

## What the build freezes into the image

GraalVM's closed-world assumption means the bean set is fixed when the image is built, not
when it starts. Spring AOT evaluates `@ConditionalOnProperty` and `@Profile` during
`processAot`, so whatever the build environment says at that moment is what the image
contains for good. Setting the property at runtime does not bring a bean back.

The conditionals that matter here:

- `finaxis.keycloak.admin.enabled` defaults to `false`, so the Keycloak admin gateway is
  absent and the no-op `IdentityProvisioningGateway` is baked in.
- No profile is active during `processAot`, so `@Profile("local")` beans such as
  `LocalPlatformSmokeMembershipSeeder` are not in the image at all.

`processAot` inherits the Gradle process environment, so build the image with the feature
set the deployment needs:

```bash
FINAXIS_KEYCLOAK_ADMIN_ENABLED=true ./gradlew bootBuildImage
```

Everything that is a plain property rather than a bean condition still resolves at runtime:
`application.yaml`, `application-production.yaml`, the `FINAXIS_*` variables, and the
`<springProfile>` blocks in `logback-spring.xml` all behave as they do on the JVM. This
includes `finaxis.email.enabled` as of `docs/architecture/email-delivery.md`'s current
description: `EmailConfiguration` registers a single unconditional bean that branches on the
property inside its method body rather than gating which bean gets registered, specifically so
`FINAXIS_EMAIL_ENABLED` can be flipped by restarting a deployed container - JVM or native -
without a rebuild. `finaxis.keycloak.admin.enabled` above has no equivalent treatment yet, so it
remains frozen at build time like any other `@ConditionalOnProperty` bean condition.

`developmentOnly` dependencies - DevTools and `spring-boot-docker-compose` - are excluded
from the native classpath by the Spring Boot Gradle plugin, so the compose-derived
connection details described in `compose.yaml` do not apply to the image. The `FINAXIS_*`
variables are authoritative there.

## What the native image leaves out

Exactly one capability, and only because there is no way to keep it.

Spring Modulith's runtime support bootstraps `ApplicationModules` by having ArchUnit import
the application packages from the classpath. A native image has neither class files to
import nor the dynamic plugin loading ArchUnit needs, so it fails at startup with a missing
`com.tngtech.archunit.core.importer.ModuleImportPlugin`
([spring-modulith#735](https://github.com/spring-projects/spring-modulith/issues/735)).
Its beans are lazy, but the observability post-processor and the startup module verifier
both force them.

There is no supported way around it. `ApplicationModulesFactory.defaultFactory()` is
hardwired to the ArchUnit-backed `ApplicationModules::of`, and no Modulith artefact ships a
factory that reads back the `application-modules.json` its own AOT processor generates - so
the precomputed structure exists but nothing consumes it. Recovering the feature would mean
embedding every application class file in the image as a resource for ArchUnit to import,
and getting ArchUnit itself to work under a closed world, to regain:

- the `/actuator/modulith` endpoint, which `management.endpoints.web.exposure.include` does
  not expose today in any case, and
- per-module observability spans.

`build.gradle.kts` therefore drops `spring-modulith-runtime`, `spring-modulith-actuator` and
`spring-modulith-observability-core` from the native build alone. It costs nothing else.
This application declares no `ApplicationModuleInitializer` beans, which is the other thing
the runtime support drives, and Modulith's event externalization and the Namastack outbox -
the parts production actually runs on - stay in the image. Module structure is still
verified where it matters: the `ApplicationModules.of(...)` verification in the test suite is
a first-class quality gate, and it runs on the JVM.

The JVM image keeps all three, which is the main reason to keep building it.

### What was recovered rather than dropped

Namastack's outbox observability advises the `outbox` bean through a pointcut on the
`Outbox` interface, and because the bean is declared as that interface Spring AOT never sees
the `OutboxService` subclass its proxy needs - the same shape as the Modulith repository
below. Excluding the artefact would have been the easy answer; pre-generating the proxy in
`NativeImageProxyConfiguration` keeps outbox observation spans and instance metrics in the
image instead. Any future capability lost to a CGLIB proxy is worth trying there first.

## What the image has to be told about

A native image contains only the resources and reflective entry points it was told about, and
a gap is never a build failure - it surfaces the first time something reaches for the missing
member. Spring's AOT processing covers what Spring itself loads, and the GraalVM reachability
metadata repository covers libraries that publish metadata. Two gaps are left, and each has a
`RuntimeHintsRegistrar` in `com.finaxis.platform.config`.

### jOOQ array types and generated records

`JooqNativeHints` covers two jOOQ gaps. The first registers the array counterpart of every
built-in data type.
`SQLDataType`'s static initializer derives them through `Class.arrayType()`, which resolves
in a native image only for array classes the image knows about and returns `null` otherwise.
jOOQ stores that `null` as a map key, and the initializer - and with it the `DSLContext`
bean - fails:

```
Caused by: java.lang.ExceptionInInitializerError
  at org.jooq.impl.DefaultDSLContext.<clinit>
Caused by: java.lang.NullPointerException
  at java.util.concurrent.ConcurrentHashMap.putIfAbsent
  at org.jooq.impl.DefaultDataType.<init>
```

The upstream metadata does not cover this for jOOQ 3.21, and the same gap has appeared there
before - 3.20 added `Decfloat` without it
([jOOQ#19124](https://github.com/jOOQ/jOOQ/issues/19124)). The registrar therefore reads the
type set back from `SQLDataType` instead of listing it, so a jOOQ upgrade that adds a
built-in type cannot silently reintroduce the problem.

The second registers the generated `*Record` classes, which jOOQ instantiates reflectively
whenever a query returns a record. Without their constructors the first `RETURNING` on a write
path fails with `IllegalStateException: Could not access record constructor`. They are read off
the generated schema (`Public.PUBLIC.tables`) rather than listed, so a new migration brings its
record type with it.

### JBoss Logging's generated loggers

`JBossLoggingHints` registers the classes JBoss Logging resolves by name.
`Logger.getMessageLogger` turns an annotated interface into a generated `<Interface>_$logger`
through `Class.forName`, and `getMessageBundle` does the same for `<Interface>_$bundle`.
Neither name appears in any bytecode, so the image drops both and the lookup fails at startup:

```
Caused by: java.lang.IllegalArgumentException: Invalid logger interface
  org.hibernate.validator.internal.util.logging.Log (implementation not found)
```

That takes Hibernate Validator down, and with it every `@ConfigurationProperties` binding.
Only Hibernate Validator's pair is registered; the other JBoss Logging users on the classpath
arrive through RESTEasy under `keycloak-admin-client`, whose gateway is conditioned out of the
image, so forcing them in would only add weight. An image built with
`FINAXIS_KEYCLOAK_ADMIN_ENABLED=true` needs those listed in the registrar too.

### CGLIB proxies AOT cannot see

A native image cannot enhance a class at runtime, so every CGLIB proxy must exist before the
image is built. Spring AOT builds them while processing the context - `preDetermineBeanTypes`
asks each auto-proxy creator what a bean's type will be, and the proxy class falls out of that
answer - but only for the type it can see. A `@Bean` method that declares an *interface* return
type hides the implementation the proxy has to subclass, so nothing is generated and the image
fails the first time the bean is used:

```
UnsupportedOperationException: CGLIB runtime enhancement not supported on native image.
  Make sure to enable Spring AOT processing to pre-generate
  'org.springframework.modulith.events.jdbc.JdbcEventPublicationRepositoryV2$$SpringCGLIB$$0'
```

Two beans here are that shape: Spring Modulith's `jdbcEventPublicationRepository`, declared as
`EventPublicationRepository` and implemented by a package-private `@Transactional` class, and
Namastack's `outbox`, declared as `Outbox` and advised by the outbox observability. Neither can
simply be dropped - the first is the event publication registry, and dropping the second would
lose a capability the JVM image keeps.

`NativeImageProxyConfiguration` builds the proxy class itself during AOT processing. That
produces the same class under the same generated name the auto-proxy creator asks for at
runtime, and the AOT engine captures it because its CGLIB class handler is installed for the
whole AOT context refresh, post-processors included.

Generating the class is only half of it. Spring makes two reflection registrations for every
proxy it generates itself, and a hand-rolled one needs both:

- the **proxy class**, with fields and methods as well as constructors. CGLIB reads
  `CGLIB$FACTORY_DATA` off it and calls its callback setters when instantiating, so
  constructors alone fail with `MissingReflectionRegistrationError`.
- the **target class's methods**. The proxy resolves the methods it overrides reflectively in
  its static initializer; without them every resolved `Method` is null and the first call
  through the proxy dies in `AdvisedSupport$MethodCacheKey` with a bare `NullPointerException`
  that names neither the proxy nor the missing metadata.

Both beans are lazy, so neither gap appears at startup - only at the first outbox tick or event
publication, which is why the verification below exercises a write path rather than stopping at
`/actuator/health`.

Finding the next *missing proxy* does not need a native build. Spring's repackaged CGLIB honours
`cglib.debugLocation`, so running the AOT jar on the JVM and diffing what it generates against
what AOT pre-generated lists exactly the proxies an image would be missing. Note that this finds
missing classes, not missing hints - the registrations above are guarded by a test instead:

```bash
./gradlew -PenableAot=true bootJar
java -Dspring.aot.enabled=true -Dcglib.debugLocation=build/cglib-runtime \
  -jar build/libs/platform-0.0.1-SNAPSHOT.jar
# anything under build/cglib-runtime that is not under build/generated/aotClasses
# will fail in a native image
```

### Nested configuration-properties types

Spring AOT registers each type annotated `@ConfigurationProperties`, but not every type reached
through it. `RateLimitProperties.paths` is one such reach: the image bound
`finaxis.rate-limit.paths.rules` against classes Kotlin reflection could not then load, and the
whole security filter chain failed with it:

```
Caused by: kotlin.reflect.jvm.internal.KotlinReflectionInternalError:
  Class not found: com/finaxis/platform/common/web/ratelimit/RateLimitPathRule
```

`RateLimitProperties` therefore carries `@RegisterReflectionForBinding` naming the two types it
reaches. A properties class that grows a nested type needs the same treatment; the check is
whether the type appears in `build/generated/aotResources/META-INF/native-image/com.finaxis/`
`platform/reachability-metadata.json` after `./gradlew -PenableAot=true processAot`, which is a
one-minute answer rather than a fifteen-minute one.

### Constraint validators

`HibernateValidatorHints` registers Hibernate Validator's built-in `ConstraintValidator`
implementations for reflective instantiation. Spring AOT registers the validators reached from
constrained *beans*, but request DTOs are not beans - they are method parameters bound per
request - so the validators their annotations need are absent and the first validated request
returns 500 with `BeanInstantiationException: No default constructor found`. The set is
discovered by scanning the package rather than listed, because the constraints the API uses
change with its surface.

### Everything the Namastack outbox reaches reflectively

`NamastackOutboxHints` covers three reaches, and every one of them fails the same quiet way:
the application starts, `/actuator/health` reports `UP`, and the outbox stops draining while
nothing else looks wrong.

- **The payload.** An externalized event is written to `outbox_record` as JSON on commit and
  read back by the polling publisher, through Namastack's own Jackson mapper rather than any
  Spring binding AOT would notice. This one is worse than an exception: without the metadata
  serialization *silently produces `{}`*, so the record is written empty and only the
  publisher fails later, on a payload that can no longer be recovered. A native image built
  before this hint existed wrote exactly that, with nothing in the log to say so.
- **The handler.** `OutboxHandlerInvoker` dispatches to `RabbitOutboxHandler.handle`
  reflectively, and without it the publishing thread dies with
  `MissingReflectionRegistrationError`.
- **The background tasks.** Both outbox lifecycle beans hand their work to a `TaskScheduler`
  on start-up and Spring runs each tick through a reflective `ScheduledMethodRunnable`. AOT
  registers the scheduled work it can find by walking bean definitions, which needs the
  bean's concrete type, and both are declared by an interface instead.

The accompanying test asserts each named method still exists, because a rename upstream would
otherwise reintroduce the silence rather than fail the build.

### Classpath resources

`NativeImageResourceHints` registers the scripts and templates that libraries read from the
classpath without declaring them:

- `schema/postgres/outbox-tables.sql`, which Namastack's JDBC outbox starter runs to create
  its tables. Without it `DSLContext` fails at startup with "No schema scripts found".
- Spring Modulith's JDBC event publication schema, created the same way.
- JobRunr's SQL migration directories, which it lists on the classpath and applies in order.
- `templates/email/*`, the FreeMarker templates the notifications module renders. Those are
  only touched when an email is actually sent, so a missing hint would fail a background job
  rather than startup.

When a new library starts reading its own classpath resources, the fastest way to find what
is missing is GraalVM's tracing agent against the AOT jar, then adding the paths it reports
to that registrar rather than committing the agent's output wholesale:

```bash
./gradlew -PenableAot=true bootJar
java -agentlib:native-image-agent=config-output-dir=build/native-agent \
  -Dspring.aot.enabled=true -jar build/libs/platform-0.0.1-SNAPSHOT.jar
```

## Running the image locally

`compose.yaml` carries the application as a profile-gated service, so it does not start with
the local infrastructure by default and `docker compose up` is unchanged:

```bash
docker compose --profile image up platform
```

Its `depends_on` covers everything the application resolves during startup - Postgres,
Redis, RabbitMQ, Keycloak for OIDC issuer discovery, and Mailpit, because
`spring-boot-starter-mail` contributes a health indicator that dials the mail host - so that
one command brings up the whole stack. The service publishes the same `8081` port `bootRun`
uses, so `./scripts/local-smoke.sh` works against either.

Point it at a different tag with `FINAXIS_IMAGE`:

```bash
FINAXIS_IMAGE=ghcr.io/finaxis/platform:1.2.3 docker compose --profile image up platform
```

## Verifying the image

```bash
curl -fsS http://localhost:8081/actuator/health
```

`/actuator/health` is the one endpoint `SecurityConfiguration` permits unauthenticated, and
a healthy start returns `{"status":"UP"}` - details stay hidden because
`management.endpoint.health.show-details` is `when_authorized`.

Startup is the signal that matters most for a native image. Reflection, resource and proxy
gaps do not fail the build; they fail the first time the missing member is touched, so a
container that reaches `UP` has already exercised Flyway, the jOOQ DSL, the Redis and
RabbitMQ connections and the JobRunr background server.

**`UP` is not a substitute for a request.** Health proves those components *connected*; it
does not serialize a session or a cached permission set, and `RedisSerializationHints` exists
precisely because that path is first touched by an authorized request. The same is true of the
outbox: health does not publish an event. So a native image that reports `UP` has cleared
startup and nothing more - run the authenticated smoke path below before believing the image
is good, because the failures this page is about are exactly the ones startup cannot reach.

## The JVM image

```bash
FINAXIS_NATIVE_IMAGE=false ./gradlew bootBuildImage
docker compose --profile image up platform   # with FINAXIS_IMAGE set to the -jvm tag
```

Same builder, same AOT output, no `native-image` step - which is why it takes about ninety
seconds instead of thirteen minutes. It keeps Spring Modulith's runtime support and
Namastack's outbox observability, and it is the variant to reach for when a native-only
defect appears.

Two details are worth knowing if you touch its `JAVA_TOOL_OPTIONS`:

- Paketo's `BPE_APPEND_*` concatenates with **no separator** unless `BPE_DELIM_*` supplies
  one. Without it the appended flags arrive glued to the option before them and the JVM
  refuses to start with `Unrecognized VM option`. `BPE_DELIM_JAVA_TOOL_OPTIONS` is set to a
  space for exactly this reason.
- Heap sizing is left to the buildpack's memory calculator, which derives an explicit `-Xmx`
  from the container limit. A `-XX:MaxRAMPercentage` beside it is ignored, so none is set.
