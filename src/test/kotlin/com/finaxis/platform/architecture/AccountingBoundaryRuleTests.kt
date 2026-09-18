package com.finaxis.platform.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
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

    @Test
    fun `no bean accounting exposes declares a generic method`() {
        // Spring Modulith advises every type a module EXPOSES with ModuleEntryInterceptor, which
        // renders the invoked method's signature to name its observation. DefaultObservedModule
        // .render calls FormattableType.of(resolvableType.resolve()), and ResolvableType.resolve()
        // answers null for an unresolvable type variable — so the first call to a generic method on
        // an exposed bean dies with `NullPointerException: Cannot invoke "java.lang.Class
        // .getTypeName()" because "type" is null`, inside the interceptor, before any application
        // code runs.
        //
        // That is a defect in Spring Modulith 2.1.0 rather than in the method, and it is still
        // ours to avoid: spring-boot-starter-opentelemetry is a runtime dependency, so the
        // interceptor is live in production and not merely under test. It is also invisible to
        // every unit test, because it only exists once the bean is proxied in a running context.
        //
        // PostingTransactionBoundary.execute was first written as `fun <T : Any> execute(...)` in
        // the exposed posting package and failed exactly this way. The generic seam moved to
        // application.ledger, which the module does not expose and Modulith therefore does not
        // advise, and PostingTransactions took its place with no type parameters anywhere. This
        // rule is the only thing standing between that and a silent reintroduction.
        val exposedBeans =
            productionClasses
                .filter { it.packageName in EXPOSED_PACKAGES }
                .filter { it.isMetaAnnotatedWith(SPRING_COMPONENT) }

        val offenders =
            exposedBeans
                .flatMap { type ->
                    type.methods
                        .filter { it.modifiers.contains(JavaModifier.PUBLIC) }
                        .filter { it.typeParameters.isNotEmpty() }
                        .map { "${type.simpleName}.${it.name} declares ${it.typeParameters}" }
                }.sorted()

        assertEquals(
            emptyList(),
            offenders,
            "a generic method on a bean accounting exposes throws NullPointerException inside " +
                "Spring Modulith's ModuleEntryInterceptor on its first call; keep the generic " +
                "signature in a package the module does not expose: $offenders",
        )

        // Non-vacuity, per `the accounting table rule guards tables that are actually generated`
        // above: this rule matches on a package and an annotation, so a rename or a move empties
        // the subject set and turns it green while proving nothing.
        assertEquals(
            true,
            exposedBeans.any { it.simpleName == "PostingTransactions" },
            "the rule must actually be scanning accounting's exposed beans; " +
                "PostingTransactions was not among ${exposedBeans.map { it.simpleName }}",
        )
    }

    @Test
    fun `the posting boundary is the only retryable method in accounting`() {
        // A second retry boundary would not be a duplicate, it would be a multiplier. Any new one
        // necessarily sits somewhere in the call tree of this one, so a serialization failure gets
        // re-run by the inner boundary before the outer one ever sees it and the budget becomes
        // MAX_ATTEMPTS squared - five attempts become twenty-five, and the worst-case latency of a
        // contended posting goes with it. Nothing else in the build notices.
        //
        // Matched on the annotation's SIMPLE name so that both spring-retry's @Retryable and
        // Spring Framework 7's same-named org.springframework.resilience one land in the subject
        // set; `accounting never names Spring's resilience package` below pins which of the two
        // this one actually is.
        val retryable = accountingMethodsAnnotatedWith(RETRYABLE)

        // Equality against a NON-EMPTY expected list, following `the accounting table rule guards
        // tables that are actually generated` above. A rule that merely counts annotations goes
        // green and proves nothing the moment the method is renamed or the annotation moved, so
        // the guarded method is named here rather than left implicit.
        assertEquals(
            listOf("PostingTransactionBoundary.execute"),
            retryable.map { "${it.owner.simpleName}.${it.name}" }.sorted(),
            "accounting has exactly one retry boundary, on one method, and this is it",
        )
    }

    @Test
    fun `no accounting method carries both the retry and the transaction annotation`() {
        // The separation is the design, not a style preference. A serialization failure can be
        // raised by COMMIT itself - PostgreSQL cancels a transaction it identifies as an SSI pivot
        // during the commit attempt - and a commit-time exception is thrown BY the transaction
        // interceptor, so only advice strictly outside it can ever see one. Co-locating @Retryable
        // and @Transactional on one method puts the retry inside the transaction, where it would
        // still retry statement-level 40001s, still look right in review, and silently never retry
        // the commit-time case the whole design exists for. A class-level @Transactional advises
        // the retryable method just as effectively, so the owner is checked too.
        val retryable = accountingMethodsAnnotatedWith(RETRYABLE)
        assertEquals(
            true,
            retryable.isNotEmpty(),
            "non-vacuity: this rule scans nothing at all if @Retryable has moved or gone",
        )

        // The other half of non-vacuity, and the one that is easy to forget: prove the same scan
        // can SEE a @Transactional. Without this, a green result means "the detector found no
        // co-location" and "the detector stopped detecting" equally well.
        val transactional =
            accountingMethodsAnnotatedWith(TRANSACTIONAL).map {
                "${it.owner.simpleName}.${it.name}"
            }
        assertEquals(
            true,
            "SerializablePostingTransaction.run" in transactional,
            "non-vacuity: the @Transactional half of the scan found nothing it should have " +
                "found; saw $transactional",
        )

        val offenders =
            retryable
                .filter { it.carries(TRANSACTIONAL) || it.owner.carries(TRANSACTIONAL) }
                .map { "${it.owner.simpleName}.${it.name}" }
                .sorted()

        assertEquals(
            emptyList(),
            offenders,
            "a retried method must not be transaction-advised: an SSI pivot is raised by COMMIT " +
                "and is invisible to advice nested inside the transaction. Open the transaction " +
                "on a separate bean, as SerializablePostingTransaction does: $offenders",
        )
    }

    @Test
    fun `accounting never names Spring's resilience package`() {
        // Spring Framework 7.0.9 ships org.springframework.resilience.annotation.Retryable, which
        // differs from org.springframework.retry.annotation.Retryable only by package and which an
        // IDE offers first because it needs no third-party dependency. It has no @Recover. So the
        // wrong symbol compiles, still retries, and then hands the caller a raw
        // ConcurrencyFailureException - PostingTransactionBoundary's three @Recover overloads and
        // accounting.posting_retries_exhausted all become dead code at once, with no build failure
        // and no log line. That is the exact silent-drop shape this suite exists to make loud.
        //
        // Scanned as source rather than as bytecode, following `accounting code never uses binary
        // floating point` above: this catches the fully-qualified use and an aliased import as
        // well as a plain one, and it reads at the level a reviewer actually looks at.
        val offenders =
            accountingCodeLines()
                .filter { (_, code) -> RESILIENCE_PACKAGE in code }
                .map { (where, code) -> "$where $code" }

        assertEquals(
            emptyList(),
            offenders,
            "accounting's retry annotations come from spring-retry; Spring's resilience package " +
                "has a same-named @Retryable with no @Recover support: $offenders",
        )

        // Non-vacuity: the rule guards a choice between two packages, so it is worth nothing once
        // neither is present. Naming the file also pins the correct import to one place.
        val correct =
            accountingCodeLines()
                .filter { (_, code) -> code == "import $SPRING_RETRY_RETRYABLE" }
                .map { (where, _) -> where.substringBefore(':') }

        assertEquals(
            listOf("PostingTransactionBoundary.kt"),
            correct,
            "non-vacuity: exactly one accounting file imports spring-retry's @Retryable, and if " +
                "that stops being true the rule above is guarding nothing; saw $correct",
        )
    }

    /** Declared methods under `accounting` carrying an annotation with this simple name. */
    private fun accountingMethodsAnnotatedWith(annotation: String): List<JavaMethod> =
        productionClasses
            .filter { it.packageName.startsWith(ACCOUNTING_PACKAGE) }
            .flatMap { it.methods }
            .filter { it.carries(annotation) }

    private fun JavaMethod.carries(annotation: String): Boolean =
        annotations.any { it.rawType.simpleName == annotation }

    private fun JavaClass.carries(annotation: String): Boolean =
        annotations.any { it.rawType.simpleName == annotation }

    /**
     * Every accounting Kotlin line as `File.kt:42` to its executable part, comments stripped.
     *
     * Stripping matters here: `PostingTransactionBoundary`'s KDoc names the forbidden package on
     * purpose, to warn the next reader about it. A raw text search would report that warning as
     * the violation it warns about.
     */
    private fun accountingCodeLines(): List<Pair<String, String>> =
        File(ACCOUNTING_SOURCE_ROOT)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex().map { (index, line) ->
                    "${file.name}:${index + 1}" to executablePartOf(line)
                }
            }.toList()

    private fun executablePartOf(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
            return ""
        }
        return trimmed.substringBefore("//").trim()
    }

    private companion object {
        const val ROOT = "com.finaxis.platform"
        const val ACCOUNTING = "com.finaxis.platform.accounting.."
        const val ACCOUNTING_PACKAGE = "com.finaxis.platform.accounting"
        const val ACCOUNTING_SOURCE_ROOT = "src/main/kotlin/com/finaxis/platform/accounting"

        /** Annotation simple names, deliberately unqualified - see the rules that use them. */
        const val RETRYABLE = "Retryable"
        const val TRANSACTIONAL = "Transactional"

        /** The retry annotation accounting is allowed to use. */
        const val SPRING_RETRY_RETRYABLE = "org.springframework.retry.annotation.Retryable"

        /** Spring Framework 7's own retry support, which has no `@Recover`. */
        const val RESILIENCE_PACKAGE = "org.springframework.resilience"

        /** The packages accounting declares as `@NamedInterface`, and therefore exposes. */
        val EXPOSED_PACKAGES =
            setOf(
                "com.finaxis.platform.accounting.application.posting",
                "com.finaxis.platform.accounting.domain",
            )

        /** Spring's stereotype meta-annotation; only beans are advised by Modulith. */
        val SPRING_COMPONENT: Class<out Annotation> =
            org.springframework.stereotype.Component::class.java

        /** `Double`/`Float` as a declared type, a constructor call, or a conversion. */
        val FLOATING_POINT =
            Regex("""\b(:\s*(Double|Float)\b|(Double|Float)\s*\(|\.to(Double|Float)\s*\()""")

        /**
         * The subset of [ACCOUNTING_TABLE_TYPES] that a migration has actually created. Later
         * issues move their own names into this set as their migrations land: `V6` the first
         * five, `V7` the three journal tables, `V8` the four posting-rule tables, `V9` the
         * reconciliation evidence table, `V10` the three manual-journal tables, and `V14` the
         * daily-balance projection.
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
                "PostingRule",
                "PostingRuleVersion",
                "PostingRuleLeg",
                "PostingRuleVersionTransitionLog",
                "ControlAccountReconciliationRun",
                "ManualJournal",
                "ManualJournalLine",
                "ManualJournalTransitionLog",
                "GlAccountDailyBalance",
            )

        /**
         * Generated jOOQ table types reserved for the accounting schema. jOOQ emits every table
         * into one package, so ownership is asserted by type name. Keep in sync with the canonical
         * ERD in `docs/database/accounting-erd.md`.
         *
         * All seventeen now exist: the first sixteen as of `V10`, and `GlAccountDailyBalance`
         * as of `V14`. Naming `GlAccountDailyBalance` here before its table existed was
         * deliberate — the rule guarded it from the moment the table appeared rather than from the
         * moment someone remembered to add it.
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
                // Issue #48's manual-journal draft aggregate, added to the ERD by the change that
                // created it, so the boundary rule guards it from its first build.
                "ManualJournal",
                "ManualJournalLine",
                "ManualJournalTransitionLog",
            )

        val accountingJooqTables: DescribedPredicate<JavaClass> =
            object : DescribedPredicate<JavaClass>("generated jOOQ accounting tables") {
                override fun test(input: JavaClass): Boolean =
                    input.packageName == "com.finaxis.platform.jooq.tables" &&
                        input.simpleName in ACCOUNTING_TABLE_TYPES
            }
    }
}
