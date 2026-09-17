package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.FunctionalCurrencyLock
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.accounting.application.UpdateGlAccountCommand
import com.finaxis.platform.accounting.application.ledger.JournalNumberAllocator
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.JournalStore
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.NewJournalLine
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.port.outbound.PostingMetadataLookup
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.ChartHierarchyPolicy
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.AtomicityProbe
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.accounting.support.PostingTenantFixture
import com.finaxis.platform.accounting.support.PostingTenantFixture.Tenant
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The posting engine against PostgreSQL, through the production wiring.
 *
 * What is proved here and nowhere else: a posting writes exactly one request, header and line set
 * atomically and invisibly until commit; every refusal leaves nothing behind; a fake product
 * sub-ledger write and the journal roll back together; the verification read is enforced against
 * real rows; a retry replays and a conflicting reuse is refused; and the freeze the journal creates
 * over account identity holds once a line exists.
 *
 * The journal's *other* freeze - the tenant's functional currency - has its own suite,
 * [FunctionalCurrencyFreezeIntegrationTests], which is also where the property issue #108 gave up
 * in exchange for `SERIALIZABLE` is recorded.
 *
 * Every write path registers its durable effects as probes in
 * [FinancialTransactionAtomicityFixture], per
 * `docs/architecture/financial-transaction-atomicity.md`.
 *
 * Every posting here opens its transaction the way production does, through
 * [PostingTransactionBoundary] or through the atomicity fixture raised to the same level. There is
 * deliberately no `READ COMMITTED` [org.springframework.transaction.support.TransactionTemplate] in
 * this suite: [PostingEngine.post] refuses one outright, so a test that reached for it would be
 * proving the refusal rather than the engine. That refusal has its own suite,
 * [PostingTransactionContractIntegrationTests].
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingEngineIntegrationTests(
    private val engine: PostingEngine,
    private val postingService: PostingService,
    private val journals: JournalStore,
    private val ledger: JournalReadStore,
    private val contextLookup: AccountingContextLookup,
    private val metadata: PostingMetadataLookup,
    private val currencyLock: FunctionalCurrencyLock,
    private val tenantLookup: AccountingTenantLookup,
    private val periodResolver: PostingPeriodResolver,
    private val accounts: GlAccountStore,
    private val numbers: JournalNumberAllocator,
    private val clock: Clock,
    private val snapshots: SnapshotIsolationGuard,
    private val postingTransactions: PostingTransactionBoundary,
    private val chartOfAccounts: ChartOfAccountsService,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val posting =
        PostingTenantFixture(dsl, organisationProvisioningService, engine, ACTOR)

    private val schema = JournalSchemaFixture(dsl)

    @Test
    fun `a balanced posting is written once, invisibly until commit, with a gapless number`() {
        val tenant = posting.provisionTenant("engine-balanced")
        val harness = harness(tenant)
        lateinit var receipt: PostingReceipt

        harness.assertVisibleOnlyAfterCommit(
            expectedDeltas =
                mapOf(
                    "posting_request" to 1L,
                    "journal_entry" to 1L,
                    "journal_line" to 2L,
                    "reference_sequence[JOURNAL]" to 1L,
                ),
        ) {
            receipt = posting.inContext(tenant) { posting.post(tenant, reference = "dep-1") }
        }

        assertEquals("1", receipt.journalReference, "the first journal takes number one")
        assertEquals(2, receipt.lineCount)
        assertEquals(tenant.businessDate, receipt.businessDate)
        val header =
            requireNotNull(ledger.findJournalEntry(tenant.organisationId, receipt.journalEntryId))
        assertEquals(1L, header.entryNumber)
        assertEquals(JournalEntryType.STANDARD, header.entryType)
        assertEquals(tenant.periodId, header.fiscalPeriodId)
        assertEquals(tenant.branchId, header.branchId)
        assertEquals(BigDecimal("500.000000"), header.totalDebitFunctional)
        assertEquals("KES", header.functionalCurrencyCode)
        val lines = ledger.findJournalLines(tenant.organisationId, receipt.journalEntryId)
        assertEquals(listOf(1, 2), lines.map { it.lineNumber })
        assertEquals(listOf(PostingSide.DEBIT, PostingSide.CREDIT), lines.map { it.side })
        assertEquals(
            "POSTED",
            dsl
                .select(POSTING_REQUEST.STATUS)
                .from(POSTING_REQUEST)
                .where(POSTING_REQUEST.ID.eq(receipt.postingRequestId))
                .fetchOne(POSTING_REQUEST.STATUS),
        )

        // Numbering is gapless and per tenant: the next journal takes two. Sequential on purpose -
        // two *overlapping* postings in one tenant contend on the single `reference_sequence` row,
        // and at SERIALIZABLE the loser aborts with 40001 rather than waiting.
        val next =
            posting.inContext(tenant) {
                postingTransactions.execute("The second posting") {
                    posting.post(tenant, reference = "dep-2")
                }
            }
        assertEquals("2", next.journalReference)
    }

    @Test
    fun `unbalanced inactive-account closed-period and foreign-currency postings commit nothing`() {
        // Every case here asserts the error *code*, not merely the exception class, and that is
        // load-bearing now rather than thorough. `accounting.snapshot_isolation_unavailable` is
        // also a ConflictException, so a harness left at READ COMMITTED would refuse each of these
        // postings before it ever reached the rule under test and the closed-period case would go
        // green for a reason that has nothing to do with the period. The code is what tells the
        // two apart.
        val tenant = posting.provisionTenant("engine-refusals")
        val harness = harness(tenant)

        val unbalanced =
            harness.assertRollsBackAtomically(InvalidOperationException::class) {
                posting.inContext(tenant) { posting.post(tenant, credit = "499.00") }
            }
        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, unbalanced.code)

        val foreign =
            harness.assertRollsBackAtomically(InvalidOperationException::class) {
                posting.inContext(tenant) { posting.post(tenant, currency = "USD") }
            }
        assertEquals(PostingErrorCodes.CURRENCY_NOT_SUPPORTED, foreign.code)

        val inactive = schema.insertAccount(tenant.organisationId, "1999", "ASSET")
        dsl
            .update(com.finaxis.platform.jooq.tables.references.GL_ACCOUNT)
            .set(com.finaxis.platform.jooq.tables.references.GL_ACCOUNT.STATUS, "INACTIVE")
            .where(
                com.finaxis.platform.jooq.tables.references.GL_ACCOUNT.ID
                    .eq(inactive),
            ).execute()
        val notPostable =
            harness.assertRollsBackAtomically(InvalidOperationException::class) {
                posting.inContext(tenant) { posting.post(tenant, debitAccount = inactive) }
            }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, notPostable.code)

        setPeriodStatus(tenant, "CLOSED")
        val closed =
            harness.assertRollsBackAtomically(ConflictException::class) {
                posting.inContext(tenant) { posting.post(tenant) }
            }
        assertEquals(PostingErrorCodes.PERIOD_CLOSED, closed.code)
        setPeriodStatus(tenant, "OPEN")
    }

    @Test
    fun `a product sub-ledger write and the journal roll back together`() {
        // The atomicity invariant from the product module's side. There is no product module yet,
        // so its position is stood in for by a reference_sequence row the "product" increments in
        // the same transaction as the posting. A failure after both leaves neither.
        val tenant = posting.provisionTenant("engine-subledger")
        seedFakeSubledger(tenant)
        val harness =
            FinancialTransactionAtomicityFixture(
                dsl,
                transactionManager,
                ledgerProbes(tenant) + fakeSubledgerPosition(tenant),
                TransactionDefinition.ISOLATION_SERIALIZABLE,
            )
        val before = harness.snapshot()

        harness.assertRollsBackAtomically(IllegalStateException::class) {
            posting.inContext(tenant) {
                posting.post(tenant)
                moveFakeSubledger(tenant)
                error("simulated failure after the sub-ledger and the journal were both written")
            }
        }
        assertEquals(before, harness.snapshot())

        // And the positive twin: when nothing fails, both commit, and only after commit.
        harness.assertVisibleOnlyAfterCommit(
            expectedDeltas =
                mapOf(
                    "posting_request" to 1L,
                    "journal_entry" to 1L,
                    "journal_line" to 2L,
                    "reference_sequence[JOURNAL]" to 1L,
                    "reference_sequence[TEST_SUBLEDGER]" to 1L,
                ),
        ) {
            posting.inContext(tenant) {
                posting.post(tenant, reference = "dep-ok")
                moveFakeSubledger(tenant)
            }
        }
    }

    @Test
    fun `a journal whose stored lines disagree with its header is rolled back`() {
        // The INV-4 enforcement point against real rows, in both of its halves. A store that
        // corrupts the lines on the way in stands in for a defect between the engine's in-memory
        // set and what reached the database; the header CHECKs cannot see it, the verification read
        // must. Built from the real beans plus the faulty store, so everything else - and in
        // particular the SQL that does the counting - is the production path.
        //
        // Money is the half a dropped line proves. The other half is the dimensions `journal_line`
        // denormalises - branch, fiscal period, posting date and both currency codes - which the
        // reporting reads filter and group on directly, and which nothing in the schema defends:
        // the foreign keys tie a line to a *valid* branch and period, never to *its header's*. A
        // line that balanced but landed in the wrong period is invisible to every CHECK and wrong
        // in every report, and these counts are the only thing that sees it.
        //
        // The null branch is the case that holds the production predicate to `IS DISTINCT FROM`.
        // `branch_id <> '<header branch>'` is NULL for a line carrying no branch, so a plain `<>`
        // would not count that line, the divergence count would stay zero, and the posting would
        // commit with a line no branch report will ever show.
        //
        // Every case rolls back whole: the harness re-reads `posting_request`, `journal_entry`,
        // `journal_line` and the JOURNAL counter from a second connection, so "left the request
        // unposted" is asserted rather than assumed.
        val tenant = posting.provisionTenant("engine-verification")
        // A real period, in a fiscal year of its own so it overlaps nothing:
        // `fk_journal_line_fiscal_period` would refuse an invented id, and the posting would then
        // fail for a reason that says nothing about the verification read.
        val other =
            posting.openPeriodCovering(tenant.organisationId, tenant.businessDate.plusYears(1))

        assertLineDefectRefused(tenant, "does not match its lines") { it.dropLast(1) }
        assertLineDefectRefused(tenant, "header on fiscal period") {
            it.map { line -> line.copy(fiscalPeriodId = other) }
        }
        assertLineDefectRefused(tenant, "header on posting date") {
            it.map { line -> line.copy(postingDate = line.postingDate.minusDays(1)) }
        }
        assertLineDefectRefused(tenant, "header on functional currency") {
            it.map { line -> line.copy(functionalCurrencyCode = "USD") }
        }
        assertLineDefectRefused(tenant, "header on branch") {
            it.map { line -> line.copy(branchId = null) }
        }
    }

    @Test
    fun `a retry replays the existing journal and a conflicting reuse is refused`() {
        val tenant = posting.provisionTenant("engine-retry")
        val harness = harness(tenant)
        val first =
            posting.inContext(tenant) {
                postingTransactions.execute("The first posting") {
                    posting.post(tenant, reference = "dep-7")
                }
            }
        val after = harness.snapshot()

        // The retry after a lost response: same reference, same request. Sequential, so the claim
        // meets a row committed before its own snapshot and replays rather than aborting.
        val replay =
            posting.inContext(tenant) {
                postingTransactions.execute("The replayed posting") {
                    posting.post(tenant, reference = "dep-7")
                }
            }
        assertEquals(first, replay, "a retry returns the receipt of the journal already posted")
        assertEquals(after, harness.snapshot(), "a retry writes nothing and burns no number")

        // The same reference for a different request.
        val conflict =
            harness.assertRollsBackAtomically(ConflictException::class) {
                posting.inContext(tenant) {
                    posting.post(tenant, reference = "dep-7", debit = "1.00", credit = "1.00")
                }
            }
        assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, conflict.code)
    }

    @Test
    fun `the caller's context must match the request context`() {
        val tenant = posting.provisionTenant("engine-context")
        val other = posting.provisionTenant("engine-context-other")
        val harness = harness(tenant)

        val failure =
            harness.assertRollsBackAtomically(ForbiddenOperationException::class) {
                posting.inContext(tenant) {
                    engine.post(
                        LedgerPostingRequest(
                            context = posting.context(other),
                            source = posting.source("dep-x"),
                            eventCode = "SAVINGS_DEPOSIT",
                            entryType = JournalEntryType.STANDARD,
                        ),
                    ) { ResolvedLegs(posting.legs(tenant), null) }
                }
            }
        assertEquals(PostingErrorCodes.CONTEXT_MISMATCH, failure.code)
    }

    @Test
    fun `the public service refuses an intent no posting rule resolves`() {
        // A tenant with no rule for the event: the resolver names the gap, and nothing is written.
        val tenant = posting.provisionTenant("engine-no-rules")
        val harness = harness(tenant)

        val failure =
            harness.assertRollsBackAtomically(InvalidOperationException::class) {
                posting.inContext(tenant) {
                    postingService.post(
                        PostFinancialFactsCommand(
                            context = posting.context(tenant),
                            source = posting.source("dep-facts"),
                            intent =
                                PostingIntent.Facts(
                                    "SAVINGS_DEPOSIT",
                                    listOf(
                                        FinancialFact(
                                            "PRINCIPAL",
                                            MonetaryAmount(BigDecimal("500.00"), "KES"),
                                        ),
                                    ),
                                ),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_NOT_FOUND, failure.code)
    }

    @Test
    fun `the request id of the originating request is recorded on the posting it produced`() {
        // The lineage issue #94 asks for: from an HTTP request id to the posting it caused. The
        // engine observes the ambient request rather than being told, so no product module has to
        // carry a value it does not hold.
        val tenant = posting.provisionTenant("engine-request-id")

        val receipt =
            posting.inContext(tenant, requestId = "req-a1b2c3") {
                postingTransactions.execute("A posting") {
                    posting.post(tenant, reference = "dep-req-id")
                }
            }

        assertEquals(
            "req-a1b2c3",
            dsl
                .select(POSTING_REQUEST.REQUEST_ID)
                .from(POSTING_REQUEST)
                .where(POSTING_REQUEST.ID.eq(receipt.postingRequestId))
                .fetchOne(POSTING_REQUEST.REQUEST_ID),
        )
    }

    @Test
    fun `a posting with no ambient request records no request id`() {
        val tenant = posting.provisionTenant("engine-no-request-id")

        val receipt =
            posting.inContext(tenant) {
                postingTransactions.execute("A posting") {
                    posting.post(tenant, reference = "dep-no-req")
                }
            }

        assertNull(
            dsl
                .select(POSTING_REQUEST.REQUEST_ID)
                .from(POSTING_REQUEST)
                .where(POSTING_REQUEST.ID.eq(receipt.postingRequestId))
                .fetchOne(POSTING_REQUEST.REQUEST_ID),
            "request_id is nullable precisely so a background posting can leave it empty",
        )
    }

    @Test
    fun `an account with a posted line keeps its code class and usage`() {
        // The second half of "has been used", answerable now that journal_line exists. The name
        // stays editable: it changes nothing about what posted or where it rolls up.
        val tenant = posting.provisionTenant("engine-identity-freeze")
        posting.inContext(tenant) {
            postingTransactions.execute("A posting") { posting.post(tenant) }
        }

        val failure =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    chartOfAccounts.update(
                        UpdateGlAccountCommand(
                            organisationId = tenant.organisationId,
                            actorId = ACTOR,
                            accountId = tenant.debitAccountId,
                            code = AccountCode("1011"),
                        ),
                    )
                }
            }
        assertEquals(ChartHierarchyPolicy.IMMUTABLE, failure.code)

        withRequestContext {
            chartOfAccounts.update(
                UpdateGlAccountCommand(
                    organisationId = tenant.organisationId,
                    actorId = ACTOR,
                    accountId = tenant.debitAccountId,
                    name = "Cash at counter",
                ),
            )
        }
        assertEquals(
            "Cash at counter",
            accounts.findById(tenant.organisationId, tenant.debitAccountId)?.name,
        )
    }

    // ---- helpers ----------------------------------------------------------------------------

    /**
     * The atomicity gate over the ledger probes, with its own transaction raised to `SERIALIZABLE`.
     *
     * The isolation belongs to the *fixture*, not to this suite. Both entry points run the caller's
     * operation inside the fixture's own [TransactionTemplate], so a test that raised only a
     * template of its own would still drive the posting at `READ COMMITTED` - and would pass the
     * gate while proving the opposite of what it claims.
     */
    private fun harness(tenant: Tenant) =
        FinancialTransactionAtomicityFixture(
            dsl,
            transactionManager,
            ledgerProbes(tenant),
            TransactionDefinition.ISOLATION_SERIALIZABLE,
        )

    private fun ledgerProbes(tenant: Tenant) =
        listOf(
            FoundationAtomicityProbes.postingRequestRows(tenant.organisationId),
            FoundationAtomicityProbes.journalEntryRows(tenant.organisationId),
            FoundationAtomicityProbes.journalLineRows(tenant.organisationId),
            FoundationAtomicityProbes.journalSequenceValue(tenant.organisationId),
        )

    private fun setPeriodStatus(
        tenant: Tenant,
        status: String,
    ) {
        dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, status)
            .where(ACCOUNTING_FISCAL_PERIOD.ID.eq(tenant.periodId))
            .execute()
    }

    private fun seedFakeSubledger(tenant: Tenant) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(REFERENCE_SEQUENCE)
            .set(REFERENCE_SEQUENCE.ORGANISATION_ID, tenant.organisationId)
            .set(REFERENCE_SEQUENCE.SEQUENCE_CODE, FAKE_SUBLEDGER_CODE)
            .set(REFERENCE_SEQUENCE.NEXT_VALUE, 1L)
            .set(REFERENCE_SEQUENCE.CREATED_AT, now)
            .set(REFERENCE_SEQUENCE.UPDATED_AT, now)
            .execute()
    }

    /** The "product module" moving its position in the same transaction as the posting. */
    private fun moveFakeSubledger(tenant: Tenant) {
        dsl
            .update(REFERENCE_SEQUENCE)
            .set(REFERENCE_SEQUENCE.NEXT_VALUE, REFERENCE_SEQUENCE.NEXT_VALUE.plus(1))
            .where(REFERENCE_SEQUENCE.ORGANISATION_ID.eq(tenant.organisationId))
            .and(REFERENCE_SEQUENCE.SEQUENCE_CODE.eq(FAKE_SUBLEDGER_CODE))
            .execute()
    }

    private fun fakeSubledgerPosition(tenant: Tenant) =
        AtomicityProbe("reference_sequence[$FAKE_SUBLEDGER_CODE]") { dsl ->
            dsl
                .select(REFERENCE_SEQUENCE.NEXT_VALUE)
                .from(REFERENCE_SEQUENCE)
                .where(REFERENCE_SEQUENCE.ORGANISATION_ID.eq(tenant.organisationId))
                .and(REFERENCE_SEQUENCE.SEQUENCE_CODE.eq(FAKE_SUBLEDGER_CODE))
                .fetchOne(REFERENCE_SEQUENCE.NEXT_VALUE) ?: 0L
        }

    /**
     * Writes the lines [corrupt] returns instead of the ones the engine prepared, standing in for a
     * defect between the engine's in-memory set and what reaches `journal_line`.
     *
     * One seam covers both halves of the verification read: dropping a line breaks the totals and
     * the count, while rewriting a denormalised column on every line leaves those intact and breaks
     * only the agreement with the header.
     */
    private class FaultyLineJournalStore(
        private val delegate: JournalStore,
        private val corrupt: (List<NewJournalLine>) -> List<NewJournalLine>,
    ) : JournalStore by delegate {
        override fun insertJournalLines(lines: List<NewJournalLine>) =
            delegate.insertJournalLines(corrupt(lines))
    }

    /** The production engine with one port swapped, so every other collaborator stays real. */
    private fun engineWith(store: JournalStore) =
        PostingEngine(
            contextLookup,
            metadata,
            tenantLookup,
            currencyLock,
            periodResolver,
            accounts,
            store,
            ledger,
            numbers,
            clock,
            snapshots,
        )

    /**
     * Posts through a store that applies [corrupt] and asserts the verification read refused it
     * with a message containing [expected], having committed nothing.
     */
    private fun assertLineDefectRefused(
        tenant: Tenant,
        expected: String,
        corrupt: (List<NewJournalLine>) -> List<NewJournalLine>,
    ) {
        val faulty = engineWith(FaultyLineJournalStore(journals, corrupt))
        val failure =
            try {
                harness(tenant).assertRollsBackAtomically(IllegalStateException::class) {
                    posting.inContext(tenant) { posting.post(tenant, engine = faulty) }
                }
            } catch (accepted: AssertionError) {
                // Names the case, because the cases run in sequence and the harness's own message
                // - "expected IllegalStateException, completed successfully" - does not say which.
                throw AssertionError("the \"$expected\" line defect was not refused", accepted)
            }
        assertTrue(failure.message.orEmpty().contains(expected), failure.message)
    }

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val FAKE_SUBLEDGER_CODE = "TEST_SUBLEDGER"

        @Suppress("unused")
        val UNUSED_TABLES = listOf(JOURNAL_ENTRY, JOURNAL_LINE)
    }
}
