package com.finaxis.platform.architecture

import com.finaxis.platform.lifecycle.application.BusinessDateAutoAdvance
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * `BusinessDateService.advanceAsSystem` asks no actor permission (#277, ADR 0041): its authority
 * is the tenant's opt-in, which only the scheduled advance reads. So only that service may call
 * it, as only the bootstrap may call `inviteAsSystem`.
 */
class ScheduledAdvanceRuleTests {
    private val production =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.finaxis.platform")

    @Test
    fun `only the scheduled advance moves a business date as the system`() {
        noClasses()
            .that(
                object : DescribedPredicate<JavaClass>("are not the scheduled advance") {
                    override fun test(input: JavaClass): Boolean =
                        !input.isEquivalentTo(BusinessDateAutoAdvance::class.java) &&
                            !input.isEquivalentTo(BusinessDateService::class.java)
                },
            ).should()
            .accessTargetWhere(
                object : DescribedPredicate<JavaAccess<*>>("a system-run business-date advance") {
                    override fun test(input: JavaAccess<*>): Boolean =
                        input.targetOwner.isEquivalentTo(BusinessDateService::class.java) &&
                            input.target.name == SYSTEM_ADVANCE
                },
            ).because(
                "advanceAsSystem asks no actor permission: only the scheduled advance, which " +
                    "reads the tenant's opt-in, may use it",
            ).check(production)
    }

    @Test
    fun `the rule sees the one call it allows`() {
        val calls =
            production
                .get(BusinessDateAutoAdvance::class.java)
                .accessesFromSelf
                .filter { it.target.name == SYSTEM_ADVANCE }
        assertTrue(calls.isNotEmpty(), "the scheduled advance no longer calls $SYSTEM_ADVANCE")
    }

    private companion object {
        const val SYSTEM_ADVANCE = "advanceAsSystem"
    }
}
