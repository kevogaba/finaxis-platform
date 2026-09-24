package com.finaxis.platform.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Guards every checkpoint-plus-delta reporting method against the default transaction isolation.
 *
 * ## The defect this rule exists to make impossible again
 *
 * `LedgerReportingService` and `FinancialStatementService` both compose a report from two or more
 * reads through their own constructor-injected ports inside one `@Transactional` method — a
 * checkpoint balance plus the journal's movement since it, or a fiscal period resolved and then
 * aggregated over. At the default `READ COMMITTED`, each statement in that composition is free to
 * see a different snapshot of the database. A journal that commits between the checkpoint read and
 * the delta read, whose posting date falls at or before the checkpoint, then lands in **neither**
 * half: the checkpoint was taken before it committed, and the delta's own window excludes the date
 * it posted on. Because a journal is always balanced by construction (`INV-4`), the report that
 * omitted it still balances — so the very check meant to catch a wrong report (`provenBalanced` /
 * `proven`) passes over exactly the failure this composition can produce, and the report is wrong
 * while asserting that it is sound.
 *
 * `[SnapshotIsolationGuard.requireStableSnapshot]` closes this by asserting, at runtime, that the
 * method actually got the `REPEATABLE_READ` isolation its `@Transactional` annotation asked for —
 * a Spring transaction manager can silently downgrade an unsupported isolation level rather than
 * fail the method, which is precisely the case a compile-time-only annotation cannot itself detect.
 * `LedgerReportingService` carried the guard on every composing method from the change that
 * introduced it. `FinancialStatementService` shipped without it: `balanceSheet` and
 * `incomeStatement` both compose a checkpoint and a delta exactly like a trial balance does, and
 * the omission was found only by an independent review sweep reading the two services side by
 * side, not by anything in this build. Nothing mechanical distinguished "has the guard" from "reads
 * like it should" before this rule, and a third reporting method added the same way, without the
 * same reviewer noticing, would have shipped the same gap silently.
 *
 * ## Why a source scan, and why this heuristic
 *
 * Following `AccountingBoundaryRuleTests`' "accounting code never uses binary floating point": the
 * property being checked — whether a method's *own composition* reads through several of the
 * class's own ports — is a source-level, textual fact, not one bytecode reconstructs reliably.
 *
 * The first version of this exact rule scanned only the annotated method's own literal body for
 * `fieldName.` references and was green against `FinancialStatementService` — the file it exists to
 * protect. It was green for the wrong reason: `balanceSheet` reads `balanceSnapshot` directly but
 * reaches `chart` only through the private helper `postableAccounts()`, and `incomeStatement` reads
 * `ledger` directly but reaches `chart` through the same helper and `periods` through
 * `resolveWindow()`. Both methods scored one direct field-reference each, one below the multi-read
 * floor, so neither was ever classified as a composition at all — a reviewer deleting
 * `requireStableSnapshot` from either method today would sail through that version with an empty
 * offender list, which is exactly the silent-drop shape every rule in this suite exists to avoid.
 * Routing a second read through a small private helper is completely ordinary Kotlin, not evasion,
 * so a scan that cannot see through it is not actually checking the file it claims to guard.
 *
 * This version therefore treats a `@Transactional` method's "own reads" as the reads reachable
 * from it through this class's **own private call graph**: starting at the method, it follows
 * every call to another function this same class declares, transitively, and sums `fieldName.`
 * references for the class's own constructor-injected fields across everything reached - the
 * port-naming convention this package's read models already follow, `Store`, `Queries`,
 * `Snapshot`, `Reader` or `Lookup`. A "multi-read" method is one whose reachable total is at
 * least two. That is deliberately the simplest heuristic that (a) now flags both
 * `FinancialStatementService` methods as well as today's known `LedgerReportingService` ones and
 * (b) still clears a method with only one such reference anywhere it can reach - a floor, not a
 * proof, exactly as the divide-by-zero scan next to it in `MoneyArithmeticRuleTests` describes
 * itself. The `requireStableSnapshot` check itself stays on the annotated method's **own** body,
 * not the reachable closure: the guard belongs at the composition site the isolation actually
 * protects, which is where both real services already put it, and a rule that accepted the call
 * from three helpers deep would stop telling a reader where the protection actually lives.
 */
