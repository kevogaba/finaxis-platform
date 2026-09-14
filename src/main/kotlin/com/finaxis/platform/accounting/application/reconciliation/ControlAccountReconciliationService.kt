package com.finaxis.platform.accounting.application.reconciliation

import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerAggregate
import com.finaxis.platform.accounting.SubledgerProofProvider
import com.finaxis.platform.accounting.SubledgerProofQuery
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.web.pagination.PaginationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The detective control of `INV-14`: proves that a control account's general-ledger balance equals
 * the aggregate the owning module reports for its subsidiary ledger, and records the evidence.
 *
 * The preventive control is same-transaction posting; this is the proof that it held. A run reads
 * the GL side through [LedgerBalanceQuery], asks the one [SubledgerProofProvider] that supports the
 * account's control class through the public port - never the module's persistence - compares the
 * two in the ledger's sign convention, and writes a `control_account_reconciliation_run` row.
 * Exact equality is the default; a tolerance is an explicitly supplied, recorded policy.
 *
 * **A run never mutates a journal.** A `BREAK` is corrected by a reversal or a fresh posting, after
 * which a new run proves the correction, and the break is marked `RESOLVED` by an actor other than
 * the one who ran it (`INV-10`), with a reason, audited at `CRITICAL`.
 *
 * Proofs are repeatable: the same account, scope and as-of date produce the same two numbers as
 * long as no posting into that scope has a posting date on or before the as-of date - which is what
 * makes running the proof at period close meaningful. This service defines the proof; it does not
 * schedule it. End-of-day and period-close orchestration is a later concern and hooks in by calling
 * [run].
 */
