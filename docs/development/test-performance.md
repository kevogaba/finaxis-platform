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
| R2 | One ArchUnit import per test JVM | 14.26 min | 2.17 min | 878 s |
| R3 | Two test JVMs (and two order dependencies fixed) | 10.18 min | 4.08 min | 636 s |
| R4 | CI pulls the test images during compilation | CI only | 50-72 s of pulls | |

Together: **20.48 min to 10.18 min of test phase (-50%)**, every test still passing.
Step R4 shortens CI only.

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

### 2. One ArchUnit import per test JVM

Seven rule classes imported `com.finaxis.platform` in a property initializer, and JUnit creates a
test instance per method, so a run re-read every production class file 67 times, about 2.5 s each.
They all asked for exactly the same set, and `JavaClasses` is immutable, so `ProductionClasses.all`
imports it once. `PermissionFreeReadRuleTests` went from 88 s to 5 s. Saved **2.17 min**.

### 3. Two test JVMs

`maxParallelForks` defaults to two on the CI runner (`-PtestForks=N` overrides; 1 restores a single
JVM); it is sized to the machine, see "Smaller runners" below. Each fork has its own
context cache and its own shared servers, so forks never see each other's data. The cost is that a
context used by classes in both forks boots in both (68 boots instead of 63), and that the boots
are slower on a busy CPU. Three forks were measured too:

| Forks | Test phase |
|---|---|
| 1 | 14.26 min |
| 2 | 10.18 min |
| 3 | 9.72 min |

Three forks saved another 0.46 min, within run-to-run noise, with the CPU saturated: the mean
context boot rose from 7.6 s to 11.5 s and the summed class time from 19.4 to 25.8 min. Two keep
headroom for the race tests that wait on real timing.

Spreading classes over two JVMs changes which class runs after which, and that exposed two
pre-existing order dependencies, both reproducible in a single JVM by running the same classes in
the same order:

- `AuthFlowIntegrationTests` reset the shared seed before each test but not after, so the class
  that followed it inherited `local.admin` suspended, deprovisioned or stripped of its profile
  grant. In one JVM the next class happened to repair it. It now restores the seed after each test
  too.
- `AccountingQueryPlanTests`' volume fixture left the order of each journal's four lines to the
  planner, which emitted them journal-major or leg-major depending on the statistics autovacuum had
  gathered, so Q2's cost moved between 559 and 1,030 blocks with the classes that ran before it
  (budget 900). The fixture now writes a journal's lines together, as the posting engine does; Q2
  costs 289 blocks every time and every other budget is equally stable.

Saved **4.08 min**.

### 4. Pulling test images while the build compiles (CI only)

Testcontainers pulled the images lazily, inside `:test`. A CI runner starts with none of them, so
every run paid for those pulls on the critical path. The workflow now pulls the images listed in
`TestContainerImages.kt` in the background right after checkout, so the downloads overlap Java and
Gradle setup, compilation and static analysis, about two and a half minutes before `:test` starts.

Measured locally with the images removed, as on a fresh runner, the suite took 11.22 min of test
phase against 9.47 and 10.18 min with them present: each fork blocked on pulls for 50 to 72 s (the
LGTM image 26 to 52 s, PostgreSQL about 10 s, Redis, RabbitMQ and GreenMail a few seconds each).
That is the time the pre-pull takes off `:test` when it finishes first.

## Results on CI

The same `ubuntu-latest` runner as the benchmark, one run per branch of the stack:

| Branch | Quality Gate job | `:test` |
|---|---|---|
| Baseline | 20.6 min (mean of 20) | 17.50 min (mean of 10) |
| R1 shared servers | 15.7 min | 13.50 min |
| R2 + one ArchUnit import | 16.2 min | 13.41 min |
| R3 + two test JVMs | 11.5 min | 8.89 min |
| R4 + image pre-pull (two runs) | 12.4 and 14.4 min | 9.61 and 11.16 min |

`:test` is read from the job log, from `> Task :testClasses` to `> Task :check`. Across the ten
baseline runs it varied from 14.8 to 19.6 min (standard deviation 1.95 min), and the two runs of the
identical R4 commit differed by 1.5 min, so one run per branch cannot resolve a step of a minute or
two: on CI the ArchUnit step and the pre-pull both fall inside that spread, and their savings above
come from the controlled local runs. The three CI runs with two test JVMs (R3 once, R4 twice) took
11.5, 12.4 and 14.4 min for the job, a mean of **12.7 min against the benchmark's 20.6 (-38%)**,
and 8.89, 9.61 and 11.16 min for `:test`, a mean of **9.89 min against 17.50 (-43%)**.

## Smaller runners

A private repository's standard GitHub runner has 2 vCPUs and 7 to 8 GB instead of 4 and 16. That
was simulated by confining the Gradle daemon, the test JVMs and every Docker container (through
`dockerd --cgroup-parent`) to one cgroup of 2 CPUs and 8 GB, with the images already pulled:

| Configuration | Test phase | `./gradlew test` | Outcome |
|---|---|---|---|
| Before R1 | 22.61 min | 23m40s | green, at the 8 GB ceiling throughout |
| Two forks (R3's fixed default) | | failed at 13m38s | a test JVM killed for lack of memory |
| One fork | 16.15 min | 16m58s | green, peak 6.8 GB |

Two forks needed about 8.1 GB: each test JVM about 2.7 GB (a 2 GB heap plus metaspace), and each
fork's own PostgreSQL (279 backends between them, one per pooled connection of every cached
context), RabbitMQ and LGTM about 1 GB more. So the default number of forks is sized to the
machine: half the cores, at most one per 4 GB after 2 GB for Gradle and the OS, at most four, at
least one. That is two on the 4-vCPU, 16 GB CI runner, as before, and one on a 2-vCPU runner.
Compilation and static analysis before `:test` took 4.0 min there from clean against 3.0 min on
4 cores, so a Quality Gate job on a 2-vCPU runner would take about 20 to 21 min, against about 28
before R1.

## Considered and not done

- **A pre-migrated template database.** Flyway costs 19 s a run, and skipping it would stop the
  application's own Flyway from migrating an empty database in any context.
- **Turning off Spring Modulith's runtime verification in tests.** It costs about 19 s once per JVM
  (Modulith caches it); it is a production fail-fast check and feeds the observability support.
- **Shortening the JobRunr waits.** Those tests deliberately run the real background server and its
  retry timing; tightening them trades minutes for flakiness.
- **JUnit's in-JVM parallel execution.** Classes sharing a context also share a database, and many
  tests reset shared seed rows; running them concurrently would need every such test rewritten.

## Keeping it fast

- Never declare a PostgreSQL, RabbitMQ or LGTM container as a `@Bean`: import
  `PostgresTestConfiguration`, `PostgresRabbitTestConfiguration` or `TestcontainersConfiguration`.
  A new backing service follows `SharedTestContainers`' pattern: one server per JVM, one isolated
  namespace per context.
- An ArchUnit rule over production code uses `ProductionClasses.all`, not its own importer.
- Every distinct `@Import`, `properties`, `@MockitoBean` or `@DynamicPropertySource` combination is
  a context boot (about 5 s, once per fork). Reuse an existing combination where the test allows.
- A test that changes shared seed rows restores them after itself as well as before. Which class
  runs next depends on how the classes are spread over the forks, and that changes whenever a class
  is added.
- To compare runs, read the JUnit XML in `build/test-results/test`: each `TEST-*.xml` has the class
  start time and duration, and Spring's `Started ... in N seconds` line marks a context boot.