class AccountingReportingSnapshotIsolationRuleTests {
    @Test
    fun `every multi-read reporting method holds a stable snapshot`() {
        val offenders = reportingSourceFiles().flatMap { file -> offendersIn(file.readText()) }

        assertEquals(
            emptyList(),
            offenders,
            "A multi-read method composes two or more reads through this class's own " +
                "constructor-injected ports (a checkpoint balance plus the journal's delta, or " +
                "a resolved period aggregated over) — reached either directly in its own body or " +
                "through a private helper it calls — inside one @Transactional method. At the " +
                "default READ COMMITTED, a journal that commits mid-report with a posting date " +
                "at or before the checkpoint lands in neither half - and because that journal is " +
                "itself balanced, the composed report still balances while being wrong. " +
                "FinancialStatementService shipped without the guard and LedgerReportingService " +
                "had it from the start; an independent review sweep caught the gap by hand, and " +
                "nothing mechanical would have caught it before this rule. Call " +
                "snapshots.requireStableSnapshot(RequiredSnapshotIsolation.REPEATABLE_READ, " +
                "...) inside the method's own body: $offenders",
        )
    }

    @Test
    fun `the scan still recognises today's known multi-read methods`() {
        // Non-vacuity, following AccountingBoundaryRuleTests' "the accounting table rule guards
        // tables that are actually generated": an empty offender list above means nothing if the
        // detector has quietly stopped detecting multi-read methods at all, so the methods it
        // must find are named here rather than left implicit - including the two
        // FinancialStatementService methods this rule exists to protect, which only appear here
        // because the scan now follows calls into the class's own private helpers.
        val multiRead =
            reportingSourceFiles()
                .flatMap { file -> transactionalMethodsIn(file.readText()) }
                .filter { it.isMultiRead }
                .map { it.qualifiedName }
                .sorted()

        assertEquals(EXPECTED_MULTI_READ_METHODS, multiRead, "saw $multiRead")
    }

    @Test
    fun `a fabricated multi-read method with no guard is caught`() {
        // The other half of non-vacuity: proving the detector can actually SEE the violation it
        // exists to catch, using the same shape FinancialStatementService shipped with before the
        // guard was added, rather than only ever exercising it against code already known to pass.
        val offenders = offendersIn(FABRICATED_METHOD_WITHOUT_GUARD)

        assertEquals(
            listOf("FixtureService.composite"),
            offenders,
            "a fabricated multi-read method with no requireStableSnapshot call must be caught; " +
                "if it is not, the rule above is not actually checking anything",
        )
    }

    @Test
    fun `the same fabricated method clears once it holds the guard`() {
        // The guarded twin of the fixture above: identical reads, one extra call. If this were
        // still flagged, the rule would be rejecting every multi-read method outright rather than
        // only the unguarded ones, which is a different and much noisier rule than intended.
        val offenders = offendersIn(FABRICATED_METHOD_WITH_GUARD)

        assertEquals(
            emptyList(),
            offenders,
            "a multi-read method that already calls requireStableSnapshot must not be flagged",
        )
    }

    @Test
    fun `a fabricated single-read method is never flagged even without the guard`() {
        // A method touching only one qualifying field one time is not the composition this rule
        // is about, and must stay clear with no guard at all - otherwise every reporting method
        // that reads anything would need one, which is not what the defect required.
        val offenders = offendersIn(FABRICATED_SINGLE_READ_METHOD)

        assertEquals(
            emptyList(),
            offenders,
            "a single-read method must never be treated as a multi-read composition",
        )
    }

    @Test
    fun `a read reached only through a private helper is still counted, and caught unguarded`() {
        // This is the exact shape FinancialStatementService shipped with: one read written
        // directly in the @Transactional method's own body, and a second read reached only by
        // calling a private helper declared elsewhere in the class. A detector that only looks at
        // the annotated method's own literal text - the first version of this rule - scores this
        // as a single read and never flags it, which is precisely how the real defect went
        // unnoticed by every mechanical check before this one.
        val offenders = offendersIn(FABRICATED_HELPER_ROUTED_METHOD_WITHOUT_GUARD)

        assertEquals(
            listOf("FixtureService.composite"),
            offenders,
            "a second read reached only through a private helper must still make the method " +
                "multi-read, and an unguarded one must still be caught: if it is not, this rule " +
                "has the same blind spot FinancialStatementService's guard omission exploited",
        )
    }

    @Test
    fun `the helper-routed composition clears once its own body holds the guard`() {
        // The guarded twin: the same helper-routed second read, plus the one call the rule
        // requires, written where both real services write it - directly in the annotated
        // method's own body, not inside the helper.
        val offenders = offendersIn(FABRICATED_HELPER_ROUTED_METHOD_WITH_GUARD)

        assertEquals(
            emptyList(),
            offenders,
            "a helper-routed multi-read method that already calls requireStableSnapshot in its " +
                "own body must not be flagged",
        )
    }

