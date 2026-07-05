import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask
import net.ltgt.gradle.errorprone.errorprone

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
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
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
                minimum = "1.00".toBigDecimal()
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
    dependsOn("staticAnalysis", "check", "bootJar")
}

tasks.check {
    dependsOn("staticAnalysis")
}
