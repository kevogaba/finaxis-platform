import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import com.sun.management.OperatingSystemMXBean
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import net.ltgt.gradle.errorprone.errorprone
import org.flywaydb.core.Flyway
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.springframework.boot.gradle.tasks.bundling.BootBuildImage
import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.lang.management.ManagementFactory
import java.net.ServerSocket

// Versions pinned here only for jOOQ codegen bootstrapping; keep aligned with the versions
// resolved for the application's own Flyway/PostgreSQL dependencies below.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(libs.embedded.postgres)
        // Pins the Dockerless embedded Postgres used for jOOQ codegen bootstrapping to the
        // Postgres 18 binaries (library default is 14.22). See
        // https://github.com/zonkyio/embedded-postgres#postgres-version.
        classpath(enforcedPlatform(libs.embedded.postgres.bom))
        classpath(libs.flyway.postgresql)
        classpath(libs.postgresql)
    }
}

plugins {
    jacoco
    checkstyle
    pmd

    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.jooq.codegen)

    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.boot.aot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.graalvm.native)
    alias(libs.plugins.sentry.jvm)
}

group = "com.finaxis"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Mockito's inline mock maker needs an instrumentation agent. The test JVMs load it at start, as
// Mockito documents, rather than letting it attach itself at runtime, which the JDK warns about and
// will refuse by default (JEP 451).
val mockitoAgent: Configuration = configurations.create("mockitoAgent") { isTransitive = false }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp-rabbitmq")
    implementation("org.springframework.boot:spring-boot-starter-rabbitmq")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation(libs.jooq.kotlin)
    implementation(libs.jooq.meta.extensions)
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-freemarker")
    jooqCodegen("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Not in the Boot BOM; version pinned here rather than inherited from
    // spring-cloud-commons, which manages it only as an optional dependency.
    implementation(libs.springRetry)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.redis.common)
    implementation(libs.bucket4j.lettuce)
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.jobrunr.spring.boot)
    implementation(libs.keycloak.admin.client)
    implementation(libs.springdoc.ui)
    implementation(libs.springdoc.api)
    implementation(libs.springdoc.scalar)
    implementation(libs.opentelemetry.logback.appender)
    implementation(platform(libs.sentry.bom))
    implementation("io.sentry:sentry-spring-boot-4-starter")
    implementation("io.sentry:sentry-async-profiler")
    implementation("io.sentry:sentry-jdbc")
    implementation("io.sentry:sentry-kotlin-extensions")
    implementation("io.sentry:sentry-logback")
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-resilience4j")
    implementation("org.springframework.modulith:spring-modulith-events-api")
    implementation("org.springframework.modulith:spring-modulith-observability-api")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")
    implementation("org.springframework.modulith:spring-modulith-starter-namastack")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
    implementation(platform(libs.namastack.bom))
    implementation("io.namastack:namastack-outbox-rabbit")
    implementation("io.namastack:namastack-outbox-observability")
    implementation("io.namastack:namastack-outbox-starter-jdbc")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
    runtimeOnly("org.springframework.modulith:spring-modulith-observability-core")
    runtimeOnly("org.springframework.modulith:spring-modulith-runtime")
    runtimeOnly("org.springframework.modulith:spring-modulith-starter-insight")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-rabbitmq-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation(
        "org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test",
    )
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.jobrunr.core) {
        capabilities {
            requireCapability("org.jobrunr:core-test-fixtures")
        }
    }
    testImplementation("org.testcontainers:testcontainers-grafana")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-rabbitmq")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    mockitoAgent("org.mockito:mockito-core")

    errorprone(libs.errorprone.core)
}

dependencyManagement {
    imports {
        mavenBom(
            "org.springframework.modulith:spring-modulith-bom:${libs.versions.springModulithVersion.get()}",
        )
        mavenBom(
            "org.springframework.cloud:spring-cloud-dependencies:${libs.versions.springCloudVersion.get()}",
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            "-java-parameters", // Retain parameter names for reflection
            "-opt-in=kotlin.uuid.ExperimentalUuidApi",
            // Writes type-use annotations into the bytecode, so Bean Validation sees the element
            // cascade `List<@Valid T>` (RequestBodyValidationArchitectureTest).
            "-Xemit-jvm-type-annotations",
        )
    }
}

// jOOQ generates sources into this directory at task-execution time (see `JooqCodegenDatabase`
// below). Registering the (possibly still-empty) directory as a source root up front lets every
// jOOQ consumer resolve during an IDE or Qodana project-model sync, which runs no tasks, once
// qodana.yaml's bootstrap step has generated the files on disk.
sourceSets.named("main") {
    java.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
    }
}

