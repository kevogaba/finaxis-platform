package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalLineageQuery
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingLineageService
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.ledger.SourceEntityLineageQuery
import com.finaxis.platform.accounting.application.ledger.SourceLineageQuery
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
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
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exactly-once financial effect under retries and concurrent duplicates, and lineage in both
 * directions, proved against PostgreSQL through the production wiring (issue #42).
 *
 * The database is the authority for every assertion here: `uq_posting_request_source` and the
 * `ON CONFLICT` claim decide the concurrent case, and no in-memory lock or cache takes part.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingIdempotencyIntegrationTests(
    private val engine: PostingEngine,
    private val lineage: PostingLineageService,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `a sequential duplicate returns the same receipt and one financial effect`() {
        val tenant = provisionTenant("idem-sequential")

        val first = inContext(tenant) { transactions.execute { post(tenant, "dep-1") }!! }
        val second = inContext(tenant) { transactions.execute { post(tenant, "dep-1") }!! }

        assertEquals(first, second)
        assertEquals(1, journalCount(tenant))
    }

    @Test
    fun `concurrent duplicates produce exactly one committed posting`() {
        // Two real transactions on two connections, released together. One inserts; the other
        // waits on the uncommitted row at the unique index, then observes the committed request
        // and replays it. Neither aborts, and neither sees a unique violation.
        val tenant = provisionTenant("idem-concurrent")
        val start = CountDownLatch(1)
        val receipts =
            Executors.newFixedThreadPool(2).use { executor ->
                val futures =
                    (1..2).map {
                        executor.submit<PostingReceipt> {
                            assertTrue(start.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            inContext(
                                tenant,
                            ) { transactions.execute { post(tenant, "dep-race") }!! }
                        }
                    }
                start.countDown()
                futures.map { it.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }

        assertEquals(receipts[0], receipts[1], "both callers hold the receipt of the one journal")
        assertEquals(1, journalCount(tenant))
        assertEquals("1", receipts[0].journalReference, "no gapless number was burnt by the loser")
    }

    @Test
    fun `a retry after a lost post-commit response resolves to the existing journal`() {
        // The client posted, the transaction committed, the response never arrived. The retry is
        // an ordinary second call with the same durable identity.
        val tenant = provisionTenant("idem-retry")
        val committed = inContext(tenant) { transactions.execute { post(tenant, "dep-lost") }!! }
        val storedBefore = journalIds(tenant)

        val retried = inContext(tenant) { transactions.execute { post(tenant, "dep-lost") }!! }

        assertEquals(committed.journalEntryId, retried.journalEntryId)
        assertEquals(storedBefore, journalIds(tenant))
    }

    @Test
    fun `the same reference with a different request is a deterministic conflict`() {
        val tenant = provisionTenant("idem-conflict")
        inContext(tenant) { transactions.execute { post(tenant, "dep-c", amount = "500.00") } }

        repeat(2) {
            val failure =
                assertFailsWith<ConflictException> {
                    inContext(
                        tenant,
                    ) { transactions.execute { post(tenant, "dep-c", amount = "501.00") } }
                }
            assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, failure.code)
        }
        assertEquals(1, journalCount(tenant))
    }

    @Test
    fun `idempotency is scoped to the tenant and the source module`() {
        val tenant = provisionTenant("idem-tenant-a")
        val other = provisionTenant("idem-tenant-b")

        inContext(tenant) { transactions.execute { post(tenant, "dep-shared") } }
        inContext(other) { transactions.execute { post(other, "dep-shared") } }
        inContext(tenant) { transactions.execute { post(tenant, "dep-shared", module = "loans") } }

        assertEquals(2, journalCount(tenant), "a different module is a different business event")
        assertEquals(1, journalCount(other))
    }

    @Test
    fun `lineage is queryable forward from the source and backward from the journal`() {
        val tenant = provisionTenant("idem-lineage")
        val entityId = uuidV7()
        val receipt =
            inContext(tenant) {
                transactions.execute {
                    post(
                        tenant,
                        "dep-l1",
                        entityId = entityId,
                        narrative = "First",
                    )
                }!!
            }
        inContext(tenant) { transactions.execute { post(tenant, "dep-l2", entityId = entityId) } }

        val forward =
            assertNotNull(
                lineage.findBySource(
                    SourceLineageQuery(tenant.organisationId, ACTOR, "savings", "dep-l1"),
                ),
            )
        assertEquals(receipt.postingRequestId, forward.request.id)
        assertEquals(PostingRequestStatus.POSTED, forward.request.status)
        assertEquals(entityId, forward.request.sourceEntityId)
        assertEquals("First", forward.request.narrative)
        assertEquals(receipt.journalEntryId, forward.journal?.id)
        assertEquals(2, forward.lines.size)

        val backward =
            assertNotNull(
                lineage.findByJournal(
                    JournalLineageQuery(tenant.organisationId, ACTOR, receipt.journalEntryId),
                ),
            )
        assertEquals("dep-l1", backward.request.sourceReference)
        assertEquals("SAVINGS_DEPOSIT", backward.request.sourceEntityType)
        assertEquals(entityId, backward.request.sourceEntityId)
    }

    @Test
    fun `lineage from a business entity is newest first, bounded and walkable by cursor`() {
        val tenant = provisionTenant("idem-lineage-paging")
        val entityId = uuidV7()
        inContext(tenant) { transactions.execute { post(tenant, "dep-l1", entityId = entityId) } }
        inContext(tenant) { transactions.execute { post(tenant, "dep-l2", entityId = entityId) } }

        val page1 =
            lineage.listForSourceEntity(
                SourceEntityLineageQuery(
                    tenant.organisationId,
                    ACTOR,
                    "savings",
                    "SAVINGS_DEPOSIT",
                    entityId,
                    pageSize = 1,
                ),
            )
        assertEquals(listOf("dep-l2"), page1.items.map { it.request.sourceReference })
        val page2 =
            lineage.listForSourceEntity(
                SourceEntityLineageQuery(
                    tenant.organisationId,
                    ACTOR,
                    "savings",
                    "SAVINGS_DEPOSIT",
                    entityId,
                    pageSize = 1,
                    cursor = page1.nextCursor,
                ),
            )
        assertEquals(listOf("dep-l1"), page2.items.map { it.request.sourceReference })
        assertNull(
            lineage
                .listForSourceEntity(
                    SourceEntityLineageQuery(
                        tenant.organisationId,
                        ACTOR,
                        "savings",
                        "SAVINGS_DEPOSIT",
                        entityId,
                        pageSize = 1,
                        cursor = page2.nextCursor,
                    ),
                ).nextCursor,
        )
    }

    @Test
    fun `lineage reads are permission gated and tenant scoped`() {
        val tenant = provisionTenant("idem-lineage-auth")
        val other = provisionTenant("idem-lineage-other")
        val receipt = inContext(tenant) { transactions.execute { post(tenant, "dep-auth") }!! }

        assertFailsWith<ForbiddenOperationException> {
            lineage.findBySource(
                SourceLineageQuery(tenant.organisationId, STRANGER, "savings", "dep-auth"),
            )
        }
        // The other tenant's administrator, asking about their own tenant, sees nothing of ours.
        assertNull(
            lineage.findByJournal(
                JournalLineageQuery(other.organisationId, ACTOR, receipt.journalEntryId),
            ),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------

    private data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val debitAccountId: UUID,
        val creditAccountId: UUID,
    )

    private fun provisionTenant(label: String): Tenant {
        val organisationId = tenants.createActiveOrganisation(label, ACTOR)
        val businessDate =
            requireNotNull(
                dsl
                    .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                    .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
            )
        val branchId =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_CODE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            )
        openPeriodCovering(organisationId, businessDate)
        return Tenant(
            organisationId = organisationId,
            branchId = branchId,
            debitAccountId = schema.insertAccount(organisationId, "1010", "ASSET"),
            creditAccountId = schema.insertAccount(organisationId, "2010", "LIABILITY"),
        )
    }

    private fun openPeriodCovering(
        organisationId: UUID,
        date: LocalDate,
    ) {
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
        dsl
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
            .execute()
    }

    @Suppress("LongParameterList")
    private fun post(
        tenant: Tenant,
        reference: String,
        amount: String = "500.00",
        module: String = "savings",
        entityId: UUID = UUID.nameUUIDFromBytes(reference.toByteArray()),
        narrative: String? = null,
    ): PostingReceipt =
        engine.post(
            LedgerPostingRequest(
                context = AccountingContext(tenant.organisationId, tenant.branchId, ACTOR),
                source = AccountingSourceReference(module, "SAVINGS_DEPOSIT", entityId, reference),
                eventCode = "SAVINGS_DEPOSIT",
                entryType = JournalEntryType.STANDARD,
                narrative = narrative,
            ),
        ) {
            ResolvedLegs(
                listOf(
                    PostingLeg(tenant.debitAccountId, PostingSide.DEBIT, kes(amount)),
                    PostingLeg(tenant.creditAccountId, PostingSide.CREDIT, kes(amount)),
                ),
                null,
            )
        }

    private fun kes(amount: String) = MonetaryAmount(BigDecimal(amount), "KES")

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
        ) { withRequestContext(block) }

    private fun journalCount(tenant: Tenant) =
        dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))

    private fun journalIds(tenant: Tenant) =
        dsl
            .select(JOURNAL_ENTRY.ID)
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
            .fetch(JOURNAL_ENTRY.ID)
            .toSet()

    private companion object {
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        const val LATCH_TIMEOUT_SECONDS = 10L
        const val FUTURE_TIMEOUT_SECONDS = 60L
    }
}