    private fun offendersIn(source: String): List<String> =
        transactionalMethodsIn(source)
            .filter { it.isMultiRead && !it.holdsGuard }
            .map { it.qualifiedName }

    private fun reportingSourceFiles(): List<File> =
        File(REPORTING_SOURCE_ROOT)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** One `@Transactional` method's fully-qualified name, its multi-read status and guard call. */
    private data class ReportingTransactionalMethod(
        val qualifiedName: String,
        val isMultiRead: Boolean,
        val holdsGuard: Boolean,
    )

    /**
     * One source file's own top-level class: its qualifying constructor fields, every function it
     * declares (name to that function's own body text), and the names of its `@Transactional`
     * methods.
     */
    private data class ReportingClass(
        val className: String,
        val qualifyingFields: Set<String>,
        val functionBodies: Map<String, String>,
        val transactionalMethodNames: List<String>,
    )

    /**
     * Every `@Transactional` method declared on [source]'s own top-level class, with whether it
     * reads through its class's own qualifying fields - directly or via a private helper it calls -
     * two or more times in total, and whether it holds the isolation guard itself.
     */
    private fun transactionalMethodsIn(source: String): List<ReportingTransactionalMethod> {
        val reportingClass = parseReportingClass(source) ?: return emptyList()
        return reportingClass.transactionalMethodNames.map { methodName ->
            val ownBody = reportingClass.functionBodies[methodName].orEmpty()
            val readCount =
                reachableQualifyingReadCount(
                    methodName,
                    reportingClass.functionBodies,
                    reportingClass.qualifyingFields,
                )
            ReportingTransactionalMethod(
                qualifiedName = "${reportingClass.className}.$methodName",
                isMultiRead = readCount >= MULTI_READ_FLOOR,
                holdsGuard = ownBody.contains(GUARD_CALL),
            )
        }
    }

    /**
     * Parses [source]'s own top-level class: its name, its constructor's qualifying field names,
     * every function it declares split into its own body text, and which of those functions carry
     * `@Transactional`.
     *
     * Comments are stripped first, following `AccountingBoundaryRuleTests`' `executablePartOf`, so
     * a KDoc mentioning a field name in prose - `[ChartReportingQueries]` or an English sentence
     * ending in "...account's ledger." - can never be mistaken for the code that actually reads it.
     */
    private fun parseReportingClass(source: String): ReportingClass? {
        val cleaned = stripComments(source)
        val className = CLASS_NAME_REGEX.find(cleaned)?.groupValues?.get(1) ?: return null
        val (constructorText, classBody) = classAnatomyOf(cleaned, className) ?: return null
        val qualifyingFields = qualifyingFieldNamesIn(constructorText)

        // Every function this class declares, in one pass: its start is where "fun name(" begins,
        // and its own body runs until the next such start (or the end of the class body for the
        // last one). This is deliberately cruder than brace-matching each body individually, and
        // that is the point: it needs no special case for a block body versus a single-expression
        // one (`= expr` with no braces at all, which most of this package's private helpers use),
        // and unlike brace-matching it cannot be defeated by a brace inside a string interpolation.
        val functionStarts =
            FUN_DECLARATION_REGEX
                .findAll(classBody)
                .map { it.range.first to it.groupValues[1] }
                .toList()
        val functionBodies =
            functionStarts
                .mapIndexed { index, (start, name) ->
                    val end = functionStarts.getOrNull(index + 1)?.first ?: classBody.length
                    name to classBody.substring(start, end)
                }.toMap()

        val transactionalMethodNames =
            TRANSACTIONAL_REGEX
                .findAll(classBody)
                .mapNotNull { annotation ->
                    functionStarts.firstOrNull { (start, _) -> start > annotation.range.last }
                }.map { (_, name) -> name }
                .distinct()
                .toList()

        return ReportingClass(className, qualifyingFields, functionBodies, transactionalMethodNames)
    }

    /**
     * The constructor's own parenthesised text and the class's own braced body text, for the class
     * named [className] in [cleaned] - or null if either's opening delimiter cannot be found or its
     * matching close cannot be located.
     *
     * Split out of [parseReportingClass] so that function stays within this file's own return-count
     * budget: one `?: return null` for a failed match here reads the same as one for a failed one
     * there, rather than the four separate null-checks a single flat function would otherwise need.
     */
    private fun classAnatomyOf(
        cleaned: String,
        className: String,
    ): Pair<String, String>? {
        val classIndex = cleaned.indexOf("class $className")
        val openParen = if (classIndex < 0) -1 else cleaned.indexOf('(', classIndex)
        val closeParen = if (openParen < 0) -1 else matchingDelimiter(cleaned, openParen, '(', ')')
        if (closeParen < 0) return null
        val classBodyOpen = cleaned.indexOf('{', closeParen)
        val classBodyClose =
            if (classBodyOpen < 0) -1 else matchingDelimiter(cleaned, classBodyOpen, '{', '}')
        if (classBodyClose < 0) return null
        return cleaned.substring(openParen, closeParen + 1) to
            cleaned.substring(classBodyOpen + 1, classBodyClose)
    }

