package com.finaxis.platform.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Focused module-coupling rules for production packages that must stay independent of framework,
 * adapter, and cross-module internals as the foundation grows.
 */
class ModuleDependencyRuleTests {
    private val productionClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(BASE_PACKAGE)

    @Test
    fun `domain packages do not depend on adapter or framework infrastructure`() {
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform..domain..")
            .and()
            // package-info carries Spring Modulith's @NamedInterface, which declares the module
            // boundary rather than introducing a runtime dependency from domain code.
            .haveSimpleNameNotEndingWith("package-info")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform..adapter..",
                "org.springframework..",
                "org.jooq..",
                "io.namastack..",
            ).check(productionClasses)
    }

    @Test
    fun `notifications module does not reach back into lifecycle or iam internals`() {
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform.notifications..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform.lifecycle..",
                "com.finaxis.platform.iam..",
            ).check(productionClasses)
    }

    @Test
    fun `common audit does not depend on any domain module`() {
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform.common.audit..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform.accounting..",
                "com.finaxis.platform.iam..",
                "com.finaxis.platform.lifecycle..",
                "com.finaxis.platform.notifications..",
            ).check(productionClasses)
    }

    @Test
    fun `no production code publishes directly through RabbitTemplate`() {
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.amqp.rabbit.core.RabbitTemplate")
            .because(
                "integration events are externalized only through transition event factories and " +
                    "the Namastack outbox; see " +
                    "docs/adr/0008-transactional-outbox-over-direct-amqp.md",
            ).check(productionClasses)
    }

    @Test
    fun `only messaging adapters depend on AMQP`() {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "com.finaxis.platform..adapter.inbound.messaging..",
                "com.finaxis.platform..adapter.outbound.messaging..",
                "com.finaxis.platform.common.transitions..",
            ).should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.amqp..")
            .because(
                "broker types belong at the messaging edge; application and domain code " +
                    "publishes through the outbox and never speaks AMQP",
            ).check(productionClasses)
    }

    private companion object {
        const val BASE_PACKAGE = "com.finaxis.platform"
    }
}
