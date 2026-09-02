package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.GlAccountPage
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine decision logic, driven with fakes so every refusal is testable without a database.
 *
 * The real `PostingPeriodResolver` is used over faked ports rather than faked itself, so the
 * period protocol the engine depends on is exercised as it will run. What the database enforces -
 * constraints, locks, the verification read against real rows - is `PostingEngineIntegrationTests`.
 */
class PostingEngineTests {
    private val context = AccountingContext(ORGANISATION_ID, BRANCH_ID, ACTOR_ID, "corr-1")
    private val contextLookup = FakeContextLookup(context)
    private val tenants = FakeTenantLookup()
    private val periods = FakeFiscalPeriodStateStore()
    private val accounts = FakeGlAccountStore()
    private val journals = FakeJournalStore()
    private val numbers = FakeNumberAllocator()
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val engine =
        PostingEngine(
            contextLookup,
            tenants,
            PostingPeriodResolver(
                periods,
                FakeBusinessDateLookup(),
                PermissiveGuard(),
                AuditService(NoAuditRepository(), clock),
                clock,
            ),
            accounts,
            journals,
            numbers,
            clock,
        )

    @BeforeEach
    fun inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        accounts.put(DEBIT_ACCOUNT, GlAccountStatus.ACTIVE)
        accounts.put(CREDIT_ACCOUNT, GlAccountStatus.ACTIVE)
    }

    @AfterEach
    fun clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun `a balanced posting writes request header and lines and returns the receipt`() {
        val receipt = engine.post(request(), explicit(legs()))

        assertEquals(1, journals.requests.size)
        val header = journals.entries.single()
        assertEquals(receipt.journalEntryId, journals.entryIds.single())
        assertEquals(JournalEntryType.STANDARD, header.entryType)
        assertEquals(BigDecimal("100.000000"), header.totalDebitFunctional)
        assertEquals(BigDecimal("100.000000"), header.totalCreditFunctional)
        assertEquals(2, header.lineCount)
        assertEquals(PERIOD_ID, header.fiscalPeriodId)
        assertEquals(TODAY, header.dates.postingDate)
        assertEquals(listOf(1, 2), journals.lines.map { it.lineNumber })
        assertEquals(BigDecimal.ONE, journals.lines.first().exchangeRate)
        assertEquals("savings", journals.lines.first().sourceModule)
        assertEquals("1", receipt.journalReference)
        assertEquals(TODAY, receipt.businessDate)
        assertEquals(NOW, receipt.postedAt)
        assertEquals(PostingRequestStatus.POSTED, journals.requests.single().status)
    }

    @Test
    fun `the legs are asked for after the posting date is known`() {
        // A rule-resolved posting cannot know its legs until the posting date - and so the rule
        // version in force - is resolved. The provider receives the resolved dates.
        var seen: LocalDate? = null
        engine.post(request()) { dates ->
            seen = dates.postingDate
            ResolvedLegs(legs(), null)
        }

        assertEquals(TODAY, seen)
    }

    @Test
    fun `an unbalanced set commits nothing`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                engine.post(request(), explicit(legs(credit = "99.00")))
            }

        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, failure.code)
        assertNothingWritten()
    }

    @Test
    fun `fewer than two legs is unbalanced by definition`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                engine.post(request(), explicit(legs().take(1)))
            }

        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, failure.code)
        assertNothingWritten()
    }

    @Test
    fun `a leg in another currency is refused rather than converted at a rate of one`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                engine.post(request(), explicit(legs(currency = "USD")))
            }

        assertEquals(PostingErrorCodes.CURRENCY_NOT_SUPPORTED, failure.code)
        assertNothingWritten()
    }

    @Test
    fun `an inactive unknown or header account is not postable`() {
        accounts.put(DEBIT_ACCOUNT, GlAccountStatus.INACTIVE)
        val inactive =
            assertFailsWith<InvalidOperationException> { engine.post(request(), explicit(legs())) }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, inactive.code)

        accounts.remove(DEBIT_ACCOUNT)
        val unknown =
            assertFailsWith<InvalidOperationException> { engine.post(request(), explicit(legs())) }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, unknown.code)

        accounts.put(DEBIT_ACCOUNT, GlAccountStatus.ACTIVE, AccountUsage.HEADER)
        val header =
            assertFailsWith<InvalidOperationException> { engine.post(request(), explicit(legs())) }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, header.code)

        assertNothingWritten()
    }

    @Test
    fun `a closed period commits nothing`() {
        periods.locked = snapshot(FiscalPeriodStatus.CLOSED)

        val failure =
            assertFailsWith<ConflictException> { engine.post(request(), explicit(legs())) }

        assertEquals(PostingErrorCodes.PERIOD_CLOSED, failure.code)
        assertNothingWritten()
    }

    @Test
    fun `a context naming another tenant actor or branch is refused before anything is read`() {
        listOf(
            context.copy(organisationId = uuidV7()),
            context.copy(actorId = uuidV7()),
            context.copy(branchId = uuidV7()),
        ).forEach { claimed ->
            val failure =
                assertFailsWith<ForbiddenOperationException> {
                    engine.post(request(context = claimed), explicit(legs()))
                }
            assertEquals(PostingErrorCodes.CONTEXT_MISMATCH, failure.code)
        }
        assertTrue(periods.lockRequests == 0, "no period was touched")
        assertNothingWritten()
    }

    @Test
    fun `a branch-less claim is accepted when the request was made under a branch`() {
        // The caller may post at tenant level from a branch session; it may not name a branch the
        // session is not in.
        engine.post(request(context = context.copy(branchId = null)), explicit(legs()))

        assertNull(journals.entries.single().branchId)
    }

    @Test
    fun `a tenant or branch that does not permit financial activity is refused`() {
        tenants.organisationPostable = false
        val organisation =
            assertFailsWith<ConflictException> { engine.post(request(), explicit(legs())) }
        assertEquals(PostingErrorCodes.ORGANISATION_NOT_POSTABLE, organisation.code)

        tenants.organisationPostable = true
        tenants.branchPostable = false
        val branch =
            assertFailsWith<ConflictException> { engine.post(request(), explicit(legs())) }
        assertEquals(PostingErrorCodes.BRANCH_NOT_POSTABLE, branch.code)

        assertNothingWritten()
    }

    @Test
    fun `a tenant without a functional currency cannot post`() {
        tenants.functionalCurrency = null

        val failure =
            assertFailsWith<ConflictException> { engine.post(request(), explicit(legs())) }

        assertEquals(PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE, failure.code)
        assertNothingWritten()
    }

    @Test
    fun `a tenant without a JOURNAL sequence fails loudly rather than numbering elsewhere`() {
        numbers.next = null

        val failure =
            assertFailsWith<ConflictException> { engine.post(request(), explicit(legs())) }

        assertEquals(PostingErrorCodes.JOURNAL_SEQUENCE_MISSING, failure.code)
        assertTrue(journals.entries.isEmpty(), "no header may be written without a number")
    }

    @Test
    fun `a retry with the same reference and fingerprint gets the existing receipt back`() {
        val first = engine.post(request(), explicit(legs()))
        journals.commitClaims()

        val second = engine.post(request(), explicit(legs()))

        assertEquals(first.journalEntryId, second.journalEntryId)
        assertEquals(first.journalReference, second.journalReference)
        assertEquals(1, journals.entries.size, "a retry writes no second journal")
        assertEquals(1, numbers.allocations, "a retry burns no gapless number")
    }

    @Test
    fun `the same reference with a different request is a conflict never a second effect`() {
        engine.post(request(), explicit(legs()))
        journals.commitClaims()

        val failure =
            assertFailsWith<ConflictException> {
                engine.post(request(), explicit(legs(amount = "250.00")))
            }

        assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, failure.code)
        assertEquals(1, journals.entries.size)
    }

    @Test
    fun `a header that does not match its stored lines rolls the posting back`() {
        // The INV-4 enforcement point: what the database holds, not what the engine believes it
        // wrote. The store drops a line to simulate a defect between the two.
        journals.dropLastLine = true

        val failure =
            assertFailsWith<IllegalStateException> { engine.post(request(), explicit(legs())) }

        assertTrue(failure.message.orEmpty().contains("does not match its lines"), failure.message)
        assertTrue(
            journals.requests.none { it.status == PostingRequestStatus.POSTED },
            "the request must not be marked posted when verification fails",
        )
    }

    @Test
    fun `posting outside a transaction is a caller defect`() {
        TransactionSynchronizationManager.setActualTransactionActive(false)

        assertFailsWith<IllegalStateException> { engine.post(request(), explicit(legs())) }
        assertNothingWritten()
    }

    @Test
    fun `the fingerprint and lineage are persisted on the request`() {
        engine.post(request(narrative = "Counter deposit"), explicit(legs()))

        val stored = journals.requests.single()
        assertEquals("savings", stored.sourceModule)
        assertEquals("SAVINGS_DEPOSIT", stored.sourceEntityType)
        assertEquals("dep-1", stored.sourceReference)
        assertEquals("SAVINGS_DEPOSIT", stored.eventCode)
        assertEquals("Counter deposit", stored.narrative)
        assertEquals("corr-1", stored.correlationId)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(stored.fingerprint))
        assertNotNull(stored.postedAt)
    }

    private fun assertNothingWritten() {
        assertTrue(journals.requests.isEmpty(), "no request may survive a refusal")
        assertTrue(journals.entries.isEmpty(), "no header may survive a refusal")
        assertTrue(journals.lines.isEmpty(), "no line may survive a refusal")
    }

    private fun request(
        context: AccountingContext = this.context,
        narrative: String? = null,
    ) = LedgerPostingRequest(
        context = context,
        source = AccountingSourceReference("savings", "SAVINGS_DEPOSIT", SOURCE_ID, "dep-1"),
        eventCode = "SAVINGS_DEPOSIT",
        entryType = JournalEntryType.STANDARD,
        narrative = narrative,
    )

    private fun explicit(legs: List<PostingLeg>) = LegProvider { ResolvedLegs(legs, null) }

    private fun legs(
        amount: String = "100.00",
        credit: String = amount,
        currency: String = "KES",
    ) = listOf(
        PostingLeg(DEBIT_ACCOUNT, PostingSide.DEBIT, MonetaryAmount(BigDecimal(amount), currency)),
        PostingLeg(
            CREDIT_ACCOUNT,
            PostingSide.CREDIT,
            MonetaryAmount(BigDecimal(credit), currency),
        ),
    )

    private fun snapshot(status: FiscalPeriodStatus) =
        FiscalPeriodSnapshot(
            FiscalPeriodKey(ORGANISATION_ID, PERIOD_ID),
            TODAY.withDayOfMonth(1),
            TODAY.withDayOfMonth(TODAY.lengthOfMonth()),
            status,
        )

    private class FakeContextLookup(
        private val context: AccountingContext,
    ) : AccountingContextLookup {
        override fun current() = context

        override fun require() = context
    }

    private class FakeTenantLookup : AccountingTenantLookup {
        var organisationPostable = true
        var branchPostable = true
        var functionalCurrency: String? = "KES"

        override fun isOrganisationPostable(organisationId: UUID) = organisationPostable

        override fun isBranchPostable(
            organisationId: UUID,
            branchId: UUID,
        ) = branchPostable

        override fun functionalCurrencyOf(organisationId: UUID) = functionalCurrency
    }

    private inner class FakeFiscalPeriodStateStore : FiscalPeriodStateStore {
        var covering: FiscalPeriodSnapshot? = snapshot(FiscalPeriodStatus.OPEN)
        var locked: FiscalPeriodSnapshot? = snapshot(FiscalPeriodStatus.OPEN)
        var lockRequests = 0

        override fun findById(key: FiscalPeriodKey) = covering

        override fun findCovering(
            organisationId: UUID,
            postingDate: LocalDate,
        ) = covering

        override fun lockForPosting(key: FiscalPeriodKey): FiscalPeriodSnapshot? {
            lockRequests++
            return locked
        }

        override fun lockForStateChange(key: FiscalPeriodKey) = locked

        override fun updateStatus(
            key: FiscalPeriodKey,
            newStatus: FiscalPeriodStatus,
            actorId: UUID,
            reason: String?,
        ) = true
    }

    private class FakeBusinessDateLookup : AccountingBusinessDateLookup {
        override fun currentBusinessDate(organisationId: UUID) =
            AccountingBusinessDate(organisationId, TODAY, postingAllowed = true)
    }

    private class PermissiveGuard : AccountingPermissionGuard {
        override fun requireTenantPermission(
            actorId: UUID,
            organisationId: UUID,
            permissionCode: String,
        ) = Unit

        override fun requireBreakGlassPermission(
            actorId: UUID,
            organisationId: UUID,
            permissionCode: String,
        ) = Unit

        override fun requireBranchPermission(
            actorId: UUID,
            organisationId: UUID,
            branchId: UUID,
            permissionCode: String,
        ) = Unit
    }

    private class NoAuditRepository : AuditEventRepository {
        override fun save(event: AuditEvent) = Unit
    }

    private class FakeGlAccountStore : GlAccountStore {
        private val accounts = mutableMapOf<UUID, GlAccount>()

        fun put(
            id: UUID,
            status: GlAccountStatus,
            usage: AccountUsage = AccountUsage.POSTABLE,
        ) {
            accounts[id] =
                GlAccount(
                    id = id,
                    organisationId = ORGANISATION_ID,
                    code = AccountCode("A-${id.toString().takeLast(4)}"),
                    name = "Account",
                    accountClass = AccountClass.ASSET,
                    usage = usage,
                    status = status,
                )
        }

        fun remove(id: UUID) = accounts.remove(id)

        override fun findById(
            organisationId: UUID,
            accountId: UUID,
        ) = accounts[accountId]?.takeIf { it.organisationId == organisationId }

        override fun findByCode(
            organisationId: UUID,
            code: AccountCode,
        ) = accounts.values.firstOrNull { it.code == code }

        override fun ancestorsOf(
            organisationId: UUID,
            accountId: UUID,
        ) = emptyList<GlAccount>()

        override fun subtreeHeightOf(
            organisationId: UUID,
            accountId: UUID,
        ) = 1

        override fun lockForStateChange(
            organisationId: UUID,
            accountId: UUID,
        ) = findById(organisationId, accountId)

        override fun hasChildren(
            organisationId: UUID,
            accountId: UUID,
        ) = false

        override fun hasJournalLines(
            organisationId: UUID,
            accountId: UUID,
        ) = false

        override fun list(
            organisationId: UUID,
            afterCode: AccountCode?,
            pageSize: Int,
        ) = GlAccountPage(accounts.values.toList(), null)
    }

    /**
     * In-memory journal store. Claims are "uncommitted" until [commitClaims] is called, mirroring
     * that a second caller only sees a committed row; a claim within the same test before that is
     * treated as this transaction's own insert.
     */
    private class FakeJournalStore : JournalStore {
        class StoredRequest(
            val id: UUID,
            val sourceModule: String,
            val sourceEntityType: String,
            val sourceReference: String,
            val eventCode: String,
            val fingerprint: String,
            val narrative: String?,
            val correlationId: String?,
            var status: PostingRequestStatus,
            var postedAt: Instant?,
        )

        val requests = mutableListOf<StoredRequest>()
        val entries = mutableListOf<NewJournalEntry>()
        val entryIds = mutableListOf<UUID>()
        val lines = mutableListOf<NewJournalLine>()
        var dropLastLine = false
        private val committed = mutableSetOf<UUID>()

        fun commitClaims() = committed.addAll(requests.map { it.id })

        override fun claimPostingRequest(request: NewPostingRequest): PostingRequestClaim {
            val existing =
                requests.firstOrNull {
                    it.id in committed &&
                        it.sourceModule == request.sourceModule &&
                        it.sourceReference == request.sourceReference
                }
            if (existing != null) {
                return PostingRequestClaim.Existing(
                    ExistingPostingRequest(existing.id, existing.status, existing.fingerprint),
                )
            }
            val id = uuidV7()
            requests +=
                StoredRequest(
                    id,
                    request.sourceModule,
                    request.sourceEntityType,
                    request.sourceReference,
                    request.eventCode,
                    request.fingerprint,
                    request.narrative,
                    request.correlationId,
                    PostingRequestStatus.PENDING,
                    null,
                )
            return PostingRequestClaim.Claimed(id)
        }

        override fun insertJournalEntry(entry: NewJournalEntry): UUID {
            entries += entry
            return uuidV7().also(entryIds::add)
        }

        override fun insertJournalLines(lines: List<NewJournalLine>) {
            this.lines += if (dropLastLine) lines.dropLast(1) else lines
        }

        override fun sumLines(
            organisationId: UUID,
            journalEntryId: UUID,
        ): JournalTotals {
            val mine = lines.filter { it.journalEntryId == journalEntryId }
            return JournalTotals(
                mine.filter { it.leg.side == PostingSide.DEBIT }.sumOf { it.functionalAmount },
                mine.filter { it.leg.side == PostingSide.CREDIT }.sumOf { it.functionalAmount },
                mine.size,
            )
        }

        override fun markPosted(
            organisationId: UUID,
            postingRequestId: UUID,
            postedAt: Instant,
            actorId: UUID,
        ) {
            requests.single { it.id == postingRequestId }.apply {
                status = PostingRequestStatus.POSTED
                this.postedAt = postedAt
            }
        }

        override fun findJournalEntryForRequest(
            organisationId: UUID,
            postingRequestId: UUID,
        ): JournalEntryView? =
            entries
                .indexOfFirst { it.postingRequestId == postingRequestId }
                .takeIf { it >= 0 }
                ?.let(
                    ::view,
                )

        private fun view(index: Int): JournalEntryView {
            val entry = entries[index]
            return JournalEntryView(
                id = entryIds[index],
                organisationId = entry.organisationId,
                branchId = entry.branchId,
                postingRequestId = entry.postingRequestId,
                fiscalPeriodId = entry.fiscalPeriodId,
                entryNumber = entry.entryNumber,
                entryType = entry.entryType,
                reversesJournalEntryId = entry.reversesJournalEntryId,
                postingDate = entry.dates.postingDate,
                businessDate = entry.dates.businessDate,
                currencyCode = entry.currencyCode,
                functionalCurrencyCode = entry.functionalCurrencyCode,
                totalDebitFunctional = entry.totalDebitFunctional,
                lineCount = entry.lineCount,
                narrative = entry.narrative,
                postedAt = entry.postedAt,
                postedBy = entry.actorId,
            )
        }
    }

    private class FakeNumberAllocator : JournalNumberAllocator {
        var next: Long? = 1
        var allocations = 0

        override fun nextEntryNumber(organisationId: UUID): Long? {
            val current = next ?: return null
            allocations++
            next = current + 1
            return current
        }
    }

    private companion object {
        val ORGANISATION_ID: UUID = uuidV7()
        val BRANCH_ID: UUID = uuidV7()
        val ACTOR_ID: UUID = uuidV7()
        val PERIOD_ID: UUID = uuidV7()
        val SOURCE_ID: UUID = uuidV7()
        val DEBIT_ACCOUNT: UUID = uuidV7()
        val CREDIT_ACCOUNT: UUID = uuidV7()
        val TODAY: LocalDate = LocalDate.of(2026, 8, 15)
        val NOW: Instant = Instant.parse("2026-08-15T10:00:00Z")

        @Suppress("unused")
        val SCALE = MoneyPolicy.STORAGE_SCALE
    }
}
