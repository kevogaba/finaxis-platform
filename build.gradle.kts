import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import net.ltgt.gradle.errorprone.errorprone
import org.flywaydb.core.Flyway
import org.springframework.boot.gradle.tasks.bundling.BootBuildImage
import org.springframework.boot.gradle.tasks.bundling.BootJar

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

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
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
    testImplementation("org.springframework.boot:spring-boot-starter-amqp-test")
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
        )
    }
}

// jOOQ generates sources into this directory at task-execution time (see the EmbeddedPostgres
// block below), but Qodana's own project-model sync runs with an empty task list, and the
// conditional block never registers this as a source root in that case - leaving every jOOQ
// consumer with unresolved-reference sanity findings even though qodana.yaml's bootstrap step has
// already generated the files on disk. Registering the (possibly still-empty) directory
// unconditionally is harmless for Gradle/IDE tooling and fixes that sync gap.
sourceSets.named("main") {
    java.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
    }
}

// jOOQ codegen introspects a live PostgreSQL schema; rather than requiring a pre-migrated local
// database (which breaks clean checkouts and CI), start a disposable, Flyway-migrated, Dockerless
// embedded Postgres here and point codegen at it. It is closed once the jOOQ codegen task, its
// last consumer, finishes executing.
//
// Only do this when Gradle is actually about to run tasks. IDE/tool project-model resolution
// (e.g. IntelliJ Gradle sync, Qodana's static analysis) evaluates this script too, but with an
// empty task list, and Qodana runs it inside its own container with no Docker access - starting
// the container unconditionally broke that sync with "Could not find a valid Docker environment."
if (gradle.startParameter.taskNames.isNotEmpty()) {
    val jooqCodegenDatabase = EmbeddedPostgres.builder().start()
    val jooqCodegenJdbcUrl = jooqCodegenDatabase.getJdbcUrl("postgres", "postgres")

    try {
        Flyway
            .configure()
            .dataSource(jooqCodegenJdbcUrl, "postgres", "")
            .locations(
                "filesystem:${layout.projectDirectory.dir("src/main/resources/db/migration")}",
            ).load()
            .migrate()
    } catch (ex: Exception) {
        jooqCodegenDatabase.close()
        throw ex
    }

    jooq {
        configuration {
            jdbc {
                driver = "org.postgresql.Driver"
                url = jooqCodegenJdbcUrl
                user = "postgres"
                password = ""
            }
            generator {
                name = "org.jooq.codegen.KotlinGenerator"
                database {
                    name = "org.jooq.meta.postgres.PostgresDatabase"
                    inputSchema = "public"
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
        // jOOQ codegen is the last consumer of the live database: it introspects the schema and
        // writes generated .kt sources to disk, so the embedded instance can be closed as soon as
        // this task finishes executing. `gradle.buildFinished` is deprecated, and there is no
        // earlier safe point since codegen itself runs at task-execution time.
        doLast { jooqCodegenDatabase.close() }
    }
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

tasks.withType<Test> {
    useJUnitPlatform()
    // Gradle's worker-daemon default (512m) is too small for Spring Boot + Testcontainers
    // integration tests loading many distinct application contexts; raise it explicitly.
    maxHeapSize = "2g"
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
                // `BPE_APPEND_*` concatenates with no separator unless `BPE_DELIM_*` gives one, and
                // JAVA_TOOL_OPTIONS is space-separated. Without both, the flags below arrive glued
                // to the option before them and the JVM refuses to start:
                // `Unrecognized VM option 'ExitOnOutOfMemoryError-XX:+HeapDump…'`.
                "BPE_DELIM_JAVA_TOOL_OPTIONS" to " ",
                // Heap sizing is left to the buildpack's memory calculator, which derives an
                // explicit -Xmx from the container limit; a MaxRAMPercentage beside it is ignored.
                "BPE_APPEND_JAVA_TOOL_OPTIONS" to
                    "-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError",
            )
        },
    )
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
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

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
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
    dependsOn(tasks.test)
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
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
    dependsOn("staticAnalysis", "check", "jacocoTestCoverageVerification", "bootJar")
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