// jOOQ codegen introspects a live PostgreSQL schema; rather than requiring a pre-migrated local
// database (which breaks clean checkouts and CI), it reads a disposable, Flyway-migrated,
// Dockerless embedded Postgres. The database starts only when `jooqCodegen` actually runs, inside
// this build service: starting a process while Gradle configures the build is deprecated (it fails
// from Gradle 11) and slowed every IDE or Qodana sync. jOOQ reads its JDBC URL from the
// configuration Gradle snapshots before the task runs, so the port is chosen while configuring (a
// socket bound and released, no process) and the database is started on it later. The service
// closes the database as soon as codegen, its only consumer, finishes, and at the latest when the
// build ends (a failed task included).
abstract class JooqCodegenDatabase :
    BuildService<JooqCodegenDatabase.Params>,
    AutoCloseable {
    interface Params : BuildServiceParameters {
        val migrations: DirectoryProperty
        val port: Property<Int>
    }

    private var database: EmbeddedPostgres? = null

    /** Starts the database on the configured port and migrates it; called by `jooqCodegen`. */
    @Synchronized
    fun start() {
        if (database != null) return
        val running = EmbeddedPostgres.builder().setPort(parameters.port.get()).start()
        database = running
        val url = running.getJdbcUrl("postgres", "postgres")
        try {
            Flyway
                .configure()
                // As spring.flyway.postgresql.transactional-lock=false in application.yaml: the
                // CONCURRENTLY index migrations (V25-V28, V30) cannot finish under a
                // transactional lock.
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                .dataSource(url, "postgres", "")
                .locations("filesystem:${parameters.migrations.get().asFile}")
                .load()
                .migrate()
        } catch (ex: Exception) {
            close()
            throw ex
        }
    }

    @Synchronized
    override fun close() {
        database?.close()
        database = null
    }
}

val jooqCodegenPort = ServerSocket(0).use { it.localPort }

val jooqCodegenDatabase =
    gradle.sharedServices.registerIfAbsent("jooqCodegenDatabase", JooqCodegenDatabase::class) {
        parameters.migrations.set(layout.projectDirectory.dir("src/main/resources/db/migration"))
        parameters.port.set(jooqCodegenPort)
    }

jooq {
    configuration {
        jdbc {
            driver = "org.postgresql.Driver"
            url = "jdbc:postgresql://localhost:$jooqCodegenPort/postgres"
            user = "postgres"
            password = ""
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            // jOOQ names a key's join paths after the table they lead to, so a self-referencing
            // key, or two keys between the same tables, gives two paths one name and codegen warns
            // "Ambiguous key name". Name those inverse (one-to-many) paths explicitly instead. No
            // code navigates them; they only stop the collision.
            strategy {
                matchers {
                    foreignKeys {
                        foreignKey {
                            expression = "fk_branch_parent_same_organisation"
                            pathMethodNameInverse { expression = "childBranches" }
                        }
                        foreignKey {
                            expression = "fk_gl_account_parent_same_organisation"
                            pathMethodNameInverse { expression = "childGlAccounts" }
                        }
                        foreignKey {
                            expression = "fk_journal_entry_reverses"
                            pathMethodNameInverse { expression = "reversingJournalEntries" }
                        }
                        foreignKey {
                            expression = "fk_posting_request_corrects"
                            pathMethodNameInverse { expression = "correctingPostingRequests" }
                        }
                        foreignKey {
                            expression = "permission_view_requirement_permission_id_fkey"
                            pathMethodNameInverse { expression = "viewRequirements" }
                        }
                        foreignKey {
                            expression = ".*_required_view_permission_id_fkey"
                            pathMethodNameInverse { expression = "requiredByPermissions" }
                        }
                    }
                }
            }
            database {
                name = "org.jooq.meta.postgres.PostgresDatabase"
                inputSchema = "public"
            }
            generate {
                // The two keys of permission_view_requirement would also give permission two
                // identically named many-to-many paths; nothing uses many-to-many paths.
                isImplicitJoinPathsManyToMany = false
            }
            target {
                packageName = "com.finaxis.platform.jooq"
                directory =
                    layout.buildDirectory
                        .dir("generated-src/jooq/main")
                        .get()
                        .asFile.absolutePath
            }
        }
    }
}

tasks.named("compileKotlin") {
    dependsOn(tasks.named("jooqCodegen"))
}

tasks.named("generateSentryBundleIdJava") {
    // Make the task run after the tasks that generate code during build
    dependsOn("jooqCodegen")
}

tasks.named("jooqCodegen") {
    inputs.files(fileTree("src/main/resources/db/migration"))
    usesService(jooqCodegenDatabase)
    doFirst { jooqCodegenDatabase.get().start() }
    doLast { jooqCodegenDatabase.get().close() }
}

detekt {
    buildUponDefaultConfig = true
    allRules = true
    config.setFrom(files("config/detekt/detekt.yml"))
    parallel = true
    ignoreFailures = false
    basePath.set(rootProject.layout.projectDirectory)
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    jvmTarget = "25"
}

configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(
                dev.detekt.gradle.plugin
                    .getSupportedKotlinVersion(),
            )
        }
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        targetExclude("build/**")
        ktlint(libs.versions.ktlintVersion.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_code_style" to "ktlint_official",
                    "max_line_length" to "100",
                    "ij_kotlin_allow_trailing_comma" to "true",
                    "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
                ),
            )
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "gradle/**/*.gradle.kts")
        ktlint(libs.versions.ktlintVersion.get())
        trimTrailingWhitespace()
        endWithNewline()
    }
    java {
        target("src/**/*.java")
        targetExclude("build/**")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target(
            "*.md",
            "docs/**/*.md",
            "*.yml",
            "*.yaml",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            "src/main/resources/**/*.yml",
            "src/main/resources/**/*.yaml",
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyleVersion.get()
    configDirectory = file("config/checkstyle")
    isIgnoreFailures = false
    maxErrors = 0
    maxWarnings = 0
}

pmd {
    toolVersion = libs.versions.pmdVersion.get()
    isConsoleOutput = true
    isIgnoreFailures = false
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = emptyList()
}

spotbugs {
    toolVersion = libs.versions.spotbugsVersion.get()
    effort = Effort.MAX
    reportLevel = Confidence.HIGH
    excludeFilter = file("config/spotbugs/exclude.xml")
    ignoreFailures = false
}

tasks.withType<Checkstyle>().configureEach {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.withType<Pmd>().configureEach {
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.withType<SpotBugsTask>().configureEach {
    reports {
        maybeCreate("xml").required = true
        maybeCreate("html").required = true
    }
}

tasks.named<SpotBugsTask>("spotbugsMain") {
    dependsOn(
        "compileJava",
        "collectExternalDependenciesForSentry",
        "generateSentryDebugMetaPropertiesjava",
    )
    onlyIf {
        sourceSets.main
            .get()
            .allJava.files
            .isNotEmpty()
    }
    classes = files(layout.buildDirectory.dir("classes/java/main"))
}

tasks.named<SpotBugsTask>("spotbugsTest") {
    onlyIf {
        sourceSets.test
            .get()
            .allJava.files
            .isNotEmpty()
    }
    classes = files(layout.buildDirectory.dir("classes/java/test"))
}

// Spring AOT writes generated Java sources into the `aot` and `aotTest` source sets. They are
// framework output the project cannot edit, and they inherit raw-type and unchecked warnings from
// the framework signatures they call, which the repository-wide `-Werror` policy turns into a
// failed `compileAotJava`. Keep the strict policy on hand-written sources and compile the
// generated ones without lint or Error Prone.
val generatedAotCompileTaskNames = setOf("compileAotJava", "compileAotTestJava")

// The Checkstyle, PMD and SpotBugs plugins add a task per source set, so the `aot` and `aotTest`
// source sets get their own - and those grade the same generated code. `checkstyleAot` alone
// reports thousands of violations against files the project cannot edit, which fails `check` for
// anyone who has run a native build. Switch them off for the same reason `compileAotJava` compiles
// without lint; the analysis of hand-written sources is untouched.
val generatedAotAnalysisTaskNames =
    setOf(
        "checkstyleAot",
        "checkstyleAotTest",
        "pmdAot",
        "pmdAotTest",
        "spotbugsAot",
        "spotbugsAotTest",
    )

tasks.matching { it.name in generatedAotAnalysisTaskNames }.configureEach {
    enabled = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (name in generatedAotCompileTaskNames) {
        options.compilerArgs.add("-Xlint:none")
        // Called on the property rather than assigned inside an `errorprone { }` block:
        // `ErrorProneOptions` exposes `enabled`, so an `isEnabled = false` there silently resolves
        // against the enclosing `JavaCompile` and disables the compile task itself, which leaves
        // the native image without its AOT initializer.
        options.errorprone.enabled.set(false)
    } else {
        options.compilerArgs.addAll(
            listOf(
                "-Xlint:all",
                "-Werror",
            ),
        )
        options.errorprone {
            disableWarningsInGeneratedCode = true
            excludedPaths = ".*/build/.*|.*/generated/.*"
        }
    }
}

// The default number of test JVMs, sized to the machine rather than fixed. A fork pays off only
// with about two cores of its own, and needs about 4 GB: a 2 GB heap plus metaspace, and the
// PostgreSQL, RabbitMQ and LGTM servers it starts. On the 4-vCPU, 16 GB CI runner that is two,
// which took about 30% off the suite while three were barely faster and saturated the CPU. On a
// 2-vCPU, 8 GB runner it is one: two forks there were killed by the kernel's out-of-memory killer
// (docs/development/test-performance.md). Gradle and the OS keep about 2 GB. The cap of four is a
// judgement, not a measurement: each fork boots the shared contexts again.
val defaultTestForks: Int =
    run {
        val cores = Runtime.getRuntime().availableProcessors()
        val memoryGb =
            (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean)
                .totalMemorySize / (1024L * 1024 * 1024)
        minOf(cores / 2, ((memoryGb - 2) / 4).toInt(), 4).coerceAtLeast(1)
    }

tasks.withType<Test> {
    useJUnitPlatform()
    // Gradle's worker-daemon default (512m) is too small for Spring Boot + Testcontainers
    // integration tests loading many distinct application contexts; raise it explicitly.
    maxHeapSize = "2g"
    // Test classes are spread over parallel JVMs. Each fork has its own Spring context cache and
    // its own PostgreSQL, RabbitMQ and LGTM servers (SharedTestContainers), so forks never see each
    // other's data; the cost is that a context used by classes in both forks boots in both.
    // `-PtestForks=N` overrides the default, and `-PtestForks=1` restores a single JVM, for example
    // to debug an ordering-dependent failure.
    maxParallelForks =
        providers.gradleProperty("testForks").map(String::toInt).getOrElse(defaultTestForks)
    val agent: FileCollection = mockitoAgent
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-javaagent:${agent.singleFile}") },
    )
    // Mockito's inline mock maker appends a jar to the boot class path at its first mock, after
    // which the JVM can share only boot classes and warns about it. Test JVMs therefore run
    // without class-data sharing, a start-up optimisation only; deployed JVMs keep it.
    jvmArgs("-Xshare:off")
    // Testcontainers' Docker client (JNA) and the AMQP 1.0 client (Netty) load native libraries
    // through restricted methods, which JDK 25 allows but warns about unless native access is
    // granted; every JVM this build starts or packages grants it (see the bootJar manifest).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Spring AOT is mandatory for everything this repository ships. Both deployable images run their
// bean definitions from AOT output, and a native image cannot be built without it: `processAot`
// generates the bean definitions, proxies and reachability metadata that `native-image` compiles
// against. It is therefore switched on unconditionally for `bootBuildImage` and the native tasks,
// and `-PenableAot=true` adds it to a plain `bootJar`. Only `bootJar` and `qualityGate`, which
// produce nothing that is deployed, run without it, because refreshing the context at build time
// costs about a minute.
val aotRequiredTaskNames = setOf("bootBuildImage", "nativeBuild", "nativeCompile", "nativeRun")

val aotEnabled =
    gradle.startParameter.taskNames.any { it.substringAfterLast(':') in aotRequiredTaskNames } ||
        providers.gradleProperty("enableAot").map(String::toBoolean).getOrElse(false)

// Which of the two images `bootBuildImage` produces. Native by default; set
// FINAXIS_NATIVE_IMAGE=false (or `-PnativeImage=false`) for the JVM image. Both are AOT-processed
// and both are deployable, so a deployment can publish the pair and choose between them at run
// time - the native image for start-up latency and footprint, the JVM one where the runtime
// features below matter more.
val nativeImageEnabled =
    providers
        .gradleProperty("nativeImage")
        .orElse(providers.environmentVariable("FINAXIS_NATIVE_IMAGE"))
        .map(String::toBoolean)
        .getOrElse(true)

// Spring Modulith's runtime support is the one capability a native image cannot keep, and it is
// dropped from that build alone - the JVM image and every JVM run keep it. Nothing load-bearing
// goes with it: the outbox, its RabbitMQ publishing and its retries, and the Modulith event
// externalization behind them all keep working. It is the main reason to keep the JVM image
// buildable.
//
// Spring Modulith's runtime support bootstraps `ApplicationModules` by having ArchUnit import the
// application packages from the classpath. A native image has no class files to import and no
// dynamic plugin loading, so `ApplicationModulesRuntime` fails on a missing
// `com.tngtech.archunit.core.importer.ModuleImportPlugin` - see
// https://github.com/spring-projects/spring-modulith/issues/735. Its beans are lazy, but the
// observability post-processor and the startup module verifier both force them. Dropping
// `spring-modulith-runtime`, `-actuator` and `-observability-core` costs the `/actuator/modulith`
// endpoint - which `management.endpoints.web.exposure.include` does not expose anyway - and
// per-module spans. This application declares no `ApplicationModuleInitializer` beans, the other
// thing that support drives, and module structure is still verified by the test suite.
//
// There is no way to keep it: `ApplicationModulesFactory.defaultFactory()` is hardwired to the
// ArchUnit-backed `ApplicationModules::of`, and no Modulith artefact ships a factory that reads the
// `application-modules.json` its own AOT processor generates. Recovering it would mean embedding
// every application class file in the image as a resource for ArchUnit to import, to get back an
// endpoint `management.endpoints.web.exposure.include` does not expose and per-module spans.
//
// `processAotClasspath` is resolved independently of `runtimeClasspath` and does not inherit its
// exclude rules, so all three classpaths have to be named: `processAot` decides which bean
// definitions are generated, `nativeImageClasspath` is what `nativeCompile` compiles, and
// `runtimeClasspath` is what ends up in the jar `bootBuildImage` hands to the buildpack.
val nativeExcludedConfigurationNames =
    setOf("nativeImageClasspath", "processAotClasspath", "runtimeClasspath")

// Guarded on the task graph, not on `nativeImageEnabled` alone, and the difference is a real bug
// rather than a nicety. `nativeImageEnabled` defaults to true, so keying the exclusion off it
// stripped these three artefacts from `runtimeClasspath` for *every* invocation - `bootRun`,
// `bootJar`, `qualityGate` - contradicting the comment directly above, which promises that every
// JVM run keeps them. Only a build that actually produces a native image may drop them:
// `nativeCompile`/`nativeBuild`/`nativeRun` always do, and `bootBuildImage` does only when the
// native variant is selected. A JVM `bootBuildImage`, and an AOT-processed plain `bootJar` via
// `-PenableAot=true`, both keep Modulith.
val nativeOnlyTaskNames = setOf("nativeBuild", "nativeCompile", "nativeRun")

val requestedTaskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }

val buildsNativeImage =
    requestedTaskNames.any { it in nativeOnlyTaskNames } ||
        (requestedTaskNames.contains("bootBuildImage") && nativeImageEnabled)

if (buildsNativeImage) {
    configurations.matching { it.name in nativeExcludedConfigurationNames }.configureEach {
        exclude(group = "org.springframework.modulith", module = "spring-modulith-actuator")
        exclude(
            group = "org.springframework.modulith",
            module = "spring-modulith-observability-core",
        )
        exclude(group = "org.springframework.modulith", module = "spring-modulith-runtime")
    }
}

tasks.named<BootJar>("bootJar") {
    layered {
        enabled.set(System.getenv("ENABLE_LAYERED_JAR")?.toBoolean() ?: false)
    }
    // Netty, under RabbitMQ's AMQP 1.0 client, loads its native transport through a restricted
    // method; JDK 25 allows it but warns unless native access is granted. This covers `java -jar`;
    // the JVM image's launcher reads the flag from JAVA_TOOL_OPTIONS below instead, and bootRun
    // and the test JVMs pass it on their command lines.
    manifest.attributes("Enable-Native-Access" to "ALL-UNNAMED")
    // The Spring Boot plugin puts the `aot` source set on this jar's classpath unconditionally, and
    // Gradle does not clean the output of a task it skipped - so an AOT-free build after an
    // AOT-enabled one packages the previous run's bean definitions. That is not merely untidy: the
    // Modulith module descriptor is written into both the AOT resources and, by the test suite,
    // into the main ones, and `bootJar` then fails on the duplicate. An AOT-free jar carries no AOT
    // output.
    if (!aotEnabled) {
        val aotOutput = sourceSets.named("aot").get().output
        val aotOutputDirs = aotOutput.classesDirs.files + setOfNotNull(aotOutput.resourcesDir)
        classpath?.let { current ->
            setClasspath(current.filter { file -> file !in aotOutputDirs })
        }
    }
    // Spring Modulith writes its module descriptor into the main resources *output* when the test
    // suite builds the module structure. It is not a source resource, it is a different and much
    // larger document than the one `processAot` generates for the runtime, and having both on the
    // classpath fails this task on a duplicate entry. Dropping the test-written copy keeps the jar
    // identical whether or not tests have run.
    val mainResourcesDir =
        sourceSets
            .named("main")
            .get()
            .output.resourcesDir
    filesMatching("**/META-INF/spring-modulith/application-modules.json") {
        if (mainResourcesDir != null && file.startsWith(mainResourcesDir)) {
            exclude()
        }
    }
    // The Spring Boot plugin stamps this entry on every bootJar as soon as the GraalVM plugin is
    // applied, and Paketo's spring-boot buildpack turns its presence alone into a native-image
    // build plan. It therefore has to mean what it says: dropped when AOT did not run, and dropped
    // for the JVM image, which is AOT-processed but must not be compiled to a binary. The manifest
    // is materialised by the task action, so removing it here still lands before the archive is
    // written.
    doFirst {
        if (!aotEnabled || !nativeImageEnabled) {
            manifest.attributes.remove("Spring-Boot-Native-Processed")
        }
    }
}

tasks.named<BootBuildImage>("bootBuildImage") {
    // The two images have to be separately addressable for a deployment to publish both and choose
    // at run time, so the JVM one carries a `-jvm` tag. FINAXIS_IMAGE_NAME overrides the whole
    // reference for a registry that names things differently.
    //
    // The namespace is the GitHub account that owns this repository, not a project name: GHCR
    // scopes every package to an owner and refuses a push to any other namespace.
    val imageTagSuffix = if (nativeImageEnabled) "" else "-jvm"
    imageName =
        System.getenv("FINAXIS_IMAGE_NAME")?.takeIf(String::isNotBlank)
            ?: "ghcr.io/kevogaba/finaxis-platform:${project.version}$imageTagSuffix"
    // The buildpacks download the Liberica NIK toolchain from inside the build container. Behind a
    // TLS-inspecting egress proxy those downloads are re-signed with a private CA the container
    // does not trust, and the build fails on certificate verification. Point
    // FINAXIS_BUILD_CA_BUNDLE at a PEM bundle to mount over the container's trust store.
    System.getenv("FINAXIS_BUILD_CA_BUNDLE")?.takeIf(String::isNotBlank)?.let { caBundle ->
        bindings.add("$caBundle:/etc/ssl/certs/ca-certificates.crt:ro")
    }
    // Spring Boot 4.2 moved the default builder to paketobuildpacks/ubuntu-resolute-builder
    // (Ubuntu 26.04). Pinned to the one 4.1 used, so the upgrade leaves the image's operating
    // system where it was; moving to 26.04 is a change of its own.
    builder = "paketobuildpacks/builder-noble-java-tiny"
    // `environment.set` replaces the map wholesale, so each branch has to be complete. Both run
    // Spring AOT; what differs is what consumes it. A native image has no JVM, so the HotSpot flags
    // in JAVA_TOOL_OPTIONS would go unread there, while the JVM image has to be told to use the AOT
    // bean definitions it ships - `BP_SPRING_AOT_ENABLED` is what adds `-Dspring.aot.enabled=true`
    // to its launcher.
    environment.set(
        if (nativeImageEnabled) {
            buildMap {
                // Selects the Liberica NIK major version the buildpack compiles with; the AOT jar
                // is Java 25 bytecode, so an older default would not read it.
                put("BP_JVM_VERSION", "25.*")
                put("BP_NATIVE_IMAGE", "true")
                put("BP_SPRING_CLOUD_BINDINGS_DISABLED", "true")
                // Extra `native-image` arguments, for callers that have to build somewhere
                // smaller than a developer machine. CI passes `-Ob` here: a two-core runner with
                // 8 GB cannot finish the optimising build, and quick-build mode still exercises
                // the whole reachability analysis - which is the part that actually breaks - for
                // a fraction of the memory. Left unset locally, so a developer still gets the
                // optimised image the deployment would ship.
                System.getenv("FINAXIS_NATIVE_BUILD_ARGS")?.takeIf(String::isNotBlank)?.let {
                    put("BP_NATIVE_IMAGE_BUILD_ARGUMENTS", it)
                }
            }
        } else {
            mapOf(
                "BP_JVM_VERSION" to "25.*",
                "BP_SPRING_AOT_ENABLED" to "true",
                // The Paketo Spring Boot buildpack adds Spring Cloud Bindings, which fails Spring
                // Boot 4.2 at startup (EnvironmentPostProcessor moved package); the 4.2 release
                // notes say to disable it until the buildpack or the library catches up.
                "BP_SPRING_CLOUD_BINDINGS_DISABLED" to "true",
                // `BPE_APPEND_*` concatenates with no separator unless `BPE_DELIM_*` gives one, and
                // JAVA_TOOL_OPTIONS is space-separated. Without both, the flags below arrive glued
                // to the option before them and the JVM refuses to start:
                // `Unrecognized VM option 'ExitOnOutOfMemoryError-XX:+HeapDump…'`.
                "BPE_DELIM_JAVA_TOOL_OPTIONS" to " ",
                // Heap sizing is left to the buildpack's memory calculator, which derives an
                // explicit -Xmx from the container limit; a MaxRAMPercentage beside it is ignored.
                // Native access for Netty under the AMQP 1.0 client, as the bootJar manifest grants
                // it to `java -jar`, which this launcher is not.
                "BPE_APPEND_JAVA_TOOL_OPTIONS" to
                    "-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError " +
                    "--enable-native-access=ALL-UNNAMED",
            )
        },
    )
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

// CI spreads the suite over several runners: `-PtestShard=i/n` runs only the i-th of n disjoint
// slices of the test classes. A class belongs to the slice its top-level class name hashes to, so
// a class and its nested classes always run together, every class lands in exactly one slice, and
// the split needs no list to maintain. The slices' JaCoCo data is merged and verified afterwards
// (`shardCoverage` below). Without the property every class runs, as `qualityGate` does locally.
providers.gradleProperty("testShard").orNull?.let { shard ->
    val (index, count) =
        shard.split('/').mapNotNull(String::toIntOrNull).takeIf { it.size == 2 }
            ?: error("testShard must be i/n, for example 1/2, not '$shard'")
    require(count >= 1 && index in 1..count) { "testShard needs 1 <= i <= n, not '$shard'" }
    tasks.test {
        exclude { element ->
            val path = element.relativePath.pathString
            val topLevelClass = path.removeSuffix(".class").substringBefore('$')
            !element.isDirectory &&
                path.endsWith(".class") &&
                Math.floorMod(topLevelClass.hashCode(), count) != index - 1
        }
    }
}

// CI's Quality Gate job verifies coverage without running a test: `-PshardCoverage=<dir>` reads the
// test shards' execution data (`<dir>/*.exec`) and the main classes they ran against
// (`<dir>/classes`, copied from a shard so the class ids match the data), and the coverage tasks
// then do not depend on `test`. The rules and the class filters are the same in both modes.
val shardCoverage: File? = providers.gradleProperty("shardCoverage").orNull?.let(::file)

/** The main classes coverage is measured over: compiled here, or copied from a test shard. */
val coverageClassRoots: Set<File> =
    shardCoverage?.let { root ->
        listOf("java", "kotlin").map { root.resolve("classes/$it/main") }.toSet()
    } ?: sourceSets.main
        .get()
        .output.classesDirs.files

/** Applies the merge mode to a coverage task: the shards' data, and no test run of its own. */
fun JacocoReportBase.coverageInputs() {
    val root = shardCoverage
    if (root == null) {
        dependsOn(tasks.test)
        return
    }
    executionData.setFrom(fileTree(root) { include("*.exec") })
    // JaCoCo skips a task whose execution data is missing, and a skipped verification reads as a
    // pass. Merging, missing data or classes is a broken hand-over, so the task runs and fails.
    setOnlyIf("merging test shards, missing coverage data fails the task") { true }
    doFirst {
        check(!executionData.asFileTree.isEmpty) { "no *.exec under $root" }
        check(!classDirectories.asFileTree.isEmpty) { "no classes under $root/classes" }
    }
}

jacoco {
    toolVersion = "0.8.15"
}

val coverageExclusions =
    listOf(
        "com/finaxis/platform/PlatformApplication*",
        "com/finaxis/platform/config/**",
        "com/finaxis/platform/iam/domain/**",
        "com/finaxis/platform/iam/persistence/**",
        "com/finaxis/platform/iam/security/SecurityConfiguration*",
        "com/finaxis/platform/iam/adapter/outbound/persistence/**",
        "com/finaxis/platform/iam/adapter/inbound/security/SecurityConfiguration*",
        "com/finaxis/platform/iam/adapter/inbound/web/AuthController*",
        "com/finaxis/platform/iam/adapter/inbound/web/*Request*",
        "com/finaxis/platform/iam/adapter/inbound/web/*Response*",
        "com/finaxis/platform/iam/adapter/inbound/web/ApiError*",
        "com/finaxis/platform/iam/application/port/**",
        "com/finaxis/platform/iam/application/context/AppPrincipal*",
        "**/SecurityConfiguration*",
        "**/AuthController*",
        "**/*Request*",
        "**/*Response*",
        "**/ApiError*",
    )

// What the accounting rule excludes, which is not what the platform-wide report excludes.
//
// The shared list ends with blanket glob patterns for Request, Response and ApiError classes.
// They exist to keep web DTOs - data carriers with no behaviour - out of a ratio, and for IAM
// they do exactly that. Applied to accounting they also remove RequestContextAccountingLookup,
// an outbound adapter that resolves tenant and actor context and raises errors when it cannot:
// real behaviour, and behaviour a change could break while this task went on reporting the same
// number.
//
// Accounting has no web adapters yet - #52 introduces them - so those blanket patterns buy
// nothing here and cost a live class. The IAM-scoped entries stay, because they are scoped by
// path and cannot reach into accounting.
val accountingCoverageExclusions =
    coverageExclusions.filterNot { it in setOf("**/*Request*", "**/*Response*", "**/ApiError*") }

tasks.jacocoTestReport {
    coverageInputs()
    classDirectories.setFrom(
        files(
            coverageClassRoots.map {
                fileTree(it) {
                    exclude(coverageExclusions)
                }
            },
        ),
    )
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    coverageInputs()
    classDirectories.setFrom(
        files(
            coverageClassRoots.map {
                fileTree(it) {
                    // The repository's ratcheted coverage contract is the IAM implementation.
                    // Generated jOOQ records and other infrastructure remain visible in the report,
                    // but are not application behavior for this focused verification rule.
                    include("com/finaxis/platform/iam/**")
                    exclude(coverageExclusions)
                }
            },
        ),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Matches the documented IAM coverage contract in
                // docs/development/static-analysis.md; keep the two in sync.
                minimum = "0.95".toBigDecimal()
            }
        }
    }
}

// The accounting module's own coverage floor.
//
// A second task rather than a second rule on the first, because a JaCoCo BUNDLE rule is named after
// the project and so cannot be scoped by package: the only way to hold two modules to two floors is
// two verifications over two sets of class directories.
//
// It is here because accounting is the financial system of record, and because coverage earned its
// place empirically rather than as a target. Issue #50's StatementWindowPolicy shipped with
// documentation promising it validated every statement request, and nothing called it - the guard
// was unreachable. What surfaced it was the package sitting at 84% while the module sat at 96%,
// which is a number somebody looks at. A floor per module keeps the newest code from being the
// weakest thing in it while the aggregate still passes.
tasks.register<JacocoCoverageVerification>("jacocoAccountingCoverageVerification") {
    group = "verification"
    description = "Verifies the accounting module's line coverage against its documented floor."
    executionData.setFrom(
        tasks.test.map { it.extensions.getByType<JacocoTaskExtension>().destinationFile!! },
    )
    coverageInputs()
    sourceDirectories.setFrom(files("src/main/kotlin"))
    // The main source set's own output (or, merging shards, the classes they ran), not the report
    // task's remapped `classDirectories`: that property has already been replaced by a set of
    // individual class FILES, and `fileTree()` of a file is empty - which makes the rule pass over
    // nothing at all. Caught by raising the floor to 99% and watching it still pass.
    classDirectories.setFrom(
        files(
            coverageClassRoots
                .map {
                    fileTree(it) {
                        // Accounting is the financial system of record: its application
                        // services, domain policies and persistence adapters are all behaviour
                        // worth holding to a floor. Generated jOOQ stays out through the shared
                        // exclusions.
                        include("com/finaxis/platform/accounting/**")
                        exclude(accountingCoverageExclusions)
                    }
                },
        ),
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Matches the documented accounting coverage contract in
                // docs/development/static-analysis.md; keep the two in sync.
                minimum = "0.95".toBigDecimal()
            }
        }
        // #143: a BUNDLE rule (the one above) computes a single ratio across every included
        // class, so a small untested package can be diluted by a healthy module average and
        // stay invisible to the gate - which is exactly what happened with
        // StatementWindowPolicy during the #136 review: it sat at 84% while the module sat at
        // 96%, and only reading the per-package HTML report surfaced it, not this task.
        //
        // JaCoCo lets one violationRules block hold several `rule { }` entries, each with its
        // own `element`; a PACKAGE-scoped rule applies the same `classDirectories` this task
        // already narrowed to `com.finaxis.platform.accounting.**`, but evaluates the ratio
        // once per package instead of once for the whole set, so a thin package can no longer
        // hide behind the aggregate.
        rule {
            element = "PACKAGE"
            // JaCoCo's WildcardMatcher requires the literal '.' immediately before a trailing
            // '*', so "com.finaxis.platform.accounting.*" matches every SUB-package but not the
            // bare "com.finaxis.platform.accounting" package itself - and that top-level package
            // is real and populated (AccountingPermissionGuard.kt, AccountingDayRollover.kt,
            // SubledgerProofProvider.kt, and others). Both entries are needed so the rule
            // actually covers every package under the accounting tree, not just its children.
            includes =
                listOf(
                    "com.finaxis.platform.accounting",
                    "com.finaxis.platform.accounting.*",
                )
            // Populate with dot-separated package-name patterns (JaCoCo's PACKAGE-rule syntax,
            // not the file-path globs `exclude()` uses above) only for a package that earns the
            // exemption documented in docs/development/static-analysis.md: one holding
            // exclusively data classes, sealed hierarchies, or other value objects with no
            // executable branch worth covering. Empty until a real per-package report names one.
            excludes = listOf()
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // PLACEHOLDER - calibrate against a real per-package coverage report before
                // merging. Do not raise this without first running
                // ./gradlew jacocoAccountingCoverageVerification and reading the generated
                // report to see the actual current per-package spread.
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.matching { it.name == "processTestAot" }.configureEach {
    enabled = false
}

tasks.matching { it.name == "processAot" }.configureEach {
    enabled = aotEnabled
}

// `processAot` writes the `aot` sources but `compileAotJava` compiles whatever is in that source
// set, so a disabled `processAot` still leaves the previous run's output to be compiled. That is
// not hypothetical: the two image variants generate different bean definitions - the JVM one keeps
// Spring Modulith's runtime support - so a `qualityGate` after building the other variant compiled
// stale sources against a classpath that no longer had their types. The compile step follows the
// generation step instead.
tasks.matching { it.name in generatedAotCompileTaskNames }.configureEach {
    enabled = aotEnabled
}

tasks.register("ktlintCheck") {
    group = "verification"
    description = "Runs ktlint through Spotless for Kotlin source and Gradle Kotlin DSL files."
    dependsOn("spotlessKotlinCheck", "spotlessKotlinGradleCheck")
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs formatting, Kotlin, Java, bytecode, and architecture static-analysis gates."
    dependsOn(
        "spotlessCheck",
        "ktlintCheck",
        "detekt",
        "checkstyleMain",
        "checkstyleTest",
        "pmdMain",
        "pmdTest",
        "spotbugsMain",
        "spotbugsTest",
    )
}

tasks.register("qualityGate") {
    group = "verification"
    description =
        "Runs the complete local quality gate, including static analysis, tests, coverage, and bootJar."
    dependsOn(
        "staticAnalysis",
        "check",
        "jacocoTestCoverageVerification",
        "jacocoAccountingCoverageVerification",
        "bootJar",
    )
}

tasks.check {
    dependsOn("staticAnalysis")
}

sentry {
    org.set(System.getenv("SENTRY_ORG"))
    projectName.set(System.getenv("SENTRY_PROJECT"))
    authToken.set(System.getenv("SENTRY_AUTH_TOKEN"))
    // Enables more detailed log output, e.g. for sentry-cli.
    debug.set(false)
    // Generates a source bundle and uploads it to Sentry.
    includeNativeSources.set(true)
    includeSourceContext.set(true)
    autoUploadNativeSymbols.set(true)
    // Disables or enables dependencies metadata reporting for Sentry.
    includeDependenciesReport.set(true)
    // Enable or disable the tracing instrumentation. Does auto instrumentation for specified
    // features through bytecode manipulation.
    tracingInstrumentation {
        enabled.set(true)
        excludes.set(emptySet())
    }
    // Automatically adds Sentry dependencies to your project.
    autoInstallation {
        enabled.set(true)
    }
    telemetry.set(false)
}
