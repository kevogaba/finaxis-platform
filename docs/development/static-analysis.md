# Static Analysis And Architecture Gates

This project enforces strict quality gates early so the rules are cheap to keep clean.
The build is Kotlin-first, but the toolchain is ready for future Java source.

## Repository Setup

- Gradle is a single-project Kotlin DSL build.
- Java toolchain is Java 25.
- Kotlin is 2.3.21.
- Spring Boot is 4.1.0.
- Spring Modulith is managed by the existing Spring Modulith BOM.

Versions are centralized in `gradle/libs.versions.toml`.

## Tool Ownership

- Spotless formats Kotlin, Gradle Kotlin DSL, Java, Markdown, YAML, and XML.
- ktlint runs through Spotless for Kotlin and Gradle Kotlin DSL formatting checks.
- Detekt analyzes Kotlin code smells, complexity, exceptions, KDoc, and bug risks.
- Checkstyle enforces Java style, naming, import, line-length, and JavaDoc rules.
- PMD analyzes Java source-level maintainability and best-practice issues.
- SpotBugs analyzes Java bytecode for high-confidence bug patterns.
- Error Prone runs as a `javac` plugin for Java compilation only.
- ArchUnit enforces project-specific hexagonal package boundaries.
- Spring Modulith verifies Spring application module boundaries.

Java-only tools are intentionally scoped to Java source and Java class output. They do
not scan Kotlin source as Java. SpotBugs is skipped until Java class files exist.

## Line Length

The project standard is 100 characters.

- Kotlin and Gradle Kotlin DSL: `.editorconfig`, ktlint, and Detekt.
- Java: Checkstyle and Spotless Java formatting.
- YAML, Markdown, XML, and related text: `.editorconfig` and Spotless hygiene.

Spotless is the primary formatter. Detekt and Checkstyle enforce the rule after
formatting so long lines do not slip through.

## Commands

Format code:

```bash
./gradlew spotlessApply
```

Run the organized static-analysis command:

```bash
./gradlew staticAnalysis
```

Run the complete local quality gate:

```bash
./gradlew qualityGate
```

Useful individual checks:

```bash
./gradlew spotlessCheck
./gradlew ktlintCheck
./gradlew detekt
./gradlew checkstyleMain checkstyleTest
./gradlew pmdMain pmdTest
./gradlew spotbugsMain spotbugsTest
./gradlew test
./gradlew check
```

`./gradlew test` runs the ArchUnit and Spring Modulith architecture tests.
`./gradlew check` depends on `staticAnalysis`, so CI gets the same quality gates.

## Reports

Reports use standard Gradle report locations:

- Detekt: `build/reports/detekt/`
- Checkstyle: `build/reports/checkstyle/`
- PMD: `build/reports/pmd/`
- SpotBugs: `build/reports/spotbugs/`
- Tests and architecture tests: `build/reports/tests/test/`
- JaCoCo: `build/reports/jacoco/test/html/`

## Suppressions

Prefer fixing code over suppressing rules.

Allowed suppressions must be narrow and documented near the suppressed code or in the
tool configuration. Do not suppress broad rule categories unless there is a staged
rollout note explaining why.

Use this order:

1. Refactor or simplify the code.
2. Tune a noisy rule only when it creates false positives for the project style.
3. Add a narrow suppression or exclusion with a TODO and reason.

## Architecture Rules

ArchUnit rules live under `src/test/kotlin/com/finaxis/platform/architecture`.
They currently enforce:

- inbound adapters do not depend on persistence adapters
- domain does not depend on adapters or configuration
- application code does not depend on inbound adapters
- persistence adapters do not depend on inbound adapters
- top-level application packages are cycle-free

Spring Modulith verification uses `ApplicationModules.of(PlatformApplication::class.java)`.
It complements ArchUnit by validating Spring module exposure and module dependencies.

When adding a new module, keep public APIs in the module package or explicitly model the
module boundary before depending on internals from another module.

## Compatibility Notes

Detekt uses the `dev.detekt` 2.x alpha line because Kotlin 2.3.21 is newer than
Detekt 1.23.x support.

Error Prone is configured only for `JavaCompile` tasks. Current Error Prone versions
require a modern JDK to run; this project compiles with Java 25, so the setup is
compatible. If Error Prone later lags a new JDK release, keep the plugin configured
but document the temporary limitation instead of silently removing it.

Checkstyle, PMD, SpotBugs, and Error Prone may show `NO-SOURCE` or `SKIPPED` while the
repository has no Java source. That is expected and keeps Kotlin-only builds clean.
