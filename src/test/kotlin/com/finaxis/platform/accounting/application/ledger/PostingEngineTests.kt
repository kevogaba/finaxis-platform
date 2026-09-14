package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.GlAccountPage
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.FinancialFact
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
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
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
 *
 * Since issue #89 (ADR 0023) the claim comes before validation, so [assertNothingWritten] checks
 * for no *committed* effect - no journal, no posted request - rather than no row at all: a request
 * this transaction claimed and then refused still leaves a `PENDING` row in [FakeJournalStore],
 * exactly as a real `posting_request` insert would exist until the surrounding transaction rolls
 * back. The fake cannot model the rollback itself, only its outcome.
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
    fun `the rule version is recorded when the request is marked posted`() {
        val versionId = uuidV7()

        engine.post(request()) { dates -> ResolvedLegs(legs(), versionId) }

        assertEquals(versionId, journals.requests.single().postingRuleVersionId)
    }

    @Test
    fun `the legs are asked for only after the period is locked and validated`() {
        // A rule-resolved posting cannot know its legs until the posting date - and so the rule
        // version in force - is resolved. The provider receives the resolved, locked period's
        // dates, and is never called at all for a request that turns out to replay one already
        // posted.
        var seen: LocalDate? = null
        engine.post(request()) { dates ->
            seen = dates.postingDate
            ResolvedLegs(legs(), null)
        }

        assertEquals(TODAY, seen)
        assertTrue(periods.lockRequests > 0, "the period must be locked before legs are resolved")
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
    fun `accounts are locked in ascending id order before their eligibility is validated`() {
        engine.post(request(), explicit(legs()))

        val expected = listOf(DEBIT_ACCOUNT, CREDIT_ACCOUNT).sorted()
        assertEquals(expected, accounts.postingLockOrder)
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
        assertTrue(journals.requests.isEmpty(), "the source reference was never even claimed")
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
        assertTrue(
            journals.requests.isEmpty(),
            "the functional currency builds the claim itself, so it is checked before the claim " +
                "is even attempted",
        )
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
    fun `the same reference with a different event or branch is a conflict not a replay`() {
        engine.post(request(), explicit(legs()))
        journals.commitClaims()

        val differentEvent =
            assertFailsWith<ConflictException> {
                engine.post(request(eventCode = "SAVINGS_WITHDRAWAL"), explicit(legs()))
            }
        assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, differentEvent.code)

        // A caller may not claim a *different* branch than the one it authenticated under - the
        // context-mismatch test above covers that - but it may claim tenant level (no branch) when
        // it was made under one, and that is a materially different posting from the branch-scoped
        // original.
        val differentBranch =
            assertFailsWith<ConflictException> {
                engine.post(request(context = context.copy(branchId = null)), explicit(legs()))
            }
        assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, differentBranch.code)

        assertEquals(1, journals.entries.size)
    }

    /**
     * The complement of the "different legs" test below: a rule-resolved posting - one whose
     * request carries [LedgerPostingRequest.financialFacts] - still gets amount-level conflict
     * detection, because the facts are the caller's own asserted amounts and are safe to
     * fingerprint before any rule ever runs. This is what closes the gap an earlier revision of
     * this change left open, where dropping the resolved legs from the fingerprint silently
     * dropped every amount with them.
     */
    @Test
    fun `a retry with a different financial fact amount is a conflict`() {
        engine.post(request(financialFacts = listOf(fact("PRINCIPAL", "500.00"))), explicit(legs()))
        journals.commitClaims()

        val failure =
            assertFailsWith<ConflictException> {
                engine.post(
                    request(financialFacts = listOf(fact("PRINCIPAL", "501.00"))),
                    explicit(legs()),
                )
            }

        assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, failure.code)
        assertEquals(1, journals.entries.size)
    }

    /**
     * [com.finaxis.platform.accounting.domain.PostingRuleSelector] can route the same event code
     * through a different rule by product class, so a retry that only changes it must conflict
     * rather than replay - otherwise it would silently reuse a journal posted under a rule the
     * caller no longer asked for.
     */
    @Test
    fun `a retry with a different product class is a conflict`() {
        engine.post(request(productClass = "SAVINGS:REGULAR"), explicit(legs()))
        journals.commitClaims()

        val failure =
            assertFailsWith<ConflictException> {
                engine.post(request(productClass = "SAVINGS:PREMIUM"), explicit(legs()))
            }

        assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, failure.code)
        assertEquals(1, journals.entries.size)
    }

    /**
     * The behaviour change ADR 0023 documents: the fingerprint no longer covers the resolved legs,
     * because covering them would require resolving a rule-backed posting's legs before the claim
     * - exactly the staleness issue #89 removes. A retry that differs only in the legs its provider
     * would now produce is indistinguishable from a faithful retry by the fingerprint alone, and
     * `replay` never asks the provider for legs at all, so the second call's legs are never even
     * looked at.
     */
    @Test
    fun `a retry with different legs from the same provider still replays`() {
        val first = engine.post(request(), explicit(legs()))
        journals.commitClaims()

        val second = engine.post(request(), explicit(legs(amount = "250.00")))

        assertEquals(first.journalEntryId, second.journalEntryId)
        assertEquals(1, journals.entries.size, "the second call's legs were never written")
    }

    @Test
    fun `a line stored in a different fiscal period from its header rolls the posting back`() {
        // A line can balance to the last cent and still be wrong. `journal_line` denormalises the
        // period, and the reporting reads group on that column directly, so a line filed against
        // another period is money that reconciles here and lands in the wrong month downstream.
        // No schema constraint catches it: the foreign key ties a line to a *valid* period, never
        // to *its header's*.
        journals.storeLineAs = { it.copy(fiscalPeriodId = uuidV7()) }

        val failure =
            assertFailsWith<IllegalStateException> { engine.post(request(), explicit(legs())) }

        assertTrue(
            failure.message.orEmpty().contains("disagree with their header on fiscal period"),
            failure.message,
        )
        assertTrue(
            journals.requests.none { it.status == PostingRequestStatus.POSTED },
            "the request must not be marked posted when verification fails",
        )
    }

    @Test
    fun `a line stored against a different branch from its header rolls the posting back`() {
        // Branch is the nullable dimension, so it is the one a value-by-value comparison in Kotlin
        // gets wrong: the check has to be NULL-safe in both directions.
        journals.storeLineAs = { it.copy(branchId = null) }

        val failure =
            assertFailsWith<IllegalStateException> { engine.post(request(), explicit(legs())) }

        assertTrue(
            failure.message.orEmpty().contains("disagree with their header on branch"),
            failure.message,
        )
    }

    @Test
    fun `every dimension a line diverges on is named in the failure`() {
        journals.storeLineAs = {
            it.copy(
                fiscalPeriodId = uuidV7(),
                postingDate = it.postingDate.plusDays(1),
                functionalCurrencyCode = "USD",
            )
        }

        val failure =
            assertFailsWith<IllegalStateException> { engine.post(request(), explicit(legs())) }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("fiscal period"), message)
        assertTrue(message.contains("posting date"), message)
        assertTrue(message.contains("functional currency"), message)
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

    // ---- issue #89: a replay never re-validates state that could have drifted -----------------

    @Test
    fun `a retry replays even after the period has since closed`() {
        val first = engine.post(request(), explicit(legs()))
        journals.commitClaims()
        periods.locked = snapshot(FiscalPeriodStatus.CLOSED)

        val second = engine.post(request(), explicit(legs()))

        assertEquals(first.journalEntryId, second.journalEntryId)
    }

    @Test
    fun `a retry replays even after the organisation has since been suspended`() {
        val first = engine.post(request(), explicit(legs()))
        journals.commitClaims()
        tenants.organisationPostable = false

        val second = engine.post(request(), explicit(legs()))

        assertEquals(first.journalEntryId, second.journalEntryId)
    }

    @Test
    fun `a retry replays even after an account has since been deactivated`() {
        val first = engine.post(request(), explicit(legs()))
        journals.commitClaims()
        accounts.put(DEBIT_ACCOUNT, GlAccountStatus.INACTIVE)

        val second = engine.post(request(), explicit(legs()))

        assertEquals(first.journalEntryId, second.journalEntryId)
    }

    @Test
    fun `a retry replays even when the leg provider would now throw`() {
        // The rule-resolution failure mode issue #89 point 6 describes: a retroactive supersession
        // or backdated retirement can make the resolver refuse to resolve the same event it once
        // did. A replay must not call the provider at all, so it cannot be affected.
        val first = engine.post(request(), explicit(legs()))
        journals.commitClaims()
        val throwingProvider =
            LegProvider { error("the resolver would refuse to resolve this event now") }

        val second = engine.post(request(), throwingProvider)

        assertEquals(first.journalEntryId, second.journalEntryId)
    }

    // ---- issue #89: correction lineage -----------------------------------------------------

    @Test
    fun `a correction naming a target this tenant does not hold is refused`() {
        val failure =
            assertFailsWith<ResourceNotFoundException> {
                engine.post(request(correctsPostingRequestId = uuidV7()), explicit(legs()))
            }

        assertEquals(PostingErrorCodes.CORRECTION_TARGET_NOT_FOUND, failure.code)
        assertNothingWritten()
    }

    @Test
    fun `a correction naming a target that was never reversed is refused`() {
        val original = engine.post(request(), explicit(legs()))
        journals.commitClaims()

        val failure =
            assertFailsWith<ConflictException> {
                engine.post(
                    request(
                        source = SOURCE_2,
                        correctsPostingRequestId = original.postingRequestId,
                    ),
                    explicit(legs()),
                )
            }

        assertEquals(PostingErrorCodes.CORRECTION_TARGET_NOT_REVERSED, failure.code)
    }

    @Test
    fun `a correction naming a reversed target succeeds`() {
        val original = engine.post(request(), explicit(legs()))
        journals.commitClaims()
        journals.reversedJournalIds += original.journalEntryId

        val correction =
            engine.post(
                request(source = SOURCE_2, correctsPostingRequestId = original.postingRequestId),
                explicit(legs()),
            )

        assertEquals(2, journals.entries.size)
        assertEquals(
            original.postingRequestId,
            journals.requests
                .single { it.sourceReference == SOURCE_2.idempotencyKey }
                .correctsPostingRequestId,
        )
        assertNotNull(correction)
    }

    private fun assertNothingWritten() {
        assertTrue(journals.entries.isEmpty(), "no header may survive a refusal")
        assertTrue(journals.lines.isEmpty(), "no line may survive a refusal")
        assertTrue(
            journals.requests.none { it.status == PostingRequestStatus.POSTED },
            "no request may be committed as posted after a refusal",
        )
    }

    private fun request(
        context: AccountingContext = this.context,
        source: AccountingSourceReference = SOURCE_1,
        eventCode: String = "SAVINGS_DEPOSIT",
        narrative: String? = null,
        correctsPostingRequestId: UUID? = null,
        financialFacts: List<FinancialFact> = emptyList(),
        productClass: String? = null,
    ) = LedgerPostingRequest(
        context = context,
        source = source,
        eventCode = eventCode,
        entryType = JournalEntryType.STANDARD,
        narrative = narrative,
        correctsPostingRequestId = correctsPostingRequestId,
        financialFacts = financialFacts,
        productClass = productClass,
    )

    private fun fact(
        code: String,
        amount: String,
    ) = FinancialFact(code, MonetaryAmount(BigDecimal(amount), "KES"))

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
        var branchKnown = true
        var functionalCurrency: String? = "KES"

        override fun isOrganisationPostable(organisationId: UUID) = organisationPostable

        override fun isBranchPostable(
            organisationId: UUID,
            branchId: UUID,
        ) = branchPostable

        override fun branchBelongsTo(
            organisationId: UUID,
            branchId: UUID,
        ) = branchKnown

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
        private val activeRuleAccounts = mutableSetOf<UUID>()

        /** Every account id locked via [lockForPosting], in call order, cleared by no test. */
        val postingLockOrder = mutableListOf<UUID>()

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

        override fun findControlAccountFor(
            organisationId: UUID,
            kind: ControlSubledgerKind,
        ) = accounts.values.firstOrNull {
            it.organisationId == organisationId && it.controlSubledgerKind == kind
        }

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

        override fun lockForPosting(
            organisationId: UUID,
            accountId: UUID,
        ): GlAccount? {
            postingLockOrder += accountId
            return findById(organisationId, accountId)
        }

        override fun hasActivePostingRuleLegs(
            organisationId: UUID,
            accountId: UUID,
        ) = accountId in activeRuleAccounts

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
     * In-memory journal store and read store. Claims are "uncommitted" until [commitClaims] is
     * called, mirroring that a second caller only sees a committed row; a claim within the same
     * test before that is treated as this transaction's own insert.
     */
    private class FakeJournalStore :
        JournalStore,
        JournalReadStore {
        class StoredRequest(
            val id: UUID,
            val sourceModule: String,
            val sourceEntityType: String,
            val sourceReference: String,
            val eventCode: String,
            val fingerprint: String,
            val correctsPostingRequestId: UUID?,
            val narrative: String?,
            val correlationId: String?,
            var status: PostingRequestStatus,
            var postedAt: Instant?,
            var postingRuleVersionId: UUID? = null,
        )

        val requests = mutableListOf<StoredRequest>()
        val entries = mutableListOf<NewJournalEntry>()
        val entryIds = mutableListOf<UUID>()
        val lines = mutableListOf<NewJournalLine>()
        val reversedJournalIds = mutableSetOf<UUID>()
        var dropLastLine = false

        /**
         * Rewrites each line on its way into the store, so a test can make what the database holds
         * disagree with the header the engine built - the defect `INV-4` exists to catch.
         */
        var storeLineAs: (NewJournalLine) -> NewJournalLine = { it }
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
                    id = id,
                    sourceModule = request.sourceModule,
                    sourceEntityType = request.sourceEntityType,
                    sourceReference = request.sourceReference,
                    eventCode = request.eventCode,
                    fingerprint = request.fingerprint,
                    correctsPostingRequestId = request.correctsPostingRequestId,
                    narrative = request.narrative,
                    correlationId = request.correlationId,
                    status = PostingRequestStatus.PENDING,
                    postedAt = null,
                )
            return PostingRequestClaim.Claimed(id)
        }

        override fun insertJournalEntry(entry: NewJournalEntry): UUID {
            entries += entry
            return uuidV7().also(entryIds::add)
        }

        override fun insertJournalLines(lines: List<NewJournalLine>) {
            val kept = if (dropLastLine) lines.dropLast(1) else lines
            this.lines += kept.map(storeLineAs)
        }

        override fun sumLines(
            organisationId: UUID,
            journalEntryId: UUID,
            header: JournalLineDimensions,
        ): JournalTotals {
            val mine = lines.filter { it.journalEntryId == journalEntryId }
            return JournalTotals(
                mine.filter { it.leg.side == PostingSide.DEBIT }.sumOf { it.functionalAmount },
                mine.filter { it.leg.side == PostingSide.CREDIT }.sumOf { it.functionalAmount },
                mine.size,
                DivergentLineCounts(
                    branch = mine.count { it.branchId != header.branchId },
                    fiscalPeriod = mine.count { it.fiscalPeriodId != header.fiscalPeriodId },
                    postingDate = mine.count { it.postingDate != header.postingDate },
                    currency = mine.count { it.leg.amount.currency != header.currencyCode },
                    functionalCurrency =
                        mine.count {
                            it.functionalCurrencyCode != header.functionalCurrencyCode
                        },
                ),
            )
        }

        override fun markPosted(
            organisationId: UUID,
            postingRequestId: UUID,
            postedAt: Instant,
            postingRuleVersionId: UUID?,
            actorId: UUID,
        ) {
            requests.single { it.id == postingRequestId }.apply {
                status = PostingRequestStatus.POSTED
                this.postedAt = postedAt
                this.postingRuleVersionId = postingRuleVersionId
            }
        }

        override fun findJournalEntryForRequest(
            organisationId: UUID,
            postingRequestId: UUID,
        ): JournalEntryView? =
            entries
                .indexOfFirst { it.postingRequestId == postingRequestId }
                .takeIf { it >= 0 }
                ?.let(::view)

        override fun findJournalEntry(
            organisationId: UUID,
            journalEntryId: UUID,
        ): JournalEntryView? = entryIds.indexOf(journalEntryId).takeIf { it >= 0 }?.let(::view)

        override fun findReversalOf(
            organisationId: UUID,
            journalEntryId: UUID,
        ): JournalEntryView? = if (journalEntryId in reversedJournalIds) view(0) else null

        override fun findPostingRequest(
            organisationId: UUID,
            postingRequestId: UUID,
        ): PostingRequestView? = requests.find { it.id == postingRequestId }?.let(::toRequestView)

        override fun findPostingRequestBySource(
            organisationId: UUID,
            sourceModule: String,
            sourceReference: String,
        ): PostingRequestView? =
            requests
                .find { it.sourceModule == sourceModule && it.sourceReference == sourceReference }
                ?.let(::toRequestView)

        override fun listPostingRequestsForEntity(
            organisationId: UUID,
            sourceModule: String,
            sourceEntityType: String,
            sourceEntityId: UUID,
            beforeId: UUID?,
            pageSize: Int,
        ): List<PostingRequestView> = emptyList()

        override fun findJournalLines(
            organisationId: UUID,
            journalEntryId: UUID,
        ): List<JournalLineView> = emptyList()

        private fun toRequestView(stored: StoredRequest) =
            PostingRequestView(
                id = stored.id,
                organisationId = ORGANISATION_ID,
                branchId = BRANCH_ID,
                sourceModule = stored.sourceModule,
                sourceEntityType = stored.sourceEntityType,
                sourceEntityId = SOURCE_ID,
                sourceReference = stored.sourceReference,
                eventCode = stored.eventCode,
                status = stored.status,
                postingRuleVersionId = stored.postingRuleVersionId,
                correctsPostingRequestId = stored.correctsPostingRequestId,
                postingDate = TODAY,
                businessDate = TODAY,
                narrative = stored.narrative,
                postedAt = stored.postedAt,
                requestedBy = ACTOR_ID,
                correlationId = stored.correlationId,
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
        val SOURCE_1 = AccountingSourceReference("savings", "SAVINGS_DEPOSIT", SOURCE_ID, "dep-1")
        val SOURCE_2 = AccountingSourceReference("savings", "SAVINGS_DEPOSIT", SOURCE_ID, "dep-2")

        @Suppress("unused")
        val SCALE = MoneyPolicy.STORAGE_SCALE
    }
}
