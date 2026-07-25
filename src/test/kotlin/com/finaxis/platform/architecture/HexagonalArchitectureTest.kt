package com.finaxis.platform.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

private const val BASE_PACKAGE = "com.finaxis.platform"

/**
 * Project-specific architecture rules that keep adapters, application services, and domain
 * model separated as the modular monolith grows.
 */
class HexagonalArchitectureTest {
    private val importedClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(BASE_PACKAGE)

    @Test
    fun `inbound adapters do not depend on persistence adapters`() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.inbound..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter.outbound.persistence..")
            .check(importedClasses)
    }

    @Test
    fun `domain does not depend on adapters or configuration`() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter..", "..config..")
            .check(importedClasses)
    }

    @Test
    fun `application services do not depend on inbound web adapters`() {
        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter.inbound.web..", "..adapter.inbound.security..")
            .check(importedClasses)
    }

    @Test
    fun `persistence adapters do not depend on inbound adapters`() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.outbound..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..adapter.inbound..")
            .check(importedClasses)
    }

    @Test
    fun `core layers do not depend on generated jooq schema metadata`() {
        noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..application..", "..adapter.inbound..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.finaxis.platform.jooq..")
            .check(importedClasses)
    }

    @Test
    fun `web adapters do not depend directly on Spring Data repositories`() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.inbound.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.springframework.data.repository..")
            .check(importedClasses)
    }

    @Test
    fun `web adapters do not depend on outbox RabbitMQ JobRunr or Keycloak infrastructure`() {
        noClasses()
            .that()
            .resideInAPackage("..adapter.inbound.web..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.amqp..",
                "io.namastack.outbox..",
                "org.jobrunr..",
                "org.keycloak..",
            ).check(importedClasses)
    }

    @Test
    fun `web adapters only depend on approved layers and infrastructure`() {
        classes()
            .that()
            .resideInAPackage("..adapter.inbound.web..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "..adapter.inbound.web..",
                "..application..",
                "..domain..",
                "com.finaxis.platform.common..",
                "com.finaxis.platform.lifecycle",
                "java..",
                "javax..",
                "jakarta..",
                "kotlin..",
                "com.fasterxml.jackson.annotation..",
                "org.springframework..",
                "org.jetbrains.annotations..",
                "io.swagger..",
                "tools.jackson..",
            ).check(importedClasses)
    }

    @Test
    fun `top-level application packages are cycle free`() {
        slices()
            .matching("$BASE_PACKAGE.(*)..")
            .should()
            .beFreeOfCycles()
            .check(importedClasses)
    }
}
