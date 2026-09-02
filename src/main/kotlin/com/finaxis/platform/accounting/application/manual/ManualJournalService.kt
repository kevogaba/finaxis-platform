package com.finaxis.platform.accounting.application.manual

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.GlAccountPostingPolicy
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.ManualJournal
import com.finaxis.platform.accounting.domain.ManualJournalAggregate
import com.finaxis.platform.accounting.domain.ManualJournalLifecycle
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.ManualJournalTransition
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionException
import com.finaxis.platform.common.transitions.TransitionExecution
import com.finaxis.platform.common.transitions.TransitionExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Manual journals: the one legitimate way to name general-ledger accounts by hand, and no way
 * around the engine, the maker-checker control or the immutable journal.
 *
 * A draft is an accounting-owned aggregate with explicit debit and credit lines, a mandatory
 * reason and its own transition log. Nothing about it touches `posting_request`, `journal_entry`
 * or `journal_line` until approval - and approval **is** the posting: `APPROVE` runs the
 * [PostingEngine] with the draft's lines as legs, `entry_type = 'MANUAL'`, and the draft's id as
 * the durable source, so an approved manual journal is an ordinary immutable journal with the same
 * period lock, account eligibility, balance check, gapless number and verification read as any
 * product posting. The client never sets `POSTED` and never supplies the journal identity.
 *
 * The controls, in the platform's established shape: `journal.create_manual` (`HIGH`) to draft,
 * `journal.submit` to submit, `journal.approve` (`CRITICAL`) to approve or reject; the approver is
 * not the most recent submitter, resolved from `manual_journal_transition_log` under the header's
 * row lock; every line's account has opted into manual posting and is not a control account, which
 * [GlAccountPostingPolicy.requireManualPostingAllowed] enforces; and every draft creation and
 * approval is audited. Correction of a posted manual journal is issue #43's reversal - there is no
 * edit and no delete.
 */
