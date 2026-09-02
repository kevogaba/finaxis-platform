package com.finaxis.platform.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Boundary rules for the accounting kernel.
 *
 * Accounting owns the general ledger. Product modules reach it only through the documented posting
 * API, and accounting never reaches into the identity or lifecycle modules — it declares ports and
 * those modules supply the adapters, so the dependency points at accounting and close-of-business
 * can call into it later without a cycle.
 *
 * See `docs/architecture/accounting-foundation.md` and
 * `docs/adr/0020-immutable-ledger-and-reversal-only-correction.md`.
 */
class AccountingBoundaryRuleTests {
    private val productionClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(ROOT)

    @Test
    fun `accounting code never uses binary floating point`() {
        // ADR 0019 bans binary floating point in accounting. Double and Float cannot represent
        // most decimal fractions exactly, so a rounding rule stated in the ADR would be violated
        // by the arithmetic itself before any rounding code ran.
        //
        // This scans source rather than bytecode, and that is the whole point. The first version
        // of this rule was an ArchUnit dependency check on java.lang.Double and java.lang.Float —
        // which a non-null Kotlin `Double` never touches, because it compiles to the JVM primitive
        // `double`. The rule was green against exactly the code it was written to forbid.
        val offenders =
            File(ACCOUNTING_SOURCE_ROOT)
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> declaresFloatingPoint(line) }
                        .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
                }.toList()

        assertEquals(
            emptyList(),
            offenders,
            "money is NUMERIC end to end; binary floating point cannot represent decimal " +
                "fractions exactly, so it is banned in accounting code (ADR 0019): $offenders",
        )
    }

    private fun declaresFloatingPoint(line: String): Boolean {
        val code = line.substringBefore("//").trim()
        if (code.startsWith("*") || code.startsWith("/*")) {
            return false
        }
        return FLOATING_POINT.containsMatchIn(code)
    }

    @Test
    fun `no module outside accounting depends on accounting adapters or configuration`() {
        noClasses()
            .that()
            .resideOutsideOfPackage(ACCOUNTING)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform.accounting.adapter..",
                "com.finaxis.platform.accounting.config..",
                "com.finaxis.platform.accounting.application.port..",
            ).because(
                "product modules consume accounting::posting and accounting::domain only; " +
                    "accounting adapters, wiring and outbound ports are module-internal",
            ).check(productionClasses)
    }

    @Test
    fun `only accounting persistence adapters touch generated accounting tables`() {
        // The generated package is excluded from the *subject* of the rule, not from its target.
        // jOOQ emits each table's nested `…Path`, its `Record`, `Keys`, `Public` and every other
        // table that holds an implicit join path to it, so the generator inevitably references its
        // own output - 222 such references the moment `V6` created the first five accounting
        // tables. Those are one code generator's internal wiring, not a module consuming the
        // ledger. What the rule is about is application code, and that is what remains in scope.
        noClasses()
            .that()
            .resideOutsideOfPackage(
                "com.finaxis.platform.accounting.adapter.outbound.persistence..",
            ).and()
            .resideOutsideOfPackage("com.finaxis.platform.jooq..")
            .should()
            .dependOnClassesThat(accountingJooqTables)
            .because(
                "product modules must never read or write the general ledger directly; they post " +
                    "through PostingService",
            ).check(productionClasses)
    }

    @Test
    fun `the accounting table rule guards tables that are actually generated`() {
        // Before `V6` the rule above had nothing to find, so it reported success while proving
        // nothing - the failure mode this suite exists to catch. This is what stops it going
        // quiet again: rename or drop one of the five tables and the rule silently returns to
        // vacuous, but this fails. Mutation-checked by adding a name no migration creates, which
        // fails here as intended.
        val generated =
            productionClasses
                .filter { it.packageName == "com.finaxis.platform.jooq.tables" }
                .map { it.simpleName }
                .toSet()

        assertEquals(
            emptySet(),
            SHIPPED_ACCOUNTING_TABLE_TYPES - generated,
            "the boundary rule can only guard a table that code generation actually emits",
        )
    }

    @Test
    fun `accounting does not depend on identity lifecycle or notifications`() {
        noClasses()
            .that()
            .resideInAPackage(ACCOUNTING)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform.iam..",
                "com.finaxis.platform.lifecycle..",
                "com.finaxis.platform.notifications..",
            ).because(
                "accounting declares its own ports and IAM and lifecycle supply the adapters, so " +
                    "the dependency points at accounting and no cycle is possible",
            ).check(productionClasses)
    }

    @Test
    fun `accounting does not depend on broker or background job infrastructure`() {
        noClasses()
            .that()
            .resideInAPackage(ACCOUNTING)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.amqp..",
                "io.namastack.outbox..",
                "org.jobrunr..",
            ).because(
                "ledger consistency is achieved in one PostgreSQL transaction; no broker or " +
                    "background job may sit in the posting critical path",
            ).check(productionClasses)
    }

    @Test
    fun `accounting domain stays free of framework and persistence types`() {
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform.accounting.domain..")
            .and()
            .haveSimpleNameNotEndingWith("package-info")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform.accounting.adapter..",
                "com.finaxis.platform.accounting.config..",
                "com.finaxis.platform.jooq..",
                "org.springframework..",
                "org.jooq..",
            ).check(productionClasses)
    }

    private companion object {
        const val ROOT = "com.finaxis.platform"
        const val ACCOUNTING = "com.finaxis.platform.accounting.."
        const val ACCOUNTING_SOURCE_ROOT = "src/main/kotlin/com/finaxis/platform/accounting"

        /** `Double`/`Float` as a declared type, a constructor call, or a conversion. */
        val FLOATING_POINT =
            Regex("""\b(:\s*(Double|Float)\b|(Double|Float)\s*\(|\.to(Double|Float)\s*\()""")

        /**
         * The subset of [ACCOUNTING_TABLE_TYPES] that a migration has actually created. Later
         * issues move their own names into this set as their migrations land: `V6` the first
         * five, `V7` the three journal tables.
         */
        val SHIPPED_ACCOUNTING_TABLE_TYPES =
            setOf(
                "AccountingFiscalYear",
                "AccountingFiscalPeriod",
                "GlAccount",
                "GlAccountTransitionLog",
                "FiscalPeriodTransitionLog",
                "PostingRequest",
                "JournalEntry",
                "JournalLine",
            )

        /**
         * Generated jOOQ table types reserved for the accounting schema. jOOQ emits every table
         * into one package, so ownership is asserted by type name. Keep in sync with the canonical
         * ERD in `docs/database/accounting-erd.md`.
         *
         * The eight in [SHIPPED_ACCOUNTING_TABLE_TYPES] exist as of `V7`; the rest are created by
         * issues #44, #46 and #47. Naming a type before its table exists is deliberate — the
         * rule then guards from the moment the table appears rather than from the moment someone
         * remembers to add it here.
         */
        val ACCOUNTING_TABLE_TYPES =
            setOf(
                "AccountingFiscalYear",
                "AccountingFiscalPeriod",
                "GlAccount",
                "GlAccountDailyBalance",
                "PostingRequest",
                "JournalEntry",
                "JournalLine",
                "PostingRule",
                "PostingRuleVersion",
                "PostingRuleLeg",
                "ControlAccountReconciliationRun",
                // The ERD's transition logs. Omitting them left the tables that record every
                // privileged state change unguarded, so code outside accounting could read them
                // while this rule still reported success.
                "GlAccountTransitionLog",
                "FiscalPeriodTransitionLog",
                "PostingRuleVersionTransitionLog",
            )

        val accountingJooqTables: DescribedPredicate<JavaClass> =
            object : DescribedPredicate<JavaClass>("generated jOOQ accounting tables") {
                override fun test(input: JavaClass): Boolean =
                    input.packageName == "com.finaxis.platform.jooq.tables" &&
                        input.simpleName in ACCOUNTING_TABLE_TYPES
            }
    }
}
