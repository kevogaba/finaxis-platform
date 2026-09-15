package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.JournalReversalFixture
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `V13`'s journal-line append guard, proved against PostgreSQL.
 *
 * Issue #95 item 1. `V13` installs the repository's only trigger - a statement-level
 * `AFTER INSERT ON journal_line` - which refuses any statement that would leave a journal holding
 * more `journal_line` rows than its header's `line_count` declares. Issue #54's
 * `REVOKE UPDATE, DELETE` cannot reach that append: it alters no protected row, and the engine's
 * verification read runs inside the transaction that created the journal and has long since
 * returned.
 *
 * What is proved here: the defect itself; every legitimate write shape the guard must leave alone -
 * a journal built a line at a time, the engine's one multi-row statement, a set-based statement
 * feeding several journals, and a reversal writing a whole new journal later; the guard's declared
 * shape, which behaviour cannot see; and the two limits the migration states rather than glosses,
 * as passing tests that record them.
 *
 * A sibling suite rather than more cases inside [JournalSchemaIntegrationTests], because these need
 * the posting engine for the production shapes and the two together would cross Detekt's
 * `LargeClass` ceiling.
 *
 * A trigger has no `pg_constraint` row, so [JournalSchemaFixture.assertViolates] - which matches a
 * constraint name in the failure's cause chain - has no name to match and is deliberately not used
 * here. Every refusal is asserted on both halves of what the migration promises instead: SQLSTATE
 * `23514`, which is what makes Spring render it as a [DataIntegrityViolationException] at all, and
 * the raised message naming the journal that overshot.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JournalLineAppendGuardIntegrationTests(
    private val dsl: DSLContext,
    private val postingService: PostingService,
    engine: PostingEngine,
    transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = JournalSchemaFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)
    private val fx =
        JournalReversalFixture(
            dsl,
            engine,
            TenantAdminOrganisationFixture(organisationProvisioningService, dsl),
            fixture,
            transactions,
        )

    @Test
    fun `a later transaction cannot append a line to a journal that holds its declared lines`() {
        // The defect, in its plainest form and the whole point of the migration. The journal is
        // written whole inside one transaction that has committed and ended before the append is
        // attempted, so nothing about the append belongs to the write that produced the journal:
        // it is exactly the "append a third line to a committed two-line journal" that changes a
        // journal's balance and its line count without touching a single protected row.
        val tenant = fixture.createTenant("append-guard-full")
        val journalId = transactions.execute { fixture.insertBalancedJournal(tenant) }
        assertEquals(
            LINES_PER_JOURNAL,
            linesOf(journalId),
            "the journal must be committed holding the two lines its header declares before the " +
                "append is attempted, or this proves nothing about a full journal",
        )

        val failure =
            assertFailsWith<DataIntegrityViolationException>(
                "a third line against a header declaring two must be refused",
            ) {
                fixture.insertJournalLine(
                    tenant,
                    journalId,
                    lineNumber = 3,
                    glAccountId = tenant.creditAccountId,
                    direction = "CREDIT",
                )
            }

        assertGuardRaised(failure, journalId)
        assertEquals(
            LINES_PER_JOURNAL,
            linesOf(journalId),
            "a refused append leaves the committed journal exactly as it was",
        )
    }

    @Test
    fun `a journal built one line per statement is accepted up to its declared count`() {
        // Why the rule is "no more than declared" rather than "exactly as declared": every schema
        // fixture in this repository writes the header and then each line as its own statement, so
        // an under-count is the normal intermediate state of a journal being built. Refusing it
        // would fail those fixtures while catching nothing the loose form misses - the extra line
        // still trips the guard when it arrives, which is the last assertion here.
        val tenant = fixture.createTenant("append-guard-per-line")
        val journalId = fixture.insertJournalEntry(tenant, lineCount = THREE_LINES)

        fixture.insertJournalLine(tenant, journalId, lineNumber = 1)
        assertEquals(
            1,
            linesOf(journalId),
            "a journal holding fewer lines than it declares is legal; the guard counts only the " +
                "other direction",
        )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 2,
            glAccountId = tenant.creditAccountId,
            direction = "CREDIT",
        )
        fixture.insertJournalLine(tenant, journalId, lineNumber = THREE_LINES)
        assertEquals(
            THREE_LINES,
            linesOf(journalId),
            "a header declaring three accepts three: the guard counts against line_count, it " +
                "does not cap a journal at two lines",
        )

        val failure =
            assertFailsWith<DataIntegrityViolationException>(
                "the fourth line is the one that exceeds the declared three",
            ) {
                fixture.insertJournalLine(tenant, journalId, lineNumber = 4)
            }
        assertGuardRaised(failure, journalId)
    }

    @Test
    fun `the engine's one-statement write commits and its journal cannot afterwards grow`() {
        // The production path, driven through the real PostingEngine rather than a lookalike.
        // writeJournal inserts the header, then every line in ONE multi-row statement, then
        // verifies and marks the request posted, all inside one transaction - so the header
        // declares exactly the number of rows that statement writes and the guard sees n > n,
        // which is false. The append that follows is a second, later transaction against a journal
        // production wrote, which is the shape issue #95 is about.
        val tenant = fx.provisionTenant("append-guard-engine")
        val receipt = fx.postOriginal(tenant, debit = "750.00")

        assertEquals(
            LINES_PER_JOURNAL,
            linesOf(receipt.journalEntryId),
            "the engine's multi-row statement committed every line it prepared",
        )
        assertEquals(
            linesOf(receipt.journalEntryId),
            declaredLineCountOf(receipt.journalEntryId),
            "the engine writes line_count as the size of the line set it is about to insert, " +
                "which is what keeps the production path exactly on the guard's boundary rather " +
                "than inside it",
        )

        val failure =
            assertFailsWith<DataIntegrityViolationException>(
                "a journal the engine posted is as unable to grow as a fixture's",
            ) {
                appendClonedLine(tenant.organisationId, receipt.journalEntryId, lineNumber = 3)
            }
        assertGuardRaised(failure, receipt.journalEntryId)
    }

    @Test
    fun `a reversal writes a new journal in a later transaction and is permitted`() {
        // Limit (c) of the migration, and load-bearing rather than a caveat: ADR 0020 makes
        // correction a new REVERSAL entry, never a change to an existing one, so a guard that
        // refused a later transaction writing journal lines would make the ledger uncorrectable.
        // The reversal below is a separate transaction, through the production service, writing
        // its own header and its own lines - and the original is proved untouched by it.
        val tenant = fx.provisionTenant("append-guard-reversal")
        val original = fx.postOriginal(tenant, debit = "300.00")
        val originalRows = fx.rowsOf(tenant, original.journalEntryId)

        val reversal =
            fx.inContext(tenant, tenant.checker) {
                postingService.reverse(
                    fx.command(tenant, original.journalEntryId, reason = "Mis-keyed"),
                )
            }

        assertEquals(
            LINES_PER_JOURNAL,
            linesOf(reversal.journalEntryId),
            "the reversal's own lines are written in a transaction of their own, long after the " +
                "original committed, and the guard says nothing about a new journal_entry",
        )
        assertEquals(
            originalRows,
            fx.rowsOf(tenant, original.journalEntryId),
            "correction is a new entry, so the original keeps its two lines byte for byte",
        )
    }

    @Test
    fun `one statement may seed many journals, and is refused whole when one overshoots`() {
        // The set-based shape AccountingQueryPlanTests seeds its two hundred thousand lines with:
        // one INSERT ... SELECT feeding every journal at once. The guard reads the statement's
        // whole NEW TABLE in a single pass, so the accepted case costs one evaluation for all of
        // them; and because a trigger raising aborts the statement, the refused case takes every
        // row with it - including the rows for the journals that were within their count.
        val sound = fixture.createTenant("append-guard-set-based")
        repeat(JOURNALS_PER_STATEMENT) { fixture.insertJournalEntry(sound) }

        seedLinesInOneStatement(sound)

        assertEquals(
            JOURNALS_PER_STATEMENT * LINES_PER_JOURNAL,
            linesOfTenant(sound),
            "one statement seeded every journal's lines, each journal landing on its declared two",
        )

        val spoilt = fixture.createTenant("append-guard-set-based-spoilt")
        val journals = List(JOURNALS_PER_STATEMENT) { fixture.insertJournalEntry(spoilt) }
        val failure =
            assertFailsWith<DataIntegrityViolationException>(
                "one journal over its declared count refuses the statement that overshot it",
            ) {
                seedLinesInOneStatement(spoilt, overshooting = journals.last())
            }

        assertGuardRaised(failure, journals.last())
        assertEquals(
            0,
            linesOfTenant(spoilt),
            "the statement is refused whole: not one of its rows survives, not even the two-line " +
                "sets for the journals that never exceeded anything",
        )
    }

    @Test
    fun `raising line_count first lets the append through, the residual issue 54 closes`() {
        // Limit (a), recorded as a passing test rather than left as prose, because a reader who
        // finds this suite has to be told what it does NOT prove. This is not a defect: line_count
        // is a plain mutable column, so an actor who can UPDATE journal_entry can widen the
        // declaration and then append inside it, and the guard passes that by construction. V13
        // closes the append an INSERT-only actor can perform; issue #54's REVOKE UPDATE, DELETE on
        // the journal tables is what makes there be such an actor - and note that a REVOKE alone
        // cannot, because the application still connects as the superuser that owns these tables
        // and a grant is not consulted for an owner. The two compose, and neither is sufficient
        // alone - so this test asserts a success, and that success is the schema behaving as
        // designed rather than a hole in it.
        val tenant = fixture.createTenant("append-guard-residual")
        val journalId = transactions.execute { fixture.insertBalancedJournal(tenant) }

        dsl
            .update(JOURNAL_ENTRY)
            .set(JOURNAL_ENTRY.LINE_COUNT, THREE_LINES)
            .where(JOURNAL_ENTRY.ID.eq(journalId))
            .execute()
        fixture.insertJournalLine(tenant, journalId, lineNumber = THREE_LINES)

        assertEquals(
            THREE_LINES,
            linesOf(journalId),
            "the third line is accepted once the header declares three; the guard never reads " +
                "what the header said when the journal was posted, only what it says now",
        )
    }

    @Test
    fun `with the trigger disabled the same append succeeds, so the guard is what refuses it`() {
        // The mutation check, run rather than argued. Every refusal in this suite asserts a
        // message and a SQLSTATE, but none of that says the trigger is what produced them rather
        // than some constraint that would have caught the append anyway - and an append of a
        // distinct line_number to an existing journal breaks no key, no CHECK and no foreign key,
        // so nothing else in the schema has an opinion about it. Disabling the trigger inside a
        // transaction that is rolled back proves exactly that: the same statement lands.
        val tenant = fixture.createTenant("append-guard-mutation")
        val journalId = transactions.execute { fixture.insertBalancedJournal(tenant) }

        transactions.execute { status ->
            status.setRollbackOnly()
            dsl.execute("ALTER TABLE public.journal_line DISABLE TRIGGER $GUARD_TRIGGER")
            appendClonedLine(tenant.organisationId, journalId, lineNumber = 3)
            assertEquals(
                THREE_LINES,
                linesOf(journalId),
                "with the guard off the third line lands against a header declaring two, which " +
                    "is the state of the schema before V13 and the defect issue #95 names",
            )
        }

        assertEquals(
            LINES_PER_JOURNAL,
            linesOf(journalId),
            "the disable and the row it let through are rolled back together, so the trigger is " +
                "live again and no other test inherits a journal it never wrote",
        )
    }

    @Test
    fun `the migration's pre-install check finds a violation the trigger itself cannot`() {
        // CREATE TRIGGER does not evaluate the rows already in the table, so a journal that was
        // already over its declared count when V13 ran would sit underneath a guard asserting it
        // cannot happen. V13 therefore proves the ledger clean before installing the guard. That
        // block runs once, at migration time, against an empty database - so what is asserted here
        // is its predicate, against a violation planted the only way one can now be made.
        val tenant = fixture.createTenant("append-guard-pre-install")
        val overCount = transactions.execute { fixture.insertBalancedJournal(tenant) }
        val underCount = transactions.execute { fixture.insertJournalEntry(tenant, lineCount = 9) }

        assertTrue(
            preInstallViolations().isEmpty(),
            "a ledger written through the engine never trips the check, which is why every " +
                "deployment of V13 is expected to pass it",
        )

        transactions.execute { status ->
            status.setRollbackOnly()
            dsl.execute("ALTER TABLE public.journal_line DISABLE TRIGGER $GUARD_TRIGGER")
            appendClonedLine(tenant.organisationId, overCount, lineNumber = 3)

            assertEquals(
                listOf(overCount),
                preInstallViolations(),
                "the check names exactly the journal holding more lines than it declares",
            )
            assertTrue(
                underCount !in preInstallViolations(),
                "an under-count journal is the normal intermediate state of one being built, " +
                    "not a violation - the check uses the guard's own `>` so the two cannot " +
                    "disagree about what would have been refused",
            )
        }
    }

    @Test
    fun `the guard is the one trigger on journal_line, statement-level, with a transition table`() {
        // Asserted from the catalogue because behaviour cannot tell the two shapes apart:
        // PostgreSQL queues an AFTER ... FOR EACH ROW trigger's events and drains them when the
        // statement finishes, so a row-level version of this rule would see the same completed
        // count and would accept and refuse exactly the same statements - at one evaluation per
        // row instead of one per statement, which is a cost, not an outcome. The shape the
        // migration argues for is therefore only visible here.
        val definitions =
            dsl.fetch(TRIGGERS_ON_JOURNAL_LINE).map { it.get(0, String::class.java) }

        assertEquals(
            1,
            definitions.size,
            "V13 adds the repository's first trigger and journal_line carries no other, so a " +
                "second one appearing here is a rule nobody documented: $definitions",
        )
        val definition = definitions.single()
        listOf(
            "AFTER INSERT ON public.journal_line",
            "REFERENCING NEW TABLE AS inserted",
            "FOR EACH STATEMENT",
            "fn_journal_line_append_guard()",
        ).forEach { clause ->
            assertTrue(
                definition.contains(clause),
                "the guard is declared with \"$clause\" - the transition table is what lets one " +
                    "set-based query see the statement's own rows - but it reads: $definition",
            )
        }
    }

    @Test
    fun `the guard function is SECURITY INVOKER, pins its search_path, and returns trigger`() {
        // The three declaration choices V13 argues for, asserted rather than trusted to the file.
        // INVOKER because the function reads two tables the caller has just written to and confers
        // nothing DEFINER would add; the pinned search_path because pg_temp is searched first for
        // relations unless it is named, which the shadow test exercises; and RETURNS trigger
        // because that is what jOOQ's codegen excludes - a helper returning anything else would
        // be generated into com.finaxis.platform.jooq, which is the problem V6 moved btree_gist
        // into its own schema to avoid.
        val row = dsl.fetchSingle(GUARD_FUNCTION_DECLARATION)

        assertFalse(
            row.get(0, Boolean::class.java),
            "the guard must stay SECURITY INVOKER: a privilege boundary that would only start " +
                "working after issue #54 is not a privilege boundary, and this guard does not " +
                "depend on privileges at all",
        )
        assertEquals(
            "search_path=pg_catalog, public, pg_temp",
            row.get(1, String::class.java),
            "search_path is pinned with pg_temp named last, so a temporary table cannot take the " +
                "implicit first position and be counted in journal_line's place",
        )
        assertEquals(
            "trigger",
            row.get(2, String::class.java),
            "a function returning trigger is invisible to jOOQ codegen (includeTriggerRoutines " +
                "defaults to false), which is why the guard may live in public beside the table " +
                "it guards",
        )
    }

    @Test
    fun `a temporary table named journal_line cannot shadow the table the guard counts`() {
        // The pin earning its place, behaviourally. Without `SET search_path = ..., pg_temp` the
        // guard's unqualified journal_line would resolve to the session's temporary schema first:
        // the decoy below is empty, 0 > 2 is false, and the append would be waved through while
        // the real row landed in public. Everything happens inside one transaction so the decoy
        // cannot outlive it and reach a later test through the pool.
        val tenant = fixture.createTenant("append-guard-shadow")
        val journalId = transactions.execute { fixture.insertBalancedJournal(tenant) }

        val failure =
            transactions.execute { status ->
                status.setRollbackOnly()
                dsl.execute(TEMP_JOURNAL_LINE_DECOY)
                assertEquals(
                    0,
                    dsl.fetchCount(DSL.table(DSL.name("journal_line"))),
                    "the decoy has to really shadow an unqualified journal_line for this " +
                        "session, or the refusal below proves nothing about the pin",
                )
                assertFailsWith<DataIntegrityViolationException>(
                    "the guard counts public.journal_line, not whatever the search path finds",
                ) {
                    appendClonedLine(tenant.organisationId, journalId, lineNumber = 3)
                }
            }

        assertGuardRaised(failure, journalId)
        assertEquals(
            LINES_PER_JOURNAL,
            linesOf(journalId),
            "and the real table is where the refused row would have gone, so it still holds two",
        )
    }

    /**
     * Clones line 1 of [journalEntryId] under [lineNumber]: one more line, everything else equal.
     *
     * Schema-qualified throughout, because one test deliberately puts a `journal_line` in the
     * session's temporary schema and this statement must keep naming the real table.
     */
    private fun appendClonedLine(
        organisationId: UUID,
        journalEntryId: UUID,
        lineNumber: Int,
    ) = dsl.execute(
        """
        INSERT INTO public.journal_line (
            organisation_id, journal_entry_id, line_number, gl_account_id, branch_id,
            fiscal_period_id, posting_date, direction, currency_code, amount,
            functional_currency_code, functional_amount, exchange_rate, source_module, created_at
        )
        SELECT l.organisation_id, l.journal_entry_id, ?, l.gl_account_id, l.branch_id,
               l.fiscal_period_id, l.posting_date, l.direction, l.currency_code, l.amount,
               l.functional_currency_code, l.functional_amount, l.exchange_rate, l.source_module,
               NOW()
        FROM public.journal_line l
        WHERE l.organisation_id = ? AND l.journal_entry_id = ? AND l.line_number = 1
        """.trimIndent(),
        lineNumber,
        organisationId,
        journalEntryId,
    )

    /**
     * Writes two lines for every one of [tenant]'s journals that has none yet, in one statement,
     * and a third for [overshooting] so that one journal exceeds the two its header declares.
     */
    private fun seedLinesInOneStatement(
        tenant: JournalSchemaFixture.Tenant,
        overshooting: UUID = NO_SUCH_JOURNAL,
    ) = dsl.execute(
        SEED_LINES_SQL,
        tenant.debitAccountId,
        tenant.organisationId,
        overshooting,
    )

    private fun linesOf(journalEntryId: UUID): Int =
        dsl.fetchCount(JOURNAL_LINE, JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(journalEntryId))

    private fun linesOfTenant(tenant: JournalSchemaFixture.Tenant): Int =
        dsl.fetchCount(JOURNAL_LINE, JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))

    private fun declaredLineCountOf(journalEntryId: UUID): Int =
        dsl
            .select(JOURNAL_ENTRY.LINE_COUNT)
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ID.eq(journalEntryId))
            .fetchOne(JOURNAL_ENTRY.LINE_COUNT)!!

    /**
     * Asserts that [failure] is the append guard refusing a statement over [journalEntryId].
     *
     * Both halves, because either alone would pass for the wrong reason: the SQLSTATE says the
     * failure is the one the migration promises rather than some other integrity violation that
     * happened to fire first, and the message says which journal overshot.
     */
    private fun assertGuardRaised(
        failure: DataIntegrityViolationException,
        journalEntryId: UUID,
    ) {
        val message = failure.mostSpecificCause.message.orEmpty()
        val sqlState =
            generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SQLException>()
                .firstOrNull()
                ?.sqlState
        assertEquals(
            CHECK_VIOLATION,
            sqlState,
            "the guard raises ERRCODE check_violation so it lands in the same class as the CHECK " +
                "constraints beside it, which is also what makes Spring render it as a " +
                "DataIntegrityViolationException rather than an uncategorised failure: $message",
        )
        assertTrue(
            message.contains(GUARD_MESSAGE),
            "a trigger has no constraint name to assert, so the raised message is what a reader " +
                "recognises and what these tests pin: $message",
        )
        assertTrue(
            message.contains(journalEntryId.toString()),
            "the message names the journal that overshot, or the refusal cannot be diagnosed " +
                "from a log line: $message",
        )
    }

    private fun preInstallViolations(): List<UUID> =
        dsl.fetch(PRE_INSTALL_VIOLATIONS).map { it.get(0, UUID::class.java) }

    private companion object {
        /** SQLSTATE 23514, the `check_violation` `V13` raises deliberately. */
        const val CHECK_VIOLATION = "23514"

        /** The tail of `V13`'s `RAISE EXCEPTION`; renaming it renames this constant. */
        const val GUARD_MESSAGE = "a committed journal cannot grow a line"

        /** `V13`'s trigger, named here only to turn it off inside a rolled-back transaction. */
        const val GUARD_TRIGGER = "trg_journal_line_append_guard"

        const val LINES_PER_JOURNAL = 2
        const val THREE_LINES = 3
        const val JOURNALS_PER_STATEMENT = 4

        /** A journal id no row carries, so the set-based seed writes no third line by default. */
        val NO_SUCH_JOURNAL: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

        /**
         * `V13`'s pre-install predicate, verbatim: journals holding more lines than they declare.
         *
         * The migration runs this once, before `CREATE TRIGGER`, and refuses to install the guard
         * if it answers anything. Restated here because `CREATE TRIGGER` does not evaluate rows
         * already in the table, so nothing else in this suite can reach that code path - Flyway
         * has long finished, against an empty ledger, by the time a test runs.
         */
        val PRE_INSTALL_VIOLATIONS =
            """
            SELECT counted.journal_entry_id
            FROM (
                SELECT line.organisation_id, line.journal_entry_id, count(*) AS present
                FROM journal_line line
                GROUP BY line.organisation_id, line.journal_entry_id
            ) counted
            JOIN journal_entry entry
              ON entry.organisation_id = counted.organisation_id
             AND entry.id = counted.journal_entry_id
            WHERE counted.present > entry.line_count
            """.trimIndent()

        /** Every non-internal trigger on `journal_line`; the RI triggers are `tgisinternal`. */
        val TRIGGERS_ON_JOURNAL_LINE =
            """
            SELECT pg_get_triggerdef(t.oid)
            FROM pg_trigger t
            JOIN pg_class c ON c.oid = t.tgrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public' AND c.relname = 'journal_line' AND NOT t.tgisinternal
            """.trimIndent()

        /** `prosecdef`, the pinned `search_path` and the return type of the guard function. */
        val GUARD_FUNCTION_DECLARATION =
            """
            SELECT p.prosecdef, array_to_string(p.proconfig, '; '),
                   pg_catalog.format_type(p.prorettype, NULL)
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = 'public' AND p.proname = 'fn_journal_line_append_guard'
            """.trimIndent()

        /** Same name, same two columns the guard reads, no rows: the shadow that must not count. */
        val TEMP_JOURNAL_LINE_DECOY =
            """
            CREATE TEMP TABLE journal_line (
                organisation_id UUID,
                journal_entry_id UUID
            ) ON COMMIT DROP
            """.trimIndent()

        /** One statement, every journal of a tenant that has no lines yet; see the helper. */
        val SEED_LINES_SQL =
            """
            INSERT INTO public.journal_line (
                organisation_id, journal_entry_id, line_number, gl_account_id, branch_id,
                fiscal_period_id, posting_date, direction, currency_code, amount,
                functional_currency_code, functional_amount, exchange_rate, source_module,
                created_at
            )
            SELECT e.organisation_id, e.id, leg, ?, e.branch_id, e.fiscal_period_id,
                   e.posting_date, CASE WHEN leg = 1 THEN 'DEBIT' ELSE 'CREDIT' END,
                   'KES', 50, 'KES', 50, 1, 'savings', NOW()
            FROM public.journal_entry e
            CROSS JOIN generate_series(1, 3) AS leg
            WHERE e.organisation_id = ?
              AND NOT EXISTS (
                  SELECT 1 FROM public.journal_line l
                  WHERE l.organisation_id = e.organisation_id AND l.journal_entry_id = e.id
              )
              AND (leg <= 2 OR e.id = ?)
            """.trimIndent()
    }
}
