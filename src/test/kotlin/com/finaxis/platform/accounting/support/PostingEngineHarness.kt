// A real `PostingEngine` over in-memory ports, for suites that post without a database.
//
// `AccountingPortFakes` holds the ports the *services* talk to; this file holds the ports the
// *engine* talks to, plus the assembly that wires them into an engine. The two are separated
// because they answer different questions: a service suite seeds a manual journal and asserts on
// the order of its collaborators, while everything here exists so that the posting at the end of
// that sequence is the genuine article rather than a stub. A stubbed engine returns a receipt
// whatever it is handed; this one refuses an unbalanced set of legs, a closed period and a tenant
// with no `JOURNAL` sequence, which is the only way a caller's *handling* of a refused posting can
// be stated at all.
//
// Hoisted out of the manual-journal suite (issue #93) so that "one shared set of fakes" is true of
// the engine's ports as well as the services'. Every fake is organisation-scoped exactly as its
// port is, and every one is parameterised on the ids its owning suite generates rather than
// carrying constants of its own, so two suites can drive it with different tenants.
package com.finaxis.platform.accounting.support

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.GlAccountPage
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.ledger.DivergentLineCounts
import com.finaxis.platform.accounting.application.ledger.JournalEntryView
import com.finaxis.platform.accounting.application.ledger.JournalLineDimensions
import com.finaxis.platform.accounting.application.ledger.JournalNumberAllocator
import com.finaxis.platform.accounting.application.ledger.JournalStore
import com.finaxis.platform.accounting.application.ledger.JournalTotals
import com.finaxis.platform.accounting.application.ledger.NewJournalEntry
import com.finaxis.platform.accounting.application.ledger.NewJournalLine
import com.finaxis.platform.accounting.application.ledger.NewPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingRequestClaim
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.port.outbound.PostingMetadataLookup
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * A [PostingEngine] assembled over the fakes below, with each of them left reachable.
 *
 * The engine is built from the real [PostingPeriodResolver] rather than a faked one, so the period
 * protocol it depends on runs as it will in production. What a suite then asserts on is the
 * recording ports - [accounts] for what the chart was asked about, [postings] for what was written,
 * [numbers] for the gapless counter - so the harness deliberately exposes them rather than hiding
 * them behind the engine it built.
 *
 * [contextLookup] is the caller's, not the harness's: the engine reconciles the request's context
 * against the ambient one, and a suite that switches actors between commands has to own that.
 */
internal class PostingEngineHarness(
    organisationId: UUID,
    periodId: UUID,
    businessDate: LocalDate,
    currencyCode: String,
    clock: Clock,
    contextLookup: AccountingContextLookup,
) {
    /** The tenant's fiscal calendar: one period, open, covering [businessDate]. */
    val periods = FakeOpenFiscalPeriods(organisationId, periodId, businessDate)

    /** The tenant itself: postable, with [currencyCode] as its functional currency. */
    val tenants = FakePostableTenants(currencyCode)

    /** The chart of accounts, recording every account it was asked about. */
    val accounts = FakeGlAccountStore(organisationId)

    /** The ledger write port, recording every request, header and line the engine wrote. */
    val postings = RecordingJournalStore()

    /** The gapless journal counter; set [FakeJournalNumberAllocator.next] to null to break it. */
    val numbers = FakeJournalNumberAllocator()

    /** The ambient request id the engine records as lineage; null for a background posting. */
    var ambientRequestId: String? = null

    private val metadata = PostingMetadataLookup { ambientRequestId }

    /** The tenant-currency lock, recording what the engine took and in which mode. */
    val currencyLock = RecordingFunctionalCurrencyLock()

    /** The engine under the ports above, ready to post inside an active transaction. */
    val engine =
        PostingEngine(
            contextLookup,
            metadata,
            tenants,
            currencyLock,
            PostingPeriodResolver(
                periods,
                FixedBusinessDates(businessDate),
                PermissiveAccountingGuard(),
                AuditService(NoAuditEvents(), clock),
                clock,
            ),
            accounts,
            postings,
            FakeJournalReadStore(),
            numbers,
            clock,
            PermissiveSnapshots(),
        )
}

