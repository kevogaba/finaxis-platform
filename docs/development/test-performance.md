# Test Suite Performance

How long the suite takes, where that time goes, and the rules that keep it fast. The suite is
the slowest part of `./gradlew qualityGate`, which CLAUDE.md requires before every push, so its
wall time is paid by every change.

## Benchmark

The benchmark is the CI **Quality Gate** job: a mean of **20.6 min** (median 21.0) over the last
20 green runs before this work, of which `:test` was about 19 min. The runner is GitHub's
`ubuntu-latest` for a public repository: 4 vCPU and 16 GB.

Each strategy below was measured locally on a 4-CPU, 15 GB machine (the same shape) with
`./gradlew test`, the JaCoCo agent attached as in CI, images already pulled, one full run per
step. "Test phase" is the first test class's start to the last one's end, read from the JUnit XML
reports; the Gradle figure is the whole `./gradlew test` invocation.

## Where the time went

The baseline run (406 classes, 3,586 tests) took 20.5 min of test phase:

| Bucket | Time | Why |
|---|---|---|
| Spring context boots | 539 s (63 boots) | 44% of the run |
| ...of which container starts | ~198 s | every context started its own servers |
| ArchUnit bytecode imports | ~160 s | re-imported for every rule method |
| JobRunr-driven integration tests | ~250 s | real 5 s polls and retry back-offs |
| Flyway migrations | 19 s (52 runs) | small; not worth optimising |

## Strategies and their savings

| Step | Change | Test phase | Saved | `./gradlew test` |
|---|---|---|---|---|
| R0 | Baseline | 20.48 min | | 1,271 s |
| R1 | Shared PostgreSQL, RabbitMQ and LGTM per test JVM | 16.43 min | 4.05 min | 1,008 s |

Together: **20.48 min to 16.43 min of test phase (-20%)**, every test still passing.

### 1. One PostgreSQL, RabbitMQ and LGTM server per test JVM

The suite builds about forty distinct Spring contexts, and each declared its containers as
`@Bean`s, so each context started - and on eviction from the context cache stopped - its own
PostgreSQL, Redis and, where imported, RabbitMQ and the Grafana LGTM stack: 111 container starts
per run, with up to 21 PostgreSQL servers alive at once on a 4-CPU runner.

`SharedTestContainers` starts one of each per test JVM, on first use, and gives every context
what a fresh container gave it:

- **an empty database of its own** (`CREATE DATABASE` on the shared server), migrated by the
  context's own Flyway exactly as before, and dropped when the context closes;
- **a RabbitMQ virtual host of its own**, so queues, exchanges and messages never cross contexts;
- the shared LGTM stack, which holds nothing any test asserts on.

Redis stays a container per context: it starts in 0.3 s, and the rate limiter takes its database
index from `spring.data.redis.database` rather than the connection details, so a shared server
could not keep two contexts' buckets apart.

Isolation is unchanged. No test reads another database's rows, and advisory locks are
database-scoped. The `pg_locks`/`pg_stat_activity` probes are server-wide views, but each looks
only for backends blocked by its own test's transactions (by pid, by `current_database()`, or on
an advisory key only that test contends), and tests in one JVM run one at a time.

Saved **4.05 min**: container starts fell from 111 to 46 (42 of them the sub-second Redis), and
context boots from 539 s to 327 s - more than the container time alone, because the runner no
longer carries a score of idle servers.

## Considered and not done

- **A pre-migrated template database.** Flyway costs 19 s a run, and skipping it would stop the
  application's own Flyway from migrating an empty database in any context.
- **Turning off Spring Modulith's runtime verification in tests.** It costs about 19 s once per JVM
  (Modulith caches it); it is a production fail-fast check and feeds the observability support.

## Keeping it fast

- Never declare a PostgreSQL, RabbitMQ or LGTM container as a `@Bean`: import
  `PostgresTestConfiguration`, `PostgresRabbitTestConfiguration` or `TestcontainersConfiguration`.
  A new backing service follows `SharedTestContainers`' pattern: one server per JVM, one isolated
  namespace per context.
- Every distinct `@Import`, `properties`, `@MockitoBean` or `@DynamicPropertySource` combination is
  a context boot (about 5 s). Reuse an existing combination where the test allows.
- To compare runs, read the JUnit XML in `build/test-results/test`: each `TEST-*.xml` has the class
  start time and duration, and Spring's `Started ... in N seconds` line marks a context boot.
