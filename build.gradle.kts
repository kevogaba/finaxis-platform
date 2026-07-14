import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import io.sentry.android.gradle.extensions.InstrumentationFeature
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import net.ltgt.gradle.errorprone.errorprone
import org.flywaydb.core.Flyway
import java.util.EnumSet

// Versions pinned here only for jOOQ codegen bootstrapping; keep aligned with the versions
// resolved for the application's own Flyway/PostgreSQL dependencies below.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("io.zonky.test:embedded-postgres:2.1.0")
        classpath("org.flywaydb:flyway-database-postgresql:12.4.0")
        classpath("org.postgresql:postgresql:42.7.11")
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
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation(libs.jooq.kotlin)
    implementation(libs.jooq.meta.extensions)
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
        )
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

    sourceSets.named("main") {
        java.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
    }

    kotlin {
        sourceSets.named("main") {
            kotlin.srcDir(layout.buildDirectory.dir("generated-src/jooq/main"))
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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
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

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.14"
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
    enabled = providers.gradleProperty("enableAot").map(String::toBoolean).getOrElse(false)
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