@Service
@Suppress("LongParameterList")
class ControlAccountReconciliationService(
    private val accounts: GlAccountStore,
    private val ledger: LedgerBalanceQuery,
    private val providers: SubledgerProofProviderRegistry,
    private val snapshots: ProofSnapshot,
    private val runs: ReconciliationRunStore,
    private val tenants: AccountingTenantLookup,
    private val businessDates: AccountingBusinessDateLookup,
    private val permissions: AccountingPermissionGuard,
    private val auditService: AuditService,
    private val pagination: PaginationProperties,
    private val clock: Clock,
) {
    /**
     * Runs one proof and records it, matched or broken.
     *
     * `REPEATABLE READ`, because the two numbers this compares have to describe one instant. Under
     * the repository's default `READ COMMITTED` the general-ledger aggregate and the provider's
     * aggregate are separate snapshots, and a posting committing between them fabricates a `BREAK`
     * or - the worse direction - offsets a real one into a `MATCHED` that is recorded as evidence.
     * [ProofSnapshot] both proves the isolation took effect, which a Spring annotation alone does
     * not when this method joins an outer transaction, and yields the identity a provider reading
     * outside this transaction adopts.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun run(command: RunReconciliationCommand): ReconciliationRun {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.RECONCILIATION_RUN,
        )
        requireNotInTheFuture(command.organisationId, command.asOfDate)
        requireBranchInOrganisation(command.organisationId, command.branchId)
        val account = requireControlAccount(command.organisationId, command.accountId)
        val kind = requireNotNull(account.controlSubledgerKind)
        val currency = requireFunctionalCurrency(command.organisationId)
        val provider = providers.providerFor(kind)
        requireTolerance(command.tolerance)

        val snapshotId = snapshots.currentSnapshotId()
        val glBalance =
            ledger.signedBalanceAsOf(
                command.organisationId,
                account.id,
                command.branchId,
                command.asOfDate,
            )
        val aggregate = askProvider(provider, command, kind, currency, snapshotId)
        val subledgerBalance = storableBalance(aggregate?.balance ?: BigDecimal.ZERO)
        val matched = (glBalance - subledgerBalance).abs() <= command.tolerance

        return runs.create(
            NewReconciliationRun(
                organisationId = command.organisationId,
                accountId = account.id,
                branchId = command.branchId,
                kind = kind,
                asOfDate = command.asOfDate,
                currencyCode = currency,
                glBalance = glBalance,
                subledgerBalance = subledgerBalance,
                tolerance = command.tolerance,
                status = if (matched) ReconciliationStatus.MATCHED else ReconciliationStatus.BREAK,
                provider = provider.providerName,
                detail =
                    buildMap {
                        // Provider detail first, then accounting's own facts: a provider that
                        // happens to use the same key must not be able to contradict them.
                        aggregate?.detail?.let(::putAll)
                        put("positionCount", aggregate?.positionCount ?: 0L)
                        // The snapshot both balances were read from, so the row is evidence that
                        // describes its own provenance. Without it a reader can see two numbers
                        // and has to take on trust that they were ever true at the same instant.
                        put("snapshotId", snapshotId)
                    },
                actorId = command.actorId,
            ),
        )
    }

    /**
     * Signs off a `BREAK` after it has been investigated and corrected through ordinary postings.
     *
     * The resolver must not be the actor who ran the proof, and must say why. The run row is the
     * only thing this writes: the ledger was corrected, if it needed to be, by a reversal or a
     * fresh posting that a later run proves.
     */
    @Transactional
    fun resolve(command: ResolveReconciliationCommand): ReconciliationRun {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.RECONCILIATION_RESOLVE,
        )
        requireReason(command.reason)
        val run =
            runs.lock(command.organisationId, command.runId)
                ?: throw ResourceNotFoundException(
                    code = PostingErrorCodes.RECONCILIATION_RUN_NOT_FOUND,
                    safeDetail = "The reconciliation run does not exist.",
                )
        requireResolvableBy(run, command.actorId)
        val resolvedAt = clock.instant()
        val resolved =
            runs.resolve(
                command.organisationId,
                run.id,
                command.reason,
                command.actorId,
                resolvedAt,
            )
        check(resolved) {
            "reconciliation run ${run.id} was locked as BREAK yet did not resolve"
        }
        auditService.record(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = command.actorId.toString(),
                tenantId = command.organisationId.toString(),
                branchId = run.branchId?.toString(),
                action = AccountingAuditActions.RECONCILIATION_RESOLVE,
                resourceType = RESOURCE_TYPE,
                resourceId = run.id.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.CRITICAL,
                reason = command.reason,
                metadata =
                    mapOf(
                        "accountId" to run.accountId.toString(),
                        "asOfDate" to run.asOfDate.toString(),
                        "difference" to run.difference.toPlainString(),
                    ),
            ),
        )
        return requireNotNull(runs.find(command.organisationId, run.id))
    }

    private fun requireReason(reason: String) {
        if (reason.isBlank()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.RECONCILIATION_REASON_REQUIRED,
                safeDetail = "Resolving a reconciliation break requires a reason.",
            )
        }
    }

    /** Only a `BREAK` resolves, and never by the actor who produced it. */
    private fun requireResolvableBy(
        run: ReconciliationRun,
        actorId: UUID,
    ) {
        if (run.status != ReconciliationStatus.BREAK) {
            throw ConflictException(
                code = PostingErrorCodes.RECONCILIATION_NOT_A_BREAK,
                safeDetail = "Only a BREAK can be resolved; this run is ${run.status}.",
            )
        }
        if (run.runBy == actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.RECONCILIATION_SELF_RESOLUTION,
                safeDetail = "The actor who ran a reconciliation cannot resolve its break.",
            )
        }
    }

    /** The runs of one control account, newest first, one bounded page at a time. */
    @Transactional(readOnly = true)
    fun listRuns(query: ListReconciliationRunsQuery): ReconciliationRunPage {
        permissions.requireTenantPermission(
            query.actorId,
            query.organisationId,
            AccountingPermissions.RECONCILIATION_VIEW,
        )
        val pageSize = query.pageSize ?: pagination.defaultPageSize
        if (pageSize < 1 || pageSize > pagination.maxPageSize) {
            throw InvalidOperationException(
                code = PAGE_SIZE_OUT_OF_RANGE,
                safeDetail = "The page size must be between 1 and ${pagination.maxPageSize}.",
            )
        }
        val items =
            runs.listForAccount(query.organisationId, query.accountId, query.cursor, pageSize)
        return ReconciliationRunPage(items, if (items.size < pageSize) null else items.last().id)
    }

    private fun requireControlAccount(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount {
        val account =
            accounts.findById(organisationId, accountId)
                ?: throw ResourceNotFoundException(
                    code = PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
                    safeDetail = "The general-ledger account does not exist.",
                )
        if (!account.isControlAccount) {
            throw InvalidOperationException(
                code = PostingErrorCodes.NOT_A_CONTROL_ACCOUNT,
                safeDetail = "Account ${account.code} is not a control account.",
            )
        }
        return account
    }

    private fun requireFunctionalCurrency(organisationId: UUID): String =
        tenants.functionalCurrencyOf(organisationId)
            ?: throw ConflictException(
                code = PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE,
                safeDetail = "The organisation has no functional currency.",
            )

    /**
     * The provider's balance at the scale the evidence row stores, or a refusal.
     *
     * The verdict is computed from this value rather than the raw one, because the row is
     * `NUMERIC(23, 6)`: comparing at full precision and storing at six decimals could persist a
     * `BREAK` whose two stored balances are identical, which is evidence that contradicts itself.
     * Excess precision is refused rather than rounded, so a provider learns its contract is wrong
     * instead of having its number quietly changed.
     */
    private fun storableBalance(balance: BigDecimal): BigDecimal {
        if (balance.stripTrailingZeros().scale() > MoneyPolicy.STORAGE_SCALE) {
            throw InvalidOperationException(
                code = PostingErrorCodes.SUBLEDGER_BALANCE_PRECISION,
                safeDetail =
                    "A sub-ledger balance may carry at most " +
                        "${MoneyPolicy.STORAGE_SCALE} fractional digits.",
            )
        }
        return balance.setScale(MoneyPolicy.STORAGE_SCALE, MoneyPolicy.ROUNDING)
    }

    /**
     * A proof is of a date that has happened, never of one that has not.
     *
     * A future `as_of_date` would record a verdict before the postings for that date exist, and the
     * row would stay visible as evidence after they arrive and change the real balances. The
     * business date, not the wall clock, is the authority: it is what the ledger itself posts by.
     */
    private fun requireNotInTheFuture(
        organisationId: UUID,
        asOfDate: LocalDate,
    ) {
        val businessDate =
            businessDates.currentBusinessDate(organisationId)?.businessDate
                ?: throw ConflictException(
                    code = PostingErrorCodes.BUSINESS_DATE_UNAVAILABLE,
                    safeDetail = "The organisation has no initialised business date.",
                )
        if (asOfDate.isAfter(businessDate)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.RECONCILIATION_DATE_IN_FUTURE,
                safeDetail = "A reconciliation proves a date that has happened, not a future one.",
            )
        }
    }

    /**
     * Asks the owning module for its aggregate, in the scope and on the snapshot the proof read.
     *
     * A provider answering in another currency is refused rather than converted: the run row stores
     * one currency for both balances, so a converted aggregate would be evidence about a number
     * nobody reported.
     */
    private fun askProvider(
        provider: SubledgerProofProvider,
        command: RunReconciliationCommand,
        kind: ControlSubledgerKind,
        currency: String,
        snapshotId: String,
    ): SubledgerAggregate? {
        val aggregate =
            provider.aggregate(
                SubledgerProofQuery(
                    organisationId = command.organisationId,
                    branchId = command.branchId,
                    kind = kind,
                    asOfDate = command.asOfDate,
                    currencyCode = currency,
                    snapshotId = snapshotId,
                ),
            )
        if (aggregate != null && aggregate.currencyCode != currency) {
            throw InvalidOperationException(
                code = PostingErrorCodes.CURRENCY_NOT_SUPPORTED,
                safeDetail =
                    "The sub-ledger reported ${aggregate.currencyCode}; the ledger is $currency.",
            )
        }
        return aggregate
    }

    /**
     * A scope names a branch of this organisation, or it names nothing at all.
     *
     * Existence, not postability: a proof is of a date that has happened, so a branch closed since
     * then is a legitimate subject of one. Checked before either side is read, so an unknown branch
     * is a named refusal rather than a foreign-key violation raised at the very end, after both
     * aggregates have been computed and a provider has been asked about a scope that never existed.
     */
    private fun requireBranchInOrganisation(
        organisationId: UUID,
        branchId: UUID?,
    ) {
        if (branchId == null) {
            return
        }
        if (!tenants.branchBelongsTo(organisationId, branchId)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.BRANCH_NOT_IN_ORGANISATION,
                safeDetail = "The branch does not belong to this organisation.",
            )
        }
    }

    private fun requireTolerance(tolerance: BigDecimal) {
        if (tolerance.signum() < 0) {
            throw InvalidOperationException(
                code = PostingErrorCodes.RECONCILIATION_TOLERANCE_INVALID,
                safeDetail = "A reconciliation tolerance cannot be negative.",
            )
        }
    }

    private companion object {
        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE = "CONTROL_ACCOUNT_RECONCILIATION_RUN"
        const val PAGE_SIZE_OUT_OF_RANGE = "accounting.page_size_out_of_range"
    }
}

/** Runs one proof for a control account, optionally within one branch, as of a business date. */
data class RunReconciliationCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val accountId: UUID,
    val asOfDate: LocalDate,
    val branchId: UUID? = null,
    val tolerance: BigDecimal = BigDecimal.ZERO,
)

/** Signs off a break. */
data class ResolveReconciliationCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val runId: UUID,
    val reason: String,
)

/** Lists the runs of one account, keyset over the run id. */
data class ListReconciliationRunsQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val accountId: UUID,
    val pageSize: Int? = null,
    val cursor: UUID? = null,
)

/** One bounded page of runs, newest first. */
data class ReconciliationRunPage(
    val items: List<ReconciliationRun>,
    val nextCursor: UUID?,
)