@Service
@Suppress("TooManyFunctions", "LongParameterList")
class ManualJournalService(
    private val journals: ManualJournalStore,
    private val accounts: GlAccountStore,
    private val makers: ManualJournalMakerResolver,
    private val engine: PostingEngine,
    private val permissions: AccountingPermissionGuard,
    private val transitions: TransitionExecutor,
    private val auditService: AuditService,
) {
    /** Creates a draft with its lines. */
    @Transactional
    fun create(command: CreateManualJournalCommand): ManualJournal {
        permissions.requireTenantPermission(
            command.context.actorId,
            command.context.organisationId,
            AccountingPermissions.JOURNAL_CREATE_MANUAL,
        )
        validateContent(command.context.organisationId, command.content)
        val journal =
            journals.create(
                NewManualJournal(
                    organisationId = command.context.organisationId,
                    branchId = command.content.branchId ?: command.context.branchId,
                    title = command.content.title,
                    narrative = command.content.narrative,
                    transactionDate = command.content.transactionDate,
                    valueDate = command.content.valueDate,
                    postingDate = command.content.postingDate,
                    actorId = command.context.actorId,
                ),
            )
        journals.replaceLines(
            command.context.organisationId,
            journal.id,
            command.content.lines,
            command.context.actorId,
        )
        audit(
            command.context.copy(branchId = journal.branchId),
            AccountingAuditActions.JOURNAL_CREATE_MANUAL,
            journal.id,
            command.content.narrative,
            AuditSeverity.HIGH,
        )
        return journal
    }

    /** Replaces a draft's header and lines. Refused once submitted. */
    @Transactional
    fun amend(command: AmendManualJournalCommand): ManualJournal {
        permissions.requireTenantPermission(
            command.context.actorId,
            command.context.organisationId,
            AccountingPermissions.JOURNAL_CREATE_MANUAL,
        )
        val current = lock(command.context.organisationId, command.journalId)
        requireMaker(current, command.context.actorId)
        if (current.status != ManualJournalStatus.DRAFT) {
            throw ConflictException(
                code = PostingErrorCodes.MANUAL_JOURNAL_NOT_EDITABLE,
                safeDetail =
                    "Only a DRAFT manual journal can be amended; this one is ${current.status}.",
            )
        }
        validateContent(command.context.organisationId, command.content)
        journals.replaceLines(
            command.context.organisationId,
            current.id,
            command.content.lines,
            command.context.actorId,
        )
        if (!journals.updateDraft(
                command.context.organisationId,
                current.id,
                command.content,
                current.rowVersion,
                command.context.actorId,
            )
        ) {
            throw ConflictException(
                code = PostingErrorCodes.MANUAL_JOURNAL_STALE,
                safeDetail =
                    "The manual journal changed while it was being amended; reload and retry.",
            )
        }
        return requireNotNull(journals.find(command.context.organisationId, current.id))
    }

    /** Submits a draft for approval. The maker's act. */
    @Transactional
    fun submit(command: ManualJournalTransitionCommand): ManualJournal {
        permissions.requireTenantPermission(
            command.context.actorId,
            command.context.organisationId,
            AccountingPermissions.JOURNAL_SUBMIT,
        )
        return move(
            command,
            ManualJournalTransition.SUBMIT,
            underLock = { current ->
                requireMaker(current, command.context.actorId)
                validateLines(
                    command.context.organisationId,
                    journals.findLines(command.context.organisationId, current.id),
                )
            },
        )
    }

    /**
     * Approves a submitted manual journal, which posts it.
     *
     * Refuses the actor who submitted it. The posting runs inside this transaction through the
     * engine, and the produced journal's id is written onto the draft in the same compare-and-set
     * that moves it to `POSTED`, so the draft, the transition log, the audit event and the ledger
     * rows commit together or not at all.
     */
    @Transactional
    fun approve(command: ManualJournalTransitionCommand): ManualJournalApproval {
        permissions.requireTenantPermission(
            command.context.actorId,
            command.context.organisationId,
            AccountingPermissions.JOURNAL_APPROVE,
        )
        lateinit var receipt: PostingReceipt
        val posted =
            move(
                command,
                ManualJournalTransition.APPROVE,
                underLock = { current ->
                    requireDifferentActorFromTheSubmitter(command, current)
                    receipt = post(command.context, current)
                },
                journalEntryId = { receipt.journalEntryId },
            )
        audit(
            command.context,
            AccountingAuditActions.JOURNAL_APPROVE,
            posted.id,
            command.reason,
            AuditSeverity.CRITICAL,
            mapOf(
                "journalEntryId" to receipt.journalEntryId.toString(),
                "entryNumber" to receipt.journalReference,
            ),
        )
        return ManualJournalApproval(posted, receipt)
    }

    /** Rejects a submitted manual journal back to `DRAFT`, with a mandatory reason. */
    @Transactional
    fun reject(command: ManualJournalTransitionCommand): ManualJournal {
        permissions.requireTenantPermission(
            command.context.actorId,
            command.context.organisationId,
            AccountingPermissions.JOURNAL_APPROVE,
        )
        requireReason(command)
        val rejected = move(command, ManualJournalTransition.REJECT)
        audit(
            command.context.copy(branchId = rejected.branchId),
            AccountingAuditActions.JOURNAL_REJECT,
            rejected.id,
            command.reason,
            AuditSeverity.CRITICAL,
        )
        return rejected
    }

    /** Cancels a draft. Terminal. */
    @Transactional
    fun cancel(command: ManualJournalTransitionCommand): ManualJournal {
        permissions.requireTenantPermission(
            command.context.actorId,
            command.context.organisationId,
            AccountingPermissions.JOURNAL_CREATE_MANUAL,
        )
        return move(
            command,
            ManualJournalTransition.CANCEL,
            underLock = { current -> requireMaker(current, command.context.actorId) },
        )
    }

    /** Reads one manual journal with its lines, permission-gated and tenant-scoped. */
    @Transactional(readOnly = true)
    fun get(
        organisationId: UUID,
        journalId: UUID,
        actorId: UUID,
    ): ManualJournalDetail {
        permissions.requireTenantPermission(
            actorId,
            organisationId,
            AccountingPermissions.JOURNAL_VIEW,
        )
        val journal = journals.find(organisationId, journalId) ?: throw notFound()
        return ManualJournalDetail(journal, journals.findLines(organisationId, journalId))
    }

    // ---- mechanics --------------------------------------------------------------------------

    private fun move(
        command: ManualJournalTransitionCommand,
        transition: ManualJournalTransition,
        underLock: (ManualJournal) -> Unit = {},
        journalEntryId: () -> UUID? = { null },
    ): ManualJournal {
        val current = lock(command.context.organisationId, command.journalId)
        val definition = requireLegal(current, transition)
        underLock(current)
        if (!journals.updateStatus(
                command.context.organisationId,
                current.id,
                current.status,
                definition.to,
                command.reason,
                journalEntryId(),
                command.context.actorId,
            )
        ) {
            throw ConflictException(
                code = PostingErrorCodes.MANUAL_JOURNAL_STALE,
                safeDetail = "The manual journal's status changed while it was being moved.",
            )
        }
        transitions.execute(
            TransitionExecution(
                aggregate = ManualJournalAggregate(current.id, current.status),
                graph = ManualJournalLifecycle.GRAPH,
                transition = transition,
                actor =
                    TransitionActor(
                        type = ACTOR_TYPE_USER,
                        id = command.context.actorId.toString(),
                    ),
                command =
                    TransitionCommand(
                        reason = command.reason,
                        metadata =
                            mapOf(ORGANISATION_ID to command.context.organisationId.toString()),
                    ),
                persist = { it },
            ),
        )
        return requireNotNull(journals.find(command.context.organisationId, current.id))
    }

    /** The posting itself: the draft's lines become legs, and the engine does the rest. */
    private fun post(
        context: AccountingContext,
        journal: ManualJournal,
    ): PostingReceipt {
        val lines = journals.findLines(context.organisationId, journal.id)
        validateLines(context.organisationId, lines)
        return engine.post(
            LedgerPostingRequest(
                context = context.copy(branchId = journal.branchId),
                source =
                    AccountingSourceReference(
                        sourceModule = SOURCE_MODULE,
                        sourceType = SOURCE_TYPE,
                        sourceId = journal.id,
                        idempotencyKey = "manual-journal:${journal.id}",
                    ),
                eventCode = EVENT_CODE,
                entryType = JournalEntryType.MANUAL,
                dates =
                    PostingDateRequest(
                        transactionDate = journal.transactionDate,
                        valueDate = journal.valueDate,
                        postingDate = journal.postingDate,
                    ),
                narrative = journal.narrative,
            ),
        ) {
            ResolvedLegs(
                legs =
                    lines.map { line ->
                        PostingLeg(
                            accountId = line.accountId,
                            side = line.side,
                            amount = MonetaryAmount(line.amount, line.currencyCode),
                            narrative = line.narrative,
                        )
                    },
                postingRuleVersionId = null,
            )
        }
    }

    private fun lock(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal = journals.lockForStateChange(organisationId, journalId) ?: throw notFound()

    private fun requireLegal(
        current: ManualJournal,
        transition: ManualJournalTransition,
    ) = try {
        ManualJournalLifecycle.GRAPH.requireDefinition(current.status, transition)
    } catch (ex: TransitionException) {
        throw ConflictException(
            code = PostingErrorCodes.MANUAL_JOURNAL_TRANSITION_NOT_ALLOWED,
            safeDetail = "A ${current.status} manual journal cannot undergo ${transition.name}.",
            cause = ex,
        )
    }

    /**
     * Only the maker touches their own draft.
     *
     * `journal.create_manual` is a tenant-wide permission, so without this any holder of it could
     * amend another accountant's accounts and amounts, submit their draft - which would make the
     * real preparer eligible to approve it - or cancel it outright. The maker is
     * `manual_journal.created_by`, read under the row lock the caller already holds, which is the
     * same maker-from-the-record shape the fiscal-period, GL-account and posting-rule services use.
     */
    private fun requireMaker(
        journal: ManualJournal,
        actorId: UUID,
    ) {
        if (journal.createdBy != actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_NOT_THE_MAKER,
                safeDetail = "Only the maker of a manual journal may amend, submit or cancel it.",
            )
        }
    }

    private fun requireDifferentActorFromTheSubmitter(
        command: ManualJournalTransitionCommand,
        current: ManualJournal,
    ) {
        val submitter =
            makers.lastActorFor(
                command.context.organisationId,
                current.id,
                ManualJournalTransition.SUBMIT,
            )
        if (submitter != null && submitter == command.context.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_SELF_APPROVAL,
                safeDetail = "The actor who submitted a manual journal cannot approve it.",
            )
        }
    }

    private fun validateContent(
        organisationId: UUID,
        content: ManualJournalDraftContent,
    ) {
        if (content.narrative.isBlank() || content.title.isBlank()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_REASON_REQUIRED,
                safeDetail = "A manual journal needs a title and a reason.",
            )
        }
        validateLines(organisationId, content.lines)
    }

    /**
     * Every line names an existing account that has opted into manual posting and is not a control
     * account; lines are numbered without gaps. Balance is the engine's to prove at posting, and is
     * also checked here so a maker learns of an unbalanced draft before a checker sees it.
     */
    private fun validateLines(
        organisationId: UUID,
        lines: List<ManualJournalLine>,
    ) {
        requireContiguous(lines)
        requireSettledAmounts(lines)
        lines.map { it.accountId }.distinct().forEach { accountId ->
            val account =
                accounts.findById(organisationId, accountId)
                    ?: throw InvalidOperationException(
                        code = PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
                        safeDetail = "A line names a general-ledger account that does not exist.",
                    )
            GlAccountPostingPolicy.requireManualPostingAllowed(account)
        }
        requireBalanced(lines)
    }

    private fun requireContiguous(lines: List<ManualJournalLine>) {
        val contiguous = lines.map { it.lineNumber }.toSet() == (1..lines.size).toSet()
        if (lines.size > MAXIMUM_LINES) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_LINES_INVALID,
                safeDetail = "A manual journal carries at most $MAXIMUM_LINES lines.",
            )
        }
        if (lines.size < MINIMUM_LINES || !contiguous) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_LINES_INVALID,
                safeDetail =
                    "A manual journal needs at least two lines numbered 1..n without gaps.",
            )
        }
    }

    /**
     * Every line is a settled amount before it reaches the draft table.
     *
     * `manual_journal_line.amount` is `NUMERIC(23, 6)`, so PostgreSQL would silently round a value
     * carrying more precision and approval would then accept the rounded number as valid. Settling
     * here means the maker's amount is either stored exactly or refused, never quietly changed.
     */
    private fun requireSettledAmounts(lines: List<ManualJournalLine>) {
        lines.forEach { line ->
            MoneyPolicy.requireSettled(MonetaryAmount(line.amount, line.currencyCode))
        }
    }

    private fun requireBalanced(lines: List<ManualJournalLine>) {
        val debit = lines.filter { it.side == PostingSide.DEBIT }.sumOf { it.amount }
        val credit = lines.filter { it.side == PostingSide.CREDIT }.sumOf { it.amount }
        if (debit.compareTo(credit) != 0) {
            throw InvalidOperationException(
                code = PostingErrorCodes.UNBALANCED_POSTING,
                safeDetail = "Debit and credit totals must be equal.",
            )
        }
    }

    private fun requireReason(command: ManualJournalTransitionCommand) {
        if (command.reason.isNullOrBlank()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.MANUAL_JOURNAL_REASON_REQUIRED,
                safeDetail = "This manual-journal operation requires a reason.",
            )
        }
    }

    private fun audit(
        context: AccountingContext,
        action: String,
        journalId: UUID,
        reason: String?,
        severity: AuditSeverity,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        auditService.record(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = context.actorId.toString(),
                tenantId = context.organisationId.toString(),
                branchId = context.branchId?.toString(),
                action = action,
                resourceType = RESOURCE_TYPE,
                resourceId = journalId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = severity,
                reason = reason,
                metadata = metadata,
            ),
        )
    }

    private fun notFound() =
        ResourceNotFoundException(
            code = PostingErrorCodes.MANUAL_JOURNAL_NOT_FOUND,
            safeDetail = "The manual journal does not exist.",
        )

    private companion object {
        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE = "MANUAL_JOURNAL"
        const val ORGANISATION_ID = "organisationId"
        const val SOURCE_MODULE = "accounting"
        const val SOURCE_TYPE = "MANUAL_JOURNAL"
        const val EVENT_CODE = "MANUAL_JOURNAL"
        const val MINIMUM_LINES = 2

        /**
         * An adjustment a human keyed and a checker reads. The cap keeps creation's per-account
         * lookups, the batch insert, and the reads that load the whole set under a row lock all
         * bounded, which the repository requires of every accounting query.
         */
        const val MAXIMUM_LINES = 200
    }
}

/** Creates a draft manual journal. */
data class CreateManualJournalCommand(
    val context: AccountingContext,
    val content: ManualJournalDraftContent,
)

/** Replaces a draft's content. */
data class AmendManualJournalCommand(
    val context: AccountingContext,
    val journalId: UUID,
    val content: ManualJournalDraftContent,
)

/** Moves a manual journal through its lifecycle. */
data class ManualJournalTransitionCommand(
    val context: AccountingContext,
    val journalId: UUID,
    val reason: String? = null,
)

/** A posted manual journal and the receipt of the journal it produced. */
data class ManualJournalApproval(
    val journal: ManualJournal,
    val receipt: PostingReceipt,
)

/** A manual journal with its lines. */
data class ManualJournalDetail(
    val journal: ManualJournal,
    val lines: List<ManualJournalLine>,
)