    /** The constructor-injected field names of a port that reads state. */
    private fun qualifyingFieldNamesIn(constructorText: String): Set<String> =
        FIELD_REGEX
            .findAll(constructorText)
            .filter { match -> QUALIFYING_SUFFIXES.any { match.groupValues[2].endsWith(it) } }
            .map { it.groupValues[1] }
            .toSet()

    /**
     * How many times [startFunction] reads through [qualifyingFields], counting both what its own
     * body reads directly and what every function it calls - transitively, through this same
     * class's own declared functions - reads on its behalf.
     *
     * This is the fix for the gap a purely-local scan has: `FinancialStatementService.balanceSheet`
     * reads `balanceSnapshot` in its own body and reaches `chart` only by calling the private
     * `postableAccounts()`, and a scan that never followed that call would forever see one read
     * where the method actually composes two. Cycle-safe by construction: each function is visited
     * at most once, so a helper called from two branches, or a function that (harmlessly) mentions
     * its own name in its own signature, contributes its reads exactly once rather than looping.
     */
    private fun reachableQualifyingReadCount(
        startFunction: String,
        functionBodies: Map<String, String>,
        qualifyingFields: Set<String>,
    ): Int {
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque(listOf(startFunction))
        var total = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val firstVisit = visited.add(current)
            val body = functionBodies[current]
            if (!firstVisit || body == null) continue
            total += totalQualifyingReadsIn(body, qualifyingFields)
            functionBodies.keys
                .filter { it !in visited && callsFunction(body, it) }
                .forEach { pending.add(it) }
        }
        return total
    }

    /** Whether `body` calls the function named `functionName`, as `functionName(`. */
    private fun callsFunction(
        body: String,
        functionName: String,
    ): Boolean = Regex("""\b${Regex.escape(functionName)}\s*\(""").containsMatchIn(body)

    /**
     * How many times, summed across all of [qualifyingFields], `body` writes `fieldName` followed
     * by a `.` - tolerating the line break this package's own call chains put between them, as in
     * `ledger` on one line and `.movementsByAccount(` on the next.
     */
    private fun totalQualifyingReadsIn(
        body: String,
        qualifyingFields: Set<String>,
    ): Int =
        qualifyingFields.sumOf { fieldName ->
            Regex("""\b${Regex.escape(fieldName)}\s*\.""").findAll(body).count()
        }

    /**
     * Removes every block (`/** ... */`) and line (`// ...`) comment from [source].
     *
     * Applied once, before any other scan in this file runs, so a KDoc's prose - a Kdoc link, or
     * an English sentence that happens to end a line in "...ledger." - can never be counted as the
     * code reference it merely describes.
     */
    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT_REGEX, " ")
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }

    /**
     * The index of the `close` that matches the `open` at [openIndex], by counting depth alone -
     * a small helper, shared by brace matching (a class's own body) and paren matching (a
     * constructor's parameter list), following this package's own small-helper convention.
     *
     * This does **not** skip string literals, and with comments already stripped by
     * [stripComments] it does not need to: neither production file this rule scans carries a `{`,
     * `}`, `(` or `)` inside a string literal that would shift [depth] away from where plain code
     * left it. A future file that broke that assumption would show up as a wrong close index
     * rather than a silent one, which is the failure mode a floor like this one is allowed to have.
     */
    private fun matchingDelimiter(
        text: String,
        openIndex: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        for (index in openIndex until text.length) {
            when (text[index]) {
                open -> {
                    depth++
                }

                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private companion object {
        const val REPORTING_SOURCE_ROOT =
            "src/main/kotlin/com/finaxis/platform/accounting/application/reporting"

        /** The call every multi-read method must make, in its own body, before composing reads. */
        const val GUARD_CALL = "requireStableSnapshot"

        /** At least this many total field-dot references make a method's reads a composition. */
        const val MULTI_READ_FLOOR = 2

        val CLASS_NAME_REGEX = Regex("""(?m)^class (\w+)""")
        val FUN_DECLARATION_REGEX = Regex("""\bfun\s+(\w+)\s*\(""")
        val TRANSACTIONAL_REGEX = Regex("""@Transactional\b""")
        val FIELD_REGEX = Regex("""private val (\w+):\s*(\w+)""")
        val BLOCK_COMMENT_REGEX = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        /** Port-field type suffixes this package uses for something that reads state. */
        val QUALIFYING_SUFFIXES = listOf("Store", "Queries", "Snapshot", "Reader", "Lookup")

        /**
         * Today's known multi-read methods, sorted, across both files this rule scans.
         *
         * `LedgerReportingService.trialBalance` and `.rollup` compose their reads inline;
         * `.accountLedger` reaches a second and third read through the private
         * `requireBranchInOrganisation` and `requireAccount` helpers it calls for validation.
         * `FinancialStatementService.balanceSheet` and `.incomeStatement` are the pair this rule
         * exists for: each writes exactly one qualifying read directly and reaches its others only
         * through the private `postableAccounts`, `requireBranchInOrganisation`,
         * `functionalCurrencyOf` and (for `incomeStatement`) `resolveWindow` helpers - which is
         * why a scan that does not follow calls into a class's own helpers never saw them as a
         * composition at all.
         */
        val EXPECTED_MULTI_READ_METHODS =
            listOf(
                "FinancialStatementService.balanceSheet",
                "FinancialStatementService.incomeStatement",
                "LedgerReportingService.accountLedger",
                "LedgerReportingService.rollup",
                "LedgerReportingService.trialBalance",
            )

        /** A checkpoint-plus-delta method with no isolation guard - the shape this rule forbids. */
        val FABRICATED_METHOD_WITHOUT_GUARD =
            """
            package com.finaxis.platform.accounting.application.reporting.fixture

            class FixtureService(
                private val ledger: FixtureQueries,
                private val chart: FixtureQueries,
            ) {
                @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
                fun composite(): Int {
                    val a = ledger.read()
                    val b = chart.read()
                    return a + b
                }
            }
            """.trimIndent()

        /** The same composition, made compliant by the one call the rule requires. */
        val FABRICATED_METHOD_WITH_GUARD =
            """
            package com.finaxis.platform.accounting.application.reporting.fixture

            class FixtureService(
                private val ledger: FixtureQueries,
                private val chart: FixtureQueries,
                private val snapshots: SnapshotIsolationGuard,
            ) {
                @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
                fun composite(): Int {
                    snapshots.requireStableSnapshot(
                        RequiredSnapshotIsolation.REPEATABLE_READ,
                        "A fixture",
                    )
                    val a = ledger.read()
                    val b = chart.read()
                    return a + b
                }
            }
            """.trimIndent()

        /** One qualifying read, once - not the composition this rule is about. */
        val FABRICATED_SINGLE_READ_METHOD =
            """
            package com.finaxis.platform.accounting.application.reporting.fixture

            class FixtureService(
                private val ledger: FixtureQueries,
            ) {
                @Transactional(readOnly = true)
                fun single(): Int {
                    return ledger.read()
                }
            }
            """.trimIndent()

        /**
         * The exact shape `FinancialStatementService` shipped with: one read written directly in
         * the `@Transactional` method's own body, and a second reached only through a private
         * helper declared elsewhere in the class - with no isolation guard anywhere.
         */
        val FABRICATED_HELPER_ROUTED_METHOD_WITHOUT_GUARD =
            """
            package com.finaxis.platform.accounting.application.reporting.fixture

            class FixtureService(
                private val ledger: FixtureQueries,
                private val chart: FixtureQueries,
            ) {
                @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
                fun composite(): Int {
                    val a = ledger.read()
                    val b = postableThings()
                    return a + b
                }

                private fun postableThings(): Int = chart.read()
            }
            """.trimIndent()

        /** The same helper-routed composition, guarded where both real services guard it. */
        val FABRICATED_HELPER_ROUTED_METHOD_WITH_GUARD =
            """
            package com.finaxis.platform.accounting.application.reporting.fixture

            class FixtureService(
                private val ledger: FixtureQueries,
                private val chart: FixtureQueries,
                private val snapshots: SnapshotIsolationGuard,
            ) {
                @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
                fun composite(): Int {
                    snapshots.requireStableSnapshot(
                        RequiredSnapshotIsolation.REPEATABLE_READ,
                        "A fixture",
                    )
                    val a = ledger.read()
                    val b = postableThings()
                    return a + b
                }

                private fun postableThings(): Int = chart.read()
            }
            """.trimIndent()
    }
}
