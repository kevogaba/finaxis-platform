package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.UpdateGlAccountCommand
import com.finaxis.platform.accounting.application.ledger.JournalNumberAllocator
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.JournalStore
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.NewJournalLine
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.ChartHierarchyPolicy
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.AtomicityProbe
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The posting engine against PostgreSQL, through the production wiring.
 *
 * What is proved here and nowhere else: a posting writes exactly one request, header and line set
 * atomically and invisibly until commit; every refusal leaves nothing behind; a fake product
 * sub-ledger write and the journal roll back together; the verification read is enforced against
 * real rows; a retry replays and a conflicting reuse is refused; and the two freezes the journal
 * creates - functional currency and account identity - hold once a line exists.
 *
 * Every write path registers its durable effects as probes in
 * [FinancialTransactionAtomicityFixture], per
 * `docs/architecture/financial-transaction-atomicity.md`.
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
    private val tenantLookup: AccountingTenantLookup,
    private val periodResolver: PostingPeriodResolver,
    private val accounts: GlAccountStore,
    private val numbers: JournalNumberAllocator,
    private val clock: Clock,
    private val chartOfAccounts: ChartOfAccountsService,
    private val tenantSettings: TenantSettingsService,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `a balanced posting is written once, invisibly until commit, with a gapless number`() {
        val tenant = provisionTenant("engine-balanced")
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
            receipt = inContext(tenant) { post(tenant, reference = "dep-1") }
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

        // Numbering is gapless and per tenant: the next journal takes two.
        val next =
            inContext(tenant) { transactions.execute { post(tenant, reference = "dep-2") }!! }
        assertEquals("2", next.journalReference)
    }

    @Test
    fun `unbalanced inactive-account closed-period and foreign-currency postings commit nothing`() {
        val tenant = provisionTenant("engine-refusals")
        val harness = harness(tenant)

        val unbalanced =
            harness.assertRollsBackAtomically(InvalidOperationException::class) {
                inContext(tenant) { post(tenant, credit = "499.00") }
            }
        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, unbalanced.code)

        val foreign =
            harness.assertRollsBackAtomically(InvalidOperationException::class) {
                inContext(tenant) { post(tenant, currency = "USD") }
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
                inContext(tenant) { post(tenant, debitAccount = inactive) }
            }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, notPostable.code)

        setPeriodStatus(tenant, "CLOSED")
        val closed =
            harness.assertRollsBackAtomically(ConflictException::class) {
                inContext(tenant) { post(tenant) }
            }
        assertEquals(PostingErrorCodes.PERIOD_CLOSED, closed.code)
        setPeriodStatus(tenant, "OPEN")
    }

    @Test
    fun `a product sub-ledger write and the journal roll back together`() {
        // The atomicity invariant from the product module's side. There is no product module yet,
        // so its position is stood in for by a reference_sequence row the "product" increments in
        // the same transaction as the posting. A failure after both leaves neither.
        val tenant = provisionTenant("engine-subledger")
        seedFakeSubledger(tenant)
        val harness =
            FinancialTransactionAtomicityFixture(
                dsl,
                transactionManager,
                ledgerProbes(tenant) + fakeSubledgerPosition(tenant),
            )
        val before = harness.snapshot()

        harness.assertRollsBackAtomically(IllegalStateException::class) {
            inContext(tenant) {
                post(tenant)
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
            inContext(tenant) {
                post(tenant, reference = "dep-ok")
                moveFakeSubledger(tenant)
            }
        }
    }

    @Test
    fun `a header that disagrees with its stored lines is rolled back by the verification read`() {
        // The INV-4 enforcement point against real rows. A store that drops a line stands in for a
        // defect between the engine's in-memory set and what reached the database; the header
        // CHECKs cannot see it, the verification read must. Built from the real beans plus the
        // faulty store, so everything else is the production path.
        val tenant = provisionTenant("engine-verification")
        val faulty =
            PostingEngine(
                contextLookup,
                tenantLookup,
                periodResolver,
                accounts,
                LineDroppingJournalStore(journals),
                ledger,
                numbers,
                clock,
            )
        val harness = harness(tenant)

        val failure =
            harness.assertRollsBackAtomically(IllegalStateException::class) {
                inContext(tenant) { post(tenant, engine = faulty) }
            }
        assertTrue(failure.message.orEmpty().contains("does not match its lines"), failure.message)
    }

    @Test
    fun `a retry replays the existing journal and a conflicting reuse is refused`() {
        val tenant = provisionTenant("engine-retry")
        val harness = harness(tenant)
        val first =
            inContext(tenant) { transactions.execute { post(tenant, reference = "dep-7") }!! }
        val after = harness.snapshot()

        // The retry after a lost response: same reference, same request.
        val replay =
            inContext(tenant) { transactions.execute { post(tenant, reference = "dep-7") }!! }
        assertEquals(first, replay, "a retry returns the receipt of the journal already posted")
        assertEquals(after, harness.snapshot(), "a retry writes nothing and burns no number")

        // The same reference for a different request.
        val conflict =
            harness.assertRollsBackAtomically(ConflictException::class) {
                inContext(
                    tenant,
                ) { post(tenant, reference = "dep-7", debit = "1.00", credit = "1.00") }
            }
        assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, conflict.code)
    }

    @Test
    fun `the caller's context must match the request context`() {
        val tenant = provisionTenant("engine-context")
        val other = provisionTenant("engine-context-other")
        val harness = harness(tenant)

        val failure =
            harness.assertRollsBackAtomically(ForbiddenOperationException::class) {
                inContext(tenant) {
                    engine.post(
                        LedgerPostingRequest(
                            context = context(other),
                            source = source("dep-x"),
                            eventCode = "SAVINGS_DEPOSIT",
                            entryType = JournalEntryType.STANDARD,
                        ),
                    ) { ResolvedLegs(legs(tenant), null) }
                }
            }
        assertEquals(PostingErrorCodes.CONTEXT_MISMATCH, failure.code)
    }

    @Test
    fun `the public service refuses an intent no posting rule resolves`() {
        // A tenant with no rule for the event: the resolver names the gap, and nothing is written.
        val tenant = provisionTenant("engine-no-rules")
        val harness = harness(tenant)

        val failure =
            harness.assertRollsBackAtomically(InvalidOperationException::class) {
                inContext(tenant) {
                    postingService.post(
                        PostFinancialFactsCommand(
                            context = context(tenant),
                            source = source("dep-facts"),
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
    fun `the public service must join an existing transaction`() {
        val tenant = provisionTenant("engine-mandatory")

        assertFailsWith<org.springframework.transaction.IllegalTransactionStateException> {
            inContext(tenant) {
                postingService.post(
                    PostFinancialFactsCommand(
                        context = context(tenant),
                        source = source("dep-outside"),
                        intent = PostingIntent.Facts("SAVINGS_DEPOSIT", emptyList()),
                    ),
                )
            }
        }
    }

    @Test
    fun `the functional currency is frozen once a journal is posted`() {
        val tenant = provisionTenant("engine-currency-freeze")

        // Before any journal, the setting may change.
        withRequestContext { setBaseCurrency(tenant, "KES") }

        inContext(tenant) { transactions.execute { post(tenant) } }

        val failure =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    setBaseCurrency(
                        tenant,
                        "USD",
                    )
                }
            }
        assertEquals(PostingErrorCodes.FUNCTIONAL_CURRENCY_FROZEN, failure.code)
    }

    @Test
    fun `an account with a posted line keeps its code class and usage`() {
        // The second half of "has been used", answerable now that journal_line exists. The name
        // stays editable: it changes nothing about what posted or where it rolls up.
        val tenant = provisionTenant("engine-identity-freeze")
        inContext(tenant) { transactions.execute { post(tenant) } }

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

    private data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val businessDate: LocalDate,
        val periodId: UUID,
        val debitAccountId: UUID,
        val creditAccountId: UUID,
    )

    /**
     * An ACTIVE organisation as provisioning leaves it - business date, head office, sequences -
     * plus what accounting needs on top: an OPEN period covering the business date and two postable
     * accounts. The period is built from the *real* business date, because the engine reads that
     * date and resolves the period from it.
     */
    private fun provisionTenant(label: String): Tenant {
        val organisationId = tenants.createActiveOrganisation(label, ACTOR)
        val businessDate =
            requireNotNull(
                dsl
                    .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                    .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
            ) { "provisioning must have created the business date" }
        val branchId =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_CODE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            ) { "provisioning must have created the head office" }
        val periodId = openPeriodCovering(organisationId, businessDate)
        return Tenant(
            organisationId = organisationId,
            branchId = branchId,
            businessDate = businessDate,
            periodId = periodId,
            debitAccountId = schema.insertAccount(organisationId, "1010", "ASSET"),
            creditAccountId = schema.insertAccount(organisationId, "2010", "LIABILITY"),
        )
    }

    /** One OPEN period covering [date], inside a fiscal year covering its calendar year. */
    private fun openPeriodCovering(
        organisationId: UUID,
        date: LocalDate,
    ): UUID {
        val now = OffsetDateTime.now()
        val yearId =
            dsl
                .insertInto(ACCOUNTING_FISCAL_YEAR)
                .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, organisationId)
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, "FY${date.year}")
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, "Financial year ${date.year}")
                .set(ACCOUNTING_FISCAL_YEAR.START_DATE, date.withDayOfYear(1))
                .set(ACCOUNTING_FISCAL_YEAR.END_DATE, date.withDayOfYear(date.lengthOfYear()))
                .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now)
                .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now)
                .returning(ACCOUNTING_FISCAL_YEAR.ID)
                .fetchOne()!!
                .id!!
        return dsl
            .insertInto(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, organisationId)
            .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, yearId)
            .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, date.monthValue)
            .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, "Period ${date.monthValue}")
            .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, date.withDayOfMonth(1))
            .set(ACCOUNTING_FISCAL_PERIOD.END_DATE, date.withDayOfMonth(date.lengthOfMonth()))
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, "OPEN")
            .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now)
            .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now)
            .returning(ACCOUNTING_FISCAL_PERIOD.ID)
            .fetchOne()!!
            .id!!
    }

    private fun context(tenant: Tenant) =
        AccountingContext(tenant.organisationId, tenant.branchId, ACTOR, correlationId = null)

    /**
     * A source whose entity id is a function of the reference: a real retry carries the same
     * business entity, so the fingerprint - which covers the whole source triple - must too.
     */
    private fun source(reference: String) =
        AccountingSourceReference(
            "savings",
            "SAVINGS_DEPOSIT",
            UUID.nameUUIDFromBytes(reference.toByteArray()),
            reference,
        )

    private fun legs(
        tenant: Tenant,
        debit: String = "500.00",
        credit: String = debit,
        currency: String = "KES",
        debitAccount: UUID = tenant.debitAccountId,
    ) = listOf(
        PostingLeg(debitAccount, PostingSide.DEBIT, MonetaryAmount(BigDecimal(debit), currency)),
        PostingLeg(
            tenant.creditAccountId,
            PostingSide.CREDIT,
            MonetaryAmount(BigDecimal(credit), currency),
            subledgerReference = "SAV-0001",
        ),
    )

    /** Posts explicit legs through the engine, as accounting's own callers do. */
    @Suppress("LongParameterList")
    private fun post(
        tenant: Tenant,
        reference: String = "dep-${uuidV7()}",
        debit: String = "500.00",
        credit: String = debit,
        currency: String = "KES",
        debitAccount: UUID = tenant.debitAccountId,
        engine: PostingEngine = this.engine,
    ): PostingReceipt =
        engine.post(
            LedgerPostingRequest(
                context = context(tenant),
                source = source(reference),
                eventCode = "SAVINGS_DEPOSIT",
                entryType = JournalEntryType.STANDARD,
                narrative = "Counter deposit",
                // A real product-module caller carries this from PostingIntent.Facts; supplied by
                // hand here because this helper calls the engine directly, so a "conflicting reuse"
                // test that varies only the amount stays distinguishable (ADR 0023).
                financialFacts =
                    listOf(
                        FinancialFact("AMOUNT", MonetaryAmount(BigDecimal(debit), currency)),
                    ),
            ),
        ) { ResolvedLegs(legs(tenant, debit, credit, currency, debitAccount), null) }

    /** Installs the ambient request context the engine reconciles the caller's claim against. */
    private fun <T> inContext(
        tenant: Tenant,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(ACTOR, null, null, null),
            ),
        ) {
            withRequestContext(block)
        }

    private fun harness(tenant: Tenant) =
        FinancialTransactionAtomicityFixture(dsl, transactionManager, ledgerProbes(tenant))

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

    private fun setBaseCurrency(
        tenant: Tenant,
        value: String,
    ) {
        tenantSettings.createOrUpdate(
            CreateOrUpdateTenantSettingCommand(
                organisationId = tenant.organisationId,
                key = "base_currency",
                value = value,
                actorId = ACTOR,
            ),
        )
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

    /** Writes every line but the last, standing in for a defect between engine and database. */
    private class LineDroppingJournalStore(
        private val delegate: JournalStore,
    ) : JournalStore by delegate {
        override fun insertJournalLines(lines: List<NewJournalLine>) =
            delegate.insertJournalLines(lines.dropLast(1))
    }

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val FAKE_SUBLEDGER_CODE = "TEST_SUBLEDGER"

        @Suppress("unused")
        val UNUSED_TABLES = listOf(JOURNAL_ENTRY, JOURNAL_LINE)
    }
}