/** Answers [businessDate] for every tenant, with posting allowed. */
internal class FixedBusinessDates(
    private val businessDate: LocalDate,
) : AccountingBusinessDateLookup {
    override fun currentBusinessDate(organisationId: UUID) =
        AccountingBusinessDate(organisationId, businessDate, postingAllowed = true)
}

/**
 * Permits every accounting permission.
 *
 * The engine's own authorization is not what an engine-backed suite is about - a service suite
 * gates on [FakeAccountingPermissionGuard] before the engine is ever entered - so refusing here
 * would only move a refusal earlier than the one under test.
 */
internal class PermissiveAccountingGuard : AccountingPermissionGuard {
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

/** Discards every audit event, for the collaborators whose audit trail is not under test. */
internal class NoAuditEvents : AuditEventRepository {
    override fun save(event: AuditEvent) = Unit
}

/** Discards every transition event, for the suites that assert on the log rather than the event. */
internal class NoTransitionEvents : TransitionEventPublisher {
    override fun publish(event: TransitionEvent) = Unit
}

/** A tenant that is postable everywhere, reporting [currencyCode] as its functional currency. */
internal class FakePostableTenants(
    private val currencyCode: String,
) : AccountingTenantLookup {
    override fun isOrganisationPostable(organisationId: UUID) = true

    override fun isBranchPostable(
        organisationId: UUID,
        branchId: UUID,
    ) = true

    override fun branchBelongsTo(
        organisationId: UUID,
        branchId: UUID,
    ) = true

    override fun functionalCurrencyOf(organisationId: UUID) = currencyCode
}

/**
 * One `OPEN` fiscal period covering the whole month of [businessDate], answered to every read.
 *
 * Deliberately not tenant-filtered on the lookups: the period protocol's refusals are the business
 * of the resolver's own suite, and a suite using this one wants the posting to reach the ledger.
 */
internal class FakeOpenFiscalPeriods(
    organisationId: UUID,
    periodId: UUID,
    businessDate: LocalDate,
) : FiscalPeriodStateStore {
    private val open =
        FiscalPeriodSnapshot(
            FiscalPeriodKey(organisationId, periodId),
            businessDate.withDayOfMonth(1),
            businessDate.withDayOfMonth(businessDate.lengthOfMonth()),
            FiscalPeriodStatus.OPEN,
        )

    override fun findById(key: FiscalPeriodKey) = open

    override fun findCovering(
        organisationId: UUID,
        postingDate: LocalDate,
    ) = open

    override fun lockCoveringForPosting(
        organisationId: UUID,
        postingDate: LocalDate,
    ) = open

    override fun lockForStateChange(key: FiscalPeriodKey) = open

    override fun updateStatus(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
        actorId: UUID,
        reason: String?,
    ) = true
}

/**
 * The chart of accounts as a posting path needs it, recording what it was asked about.
 *
 * Every account is seeded into [organisationId] and every read is filtered on the tenant asked
 * for, so a suite cannot prove a tenant leak impossible by using a store that never looked.
 */
internal class FakeGlAccountStore(
    private val organisationId: UUID,
) : GlAccountStore {
    private val accounts = linkedMapOf<UUID, GlAccount>()

    /** Every [findById] argument, in call order: a distinct-account claim rests on it. */
    val lookups = mutableListOf<UUID>()

    /** Seeds one account, replacing any account already stored under its id. */
    fun put(
        accountId: UUID,
        manualPostingAllowed: Boolean = true,
        controlAccount: Boolean = false,
        status: GlAccountStatus = GlAccountStatus.ACTIVE,
        usage: AccountUsage = AccountUsage.POSTABLE,
    ) {
        accounts[accountId] =
            GlAccount(
                id = accountId,
                organisationId = organisationId,
                code = AccountCode("A-${accountId.toString().takeLast(4)}"),
                name = "Account",
                accountClass = AccountClass.ASSET,
                usage = usage,
                status = status,
                manualPostingAllowed = manualPostingAllowed,
                isControlAccount = controlAccount,
            )
    }

    override fun findById(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount? {
        lookups += accountId
        return tenantAccount(organisationId, accountId)
    }

    override fun findByCode(
        organisationId: UUID,
        code: AccountCode,
    ) = accounts.values.firstOrNull { it.organisationId == organisationId && it.code == code }

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
    ) = tenantAccount(organisationId, accountId)

    override fun lockForPosting(
        organisationId: UUID,
        accountId: UUID,
    ) = tenantAccount(organisationId, accountId)

    override fun hasActivePostingRuleLegs(
        organisationId: UUID,
        accountId: UUID,
    ) = false

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

    private fun tenantAccount(
        organisationId: UUID,
        accountId: UUID,
    ) = accounts[accountId]?.takeIf { it.organisationId == organisationId }
}

/**
 * The ledger write port, recording what the engine wrote.
 *
 * Every claim is new. The callers this file serves key their idempotency on a record that can only
 * be posted once - a manual journal's own draft id - so the replay branch belongs to the engine's
 * own suite, which models a claim that already exists.
 *
 * [sumLines] is the `INV-4` verification read answered over the lines actually handed to
 * [insertJournalLines], divergence counts included, so a header whose dimensions disagree with its
 * own lines fails here exactly as it would against the database.
 */
internal class RecordingJournalStore : JournalStore {
    /** Every claimed posting request, in order. */
    val requests = mutableListOf<NewPostingRequest>()

