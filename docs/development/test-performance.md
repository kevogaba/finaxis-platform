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
| R5 | CI runs the tests as three shards on three runners | CI only | slowest shard 5.46 min | |

Together: **20.48 min to 10.18 min of test phase (-50%)**, every test still passing.
Steps R4 and R5 shorten CI only.

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

### 5. Test shards on CI (CI only)

After steps 1 to 4 the job was about two minutes of setup, compilation and static analysis, then
7.5 to 9 minutes of `:test`, and that test phase does not keep the runner busy. A local run with
every process's CPU sampled used 67% of the four CPUs on average, and from the sixth minute on
only 1.6 to 2.5 of them, while each test JVM mostly waited: on JobRunr polls and retry back-offs,
on awaits, on PostgreSQL. The test JVMs took 74% of the CPU used, PostgreSQL 12%, Gradle and the
system 10%, and the LGTM stack under 2%, so there was no busy neighbour left to remove. A third
fork would soak up some of the idle time, but a fork needs about 4 GB with its own servers, so the
fork count is sized to memory ("Smaller runners") and a 16 GB runner holds two.

So the workflow runs the suite on three runners at once, as five jobs:

- `Tests (1/3)` to `Tests (3/3)` run `./gradlew test -PtestShard=i/3`. A class belongs to the
  shard its top-level class name hashes to, so the split needs no list to maintain, a nested class
  always runs with its outer class, and every class runs in exactly one shard: 123, 150 and 133
  classes, about 37%, 32% and 31% of the suite's time. The shard count is the matrix's length.
- `Static Analysis` runs everything else `check` runs (`check -x test`) and `bootJar`.
- `Quality Gate` runs after the others and **fails unless each of them succeeded**; GitHub reports
  a job skipped after a failure as a success, so this is the check to require. It then requires
  one data file per shard and runs the same two coverage rules, and the report, over the shards'
  merged JaCoCo data (`-PshardCoverage=<dir>`). The classes they are measured against are the ones
  a shard ran, copied from it rather than compiled again, because JaCoCo matches execution data to
  classes by a checksum of their bytecode. An empty data or class set fails the task instead of
  passing a rule over nothing.

Locally nothing changes: `qualityGate` still runs everything in one invocation.

Checked locally, on the same 4-CPU machine:

- Split three ways the shards ran 123, 150 and 133 classes (1,164, 1,229 and 1,193 tests), and
  split two ways 201 and 205 classes (1,780 and 1,806 tests): disjoint each time, and together
  exactly the 406 classes and 3,586 tests of an unsharded run.
- The two-way merged report equals an unsharded run's line for line: 1,043 classes, the same
  covered and missed lines, branches and instructions, so both coverage rules judge exactly what
  they judged. A merge of n shards is the same union of the same probes.
- With no execution data, or data but no classes, the three coverage tasks fail. JaCoCo on its own
  *skips* a task whose data is missing, which a build reports as success, so merge mode overrides
  that.

| Split | Test phase per shard | Slowest |
|---|---|---|
| None | 10.24 to 10.48 min | 10.48 min |
| Two shards | 7.13 and 5.58 min | 7.13 min |
| Three shards | 5.46, 4.63 and 4.86 min | 5.46 min |

Not a clean division: every shard pays its forks' start-up (the first context boot is about 50 s)
and boots the contexts its own classes need (two-way, 38 and 36 of the 68), and every shard job
pays about two minutes of Gradle start and test compilation before its first test. Two shards
were tried on CI first; on a slow runner they left the check above ten minutes (see "Results on
CI"), so three. The cost is runner time: each extra job pays its own setup, which standard runners
give a public repository for free.

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

The test shards of step 5 were measured in pairs instead, because the runner pool was slow that
day: the unsharded job took 14.6 to 15.6 min, against 9.6 and 11.8 min that morning. Each
sharded run but the first started within seconds of an unsharded one, and the time is from the
run's start to the end of its `Quality Gate` job, which is when the check reports:

| Started (UTC) | Unsharded job | Two shards | Three shards |
|---|---|---|---|
| 08:48 | | 11.2 min | |
| 09:03 | 14.6 min | 11.3 min | |
| 09:28 | 14.8 min | | 10.3 min |
| 12:51 | 15.6 min | | 10.8 min |

Three shards took **4.5 and 4.8 min (about 31%) off the unsharded job started with them**. In
each three-shard run the first shard finished last, 1.3 to 2.1 min after the other two: its
classes hold 37% of the suite's time, and each shard pays about two minutes of Gradle start and
test compilation before its first test plus about 50 s for its forks' first context.

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
before R1. With the test shards of step 5, each 2-vCPU runner would run one fork over about a
third of the suite instead (an estimate, not simulated).

## Considered and not done

- **A pre-migrated template database.** Flyway costs 19 s a run, and skipping it would stop the
  application's own Flyway from migrating an empty database in any context.
- **Turning off Spring Modulith's runtime verification in tests.** It costs about 19 s once per JVM
  (Modulith caches it); it is a production fail-fast check and feeds the observability support.
- **Shortening the JobRunr waits.** Those tests deliberately run the real background server and its
  retry timing; tightening them trades minutes for flakiness.
- **JUnit's in-JVM parallel execution.** Classes sharing a context also share a database, and many
  tests reset shared seed rows; running them concurrently would need every such test rewritten.
- **The C1 compiler only (`-XX:TieredStopAtLevel=1`).** Measured: 13.80 min of test phase against
  10.48, context boots 11.8 s instead of 8.0. The suite runs long enough for C2 to pay for itself.
- **The parallel garbage collector (`-XX:+UseParallelGC`).** Measured in two pairs against G1: the
  test JVMs used 12% less CPU, but the test phase moved by 0.8 and by 0.0 min, inside the spread of
  identical runs. The suite waits more than it computes, so CPU saved barely shows on the clock.
- **Restricting the JaCoCo agent to `com.finaxis.*`.** Measured: no change in test phase.
- **Sharing one LGTM stack between forks.** It used under 2% of the CPU of a sampled run.
- **Merging contexts.** The 58 distinct context keys are deliberate: properties, mocks, `@Import`s
  or `@DirtiesContext` that a test's assertions depend on. The two that look identical
  (`BootstrapKeycloakRetryIntegrationTests`, `KeycloakProvisioningRetryIntegrationTests`) are each
  discarded after their class (`@DirtiesContext`), which keeps their JobRunr servers and jobs apart.
- **Building `AccountingQueryPlanTests`' 50,000-journal fixture faster.** Its minute is PostgreSQL
  maintaining the real indexes; loading first and indexing after would change the block counts
  the test budgets.

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
