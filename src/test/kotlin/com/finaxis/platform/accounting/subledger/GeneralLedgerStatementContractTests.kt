package com.finaxis.platform.accounting.subledger

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerPosition
import com.finaxis.platform.accounting.SubledgerStatementCursor
import com.finaxis.platform.accounting.SubledgerStatementProvider
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The conformance suite, run against the general ledger's own implementation of it.
 *
 * This is what keeps [SubledgerStatementContract] from being a document with a test-shaped wrapper:
 * every obligation it states is demonstrated here against PostgreSQL, so a future product module
 * extending the suite is inheriting assertions that are known to be satisfiable rather than merely
 * reasonable.
 *
 * The seeding writes journal lines directly. These tests are about what a *statement* makes of a
 * set of lines, and they need posting dates and recording dates chosen freely — a movement recorded
 * four days after the day it lands on is the case the suite exists for, and the engine would refuse
 * to let a test arrange it.
 *
 * [statementRead] is overridden here with a real `REPEATABLE_READ` transaction, so every inherited
 * test runs the way `SubledgerStatementAssembler.statementOf`'s own KDoc now says a caller must:
 * one transaction, sharing one snapshot between
 * [SubledgerStatementProvider.recordedBehindConsumedRange] and
 * [SubledgerStatementProvider.movements]. Without it, every mid-walk case above — `a page is
 * refused once the ledger has recorded behind its cursor` among them — would fail
 * [com.finaxis.platform.accounting.adapter.outbound.persistence.PostgresProofSnapshot]'s guard
 * before it reached the assertion the test is actually about.
 *
 * A class-level `@Transactional(isolation = REPEATABLE_READ)` was tried first and does not work
 * here: Spring Test resolves a test method's transactional attributes from the method's own
 * declaring class, and every mid-walk case is declared on [SubledgerStatementContract], not this
 * class, so the class-level annotation is silently never applied to them. [statementRead] sidesteps
 * that entirely - it is plain method delegation, not a test-framework annotation, so it applies to
 * an inherited test exactly as it applies to one declared here.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GeneralLedgerStatementContractTests(
    private val dsl: DSLContext,
    private val statements: SubledgerStatementProvider,
    private val transactionManager: PlatformTransactionManager,
) : SubledgerStatementContract() {
    private val fixture = JournalSchemaFixture(dsl)

    /** Every read runs in its own `REPEATABLE_READ` transaction - see the class KDoc for why. */
    private val statementTransactions =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        }

    override fun <T> statementRead(block: () -> T): T = statementTransactions.execute { block() }

    // JUnit builds a fresh instance of this class per test method (no `@TestInstance(PER_CLASS)`
    // here), so caching the seeded tenant on an instance field is safe: it never outlives the one
    // test that populated it, and `record` below is only ever called after `newPosition` has run.
    private lateinit var tenant: JournalSchemaFixture.Tenant

    override fun provider(): SubledgerStatementProvider = statements

    override fun newPosition(): SubledgerPosition {
        tenant = fixture.createTenant("statement-contract")
        val reference = "POS-${UUID.randomUUID()}"
        // The position lives on a control account, which is what makes its class name it:
        // `uq_gl_account_control_kind` allows one per tenant per class.
        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.IS_CONTROL_ACCOUNT, true)
            .set(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND, ControlSubledgerKind.SAVINGS_DEPOSITS.name)
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(tenant.organisationId))
            .and(GL_ACCOUNT.ID.eq(tenant.creditAccountId))
            .execute()
        return SubledgerPosition(
            organisationId = tenant.organisationId,
            ownerModule = OWNER_MODULE,
            kind = ControlSubledgerKind.SAVINGS_DEPOSITS,
            reference = reference,
        )
    }

    override fun record(
        position: SubledgerPosition,
        movements: List<SeededMovement>,
    ) {
        val reference = position.reference
        movements.forEach { movement ->
            // A signed movement becomes an ordinary balanced journal. The position's leg always
            // sits on the control account and changes *direction* with the sign - it does not move
            // to another account, which is what a real position does and what makes the reversal
            // case meaningful: a positive amount against a negative position IS the reversal.
            val credit = movement.signedAmount.signum() < 0
            val amount = movement.signedAmount.abs()
            val journalId =
                fixture.insertJournalEntry(
                    tenant,
                    totalDebit = amount,
                    totalCredit = amount,
                    businessDate = movement.recordedOn,
                    postingDate = movement.postingDate,
                )
            fixture.insertJournalLine(
                tenant,
                journalId,
                lineNumber = 1,
                glAccountId = tenant.creditAccountId,
                direction = if (credit) "CREDIT" else "DEBIT",
                amount = amount,
                postingDate = movement.postingDate,
                subledgerReference = reference,
            )
            // The contra leg carries the reference too, because `PostingRulePolicy.allocate`
            // stamps a fact's position reference on every leg it drives - both sides of the same
            // fact. A fixture that referenced only the position's own leg would hide the defect
            // this restriction exists for: matching on the reference alone selects both halves of
            // a balanced posting and sums them to zero.
            fixture.insertJournalLine(
                tenant,
                journalId,
                lineNumber = 2,
                glAccountId = tenant.debitAccountId,
                direction = if (credit) "DEBIT" else "CREDIT",
                amount = amount,
                postingDate = movement.postingDate,
                subledgerReference = reference,
            )
        }
    }

    /**
     * Fail-closed, not silently short. A stale-cursor check with no active transaction at all
     * cannot share a snapshot with the [SubledgerStatementProvider.movements] read that follows
     * it, so it must refuse rather than answer. This test calls the adapter directly - no
     * [statementRead] involved - which is exactly what "no transaction" means here.
     *
     * The position and cursor are otherwise arbitrary:
     * [com.finaxis.platform.accounting.adapter.outbound.persistence.PostgresProofSnapshot] asks the
     * database what isolation is in force before this adapter's query runs at all, so nothing about
     * this cursor's own content - or whether a movement matching it actually exists - is reachable.
     */
    @Test
    fun `the stale-cursor check refuses to run with no transaction at all`() {
        val position = newPosition()
        val cursor = SubledgerStatementCursor(DAY_1, UUID.randomUUID(), ARBITRARY_WATERMARK)

        assertFailsWith<IllegalStateException> {
            statements.recordedBehindConsumedRange(position, null, cursor)
        }
    }

    /**
     * The other half of the guard: a transaction *is* active, but at the server default of `READ
     * COMMITTED` rather than the `REPEATABLE_READ` [SubledgerStatementProvider
     * .recordedBehindConsumedRange]'s KDoc requires. This is the failure [statementRead]'s own
     * `REPEATABLE_READ` template cannot itself demonstrate - it always asks for the right
     * isolation - so this test opens its own bare [TransactionTemplate] at the server default
     * instead, exactly as `PostingTransactionContractIntegrationTests` does for the posting path.
     */
    @Test
    fun `the stale-cursor check refuses to run at less than repeatable read`() {
        val position = newPosition()
        val cursor = SubledgerStatementCursor(DAY_1, UUID.randomUUID(), ARBITRARY_WATERMARK)

        val refusal =
            assertFailsWith<ConflictException> {
                TransactionTemplate(transactionManager).execute {
                    statements.recordedBehindConsumedRange(position, null, cursor)
                }
            }

        assertEquals(PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE, refusal.code)
    }

    /** The watermark-minting twin of the two tests above: no transaction at all. */
    @Test
    fun `minting a watermark refuses to run with no transaction at all`() {
        assertFailsWith<IllegalStateException> { statements.currentWatermark() }
    }

    /** The watermark-minting twin of the two tests above: a transaction, but too weak. */
    @Test
    fun `minting a watermark refuses to run at less than repeatable read`() {
        val refusal =
            assertFailsWith<ConflictException> {
                TransactionTemplate(transactionManager).execute { statements.currentWatermark() }
            }

        assertEquals(PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE, refusal.code)
    }

    /**
     * The exact defect issue #142's Codex review found, proven against real PostgreSQL rather than
     * merely reasoned about: a transaction that acquires its transaction id *before* a walk's
     * watermark is minted, but does not commit its backdated row until *after*, must still be
     * caught by the next page's stale-cursor check.
     *
     * An id-order watermark - what this method compared against before this fix - would miss this
     * outright: `writer`'s row gets whatever `uuidv7()` id `INSERT` assigns it at commit time, and
     * nothing about that id encodes that the transaction *holding* it had already started before
     * [SubledgerStatementCursor.recordedThrough] was minted. `pg_visible_in_snapshot` asks
     * PostgreSQL's own MVCC machinery instead, from the row's `xmin` and the stored snapshot token,
     * which only reason about commit visibility and are correct by construction.
     *
     * Latch-driven rather than `pg_sleep`-timed, following
     * `FiscalPeriodConcurrencyIntegrationTests`: `transactionStarted` fires only once `writer`'s
     * transaction has actually acquired an id
     * (`txid_current()` forces assignment immediately, before the row it backdates is ever
     * inserted), so the watermark this test mints is provably captured while that transaction is
     * still open - not merely likely to be, on a fast enough machine.
     */
    @Test
    fun `a movement whose id predates the watermark but commits after it is still caught`() {
        val position = newPosition()
        settle(position)
        val transactionStarted = CountDownLatch(1)
        val watermarkMinted = CountDownLatch(1)
        lateinit var watermark: String

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val writer =
                executor.submit {
                    TransactionTemplate(transactionManager).execute {
                        dsl.fetchValue(DSL.field("txid_current()", Long::class.java))
                        transactionStarted.countDown()
                        assertTrue(watermarkMinted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        record(position, listOf(SeededMovement(DAY_1, DAY_1, BigDecimal("-5.00"))))
                    }
                }
            assertTrue(transactionStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            watermark = statementTransactions.execute { statements.currentWatermark() }
            watermarkMinted.countDown()
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        val cursor = SubledgerStatementCursor(DAY_5, UUID.randomUUID(), watermark)
        val stale =
            statementTransactions.execute {
                statements.recordedBehindConsumedRange(position, null, cursor)
            }

        assertTrue(
            stale,
            "the backdated movement committed after the watermark and must be caught, even " +
                "though its transaction started - and so acquired its id - before the watermark " +
                "was minted",
        )
    }

    private companion object {
        /** `JournalSchemaFixture` stamps every line it writes with this module. */
        const val OWNER_MODULE = "savings"

        /** Content the guard call refuses on before it is ever read. */
        const val ARBITRARY_WATERMARK = "arbitrary"

        const val TIMEOUT_SECONDS = 10L
    }
}