    /** Every journal header inserted, in order. */
    val entries = mutableListOf<NewJournalEntry>()

    /** Every journal line inserted, in order. */
    val lines = mutableListOf<NewJournalLine>()

    /** The id of every posting request moved to `POSTED`, in order. */
    val posted = mutableListOf<UUID>()

    /** The rule version recorded against each [markPosted], nulls included. */
    val ruleVersions = mutableListOf<UUID?>()

    override fun claimPostingRequest(request: NewPostingRequest): PostingRequestClaim {
        requests += request
        return PostingRequestClaim.Claimed(uuidV7())
    }

    override fun insertJournalEntry(entry: NewJournalEntry): UUID {
        entries += entry
        return uuidV7()
    }

    override fun insertJournalLines(lines: List<NewJournalLine>) {
        this.lines += lines
    }

    override fun sumLines(
        organisationId: UUID,
        journalEntryId: UUID,
        header: JournalLineDimensions,
    ): JournalTotals {
        val mine = lines.filter { it.journalEntryId == journalEntryId }
        return JournalTotals(
            debitFunctional =
                mine.filter { it.leg.side == PostingSide.DEBIT }.sumOf { it.functionalAmount },
            creditFunctional =
                mine.filter { it.leg.side == PostingSide.CREDIT }.sumOf { it.functionalAmount },
            lineCount = mine.size,
            divergentLines =
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
        posted += postingRequestId
        ruleVersions += postingRuleVersionId
    }

    override fun findJournalEntryForRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): JournalEntryView? = null
}

/** The gapless counter; [next] set to null is a tenant with no `JOURNAL` sequence at all. */
internal class FakeJournalNumberAllocator : JournalNumberAllocator {
    /** The number the next allocation returns, or null for a tenant with no sequence row. */
    var next: Long? = 1

    override fun nextEntryNumber(organisationId: UUID): Long? =
        next?.also { current -> next = current + 1 }
}

/** The ambient [AccountingContext] a suite can swap between commands. */
internal class MutableContextLookup(
    /** The context every lookup answers with; reassign to act as a different actor or tenant. */
    var context: AccountingContext,
) : AccountingContextLookup {
    override fun current(): AccountingContext = context

    override fun require(): AccountingContext = context
}
