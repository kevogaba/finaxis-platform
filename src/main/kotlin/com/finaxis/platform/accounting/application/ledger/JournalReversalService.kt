package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.application.posting.ReversePostingCommand
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Reversal: the only correction of posted financial history (ADR 0020, `INV-6`).
 *
 * A reversal is a **new** journal with `entry_type = 'REVERSAL'`, its lines mirroring the
 * original's with the side flipped and the same positive amounts, linked by
 * `reverses_journal_entry_id`. The original is never touched; *"has this been reversed"* is
 * derived from the link. Negative-amount storno is not used, because a reversed 1,000 debit must
 * appear as 1,000 of debit turnover and 1,000 of credit turnover, not as zero.
 *
 * It goes through the same [PostingEngine] as every other journal, so the period lock, the
 * account eligibility check, the balance check, the idempotency claim, the gapless number and the
 * verification read all apply unchanged. What this service adds is the controls a reversal needs
 * over an ordinary posting, in this order: the caller's claimed context reconciled against the
 * ambient one; the `CRITICAL` permission; a mandatory, bounded reason; the per-journal
 * serialisation lock; the original exists, is not itself a reversal and is not already reversed -
 * all decided **under** that lock; the caller is operating in the branch the original was booked
 * to; and separation of duties against the actor who posted the original.
 *
 * **The context is reconciled first, before the permission check and before any journal is read.**
 * [PostingService] is a cross-module port and its command context is not a trust boundary, so a
 * caller could otherwise name another tenant and learn from the difference between
 * `journal_not_found`, `journal_already_reversed` and `reversal_not_reversible` whether a journal
 * exists in it. The engine reconciles again when it posts; this is the earlier check that keeps the
 * refusals from being an oracle.
 *
 * **A reversal is booked to the branch the original was booked to, never the caller's.** The
 * mirrored legs must land in the ledger the original moved, or the two cancel tenant-wide while
 * leaving both branches wrong. The branch is therefore taken from `journal_entry.branch_id` rather
 * than from the command, and a caller whose active branch is a different one is refused rather
 * than silently re-attributed: you reverse where the journal was posted.
 *
 * **The reversing actor must not be the actor who posted the original.** `INV-10` asks for a
 * checker who is not the maker on a privileged operation, and the catalogue has no
 * `journal.reverse_request` code to build a submit/approve pair from - so, as the fiscal-period and
 * GL-account services do, the maker is resolved from the record itself: `journal_entry.created_by`.
 * A teller who mis-keys a deposit has it reversed by a supervisor, which is the control most
 * SACCO procedures already require.
 *
 * **A reversal cannot be reversed.** Undoing a reversal is a fresh posting that names the
 * original's request in `corrects_posting_request_id`, which keeps *"has this journal been
 * reversed"* a one-index answer and the correction lineage on the mutable request.
 *
 * **Serialisation is an advisory lock, not a row lock.** `SELECT … FOR UPDATE` on `journal_entry`
 * needs the `UPDATE` privilege issue #54 revokes, so the race between two reversals is decided by
 * `pg_advisory_xact_lock` on the original's id, with `uq_journal_entry_reversal_once` as the
 * backstop that makes the second write impossible even if the lock were bypassed.
 */
@Service
class JournalReversalService(
    private val engine: PostingEngine,
    private val contextLookup: AccountingContextLookup,
    private val journals: JournalReadStore,
    private val locks: JournalReversalLock,
    private val permissions: AccountingPermissionGuard,
    private val auditService: AuditService,
) {
    /** Reverses the named journal with an equal-and-opposite one; see the class KDoc. */
    @Transactional
    fun reverse(command: ReversePostingCommand): PostingReceipt {
        val ambient = reconcileContext(command.context)
        val organisationId = command.context.organisationId
        permissions.requireTenantPermission(
            command.context.actorId,
            organisationId,
            AccountingPermissions.JOURNAL_REVERSE,
        )
        requireReason(command)

        locks.lockForReversal(organisationId, command.originalJournalEntryId)
        val original = requireReversible(organisationId, command)
        requireDifferentActorFromThePoster(original, command)
        val reversalContext = reversalContextFor(command.context, ambient, original)

        val lines = journals.findJournalLines(organisationId, original.id)
        val receipt =
            engine.post(
                LedgerPostingRequest(
                    context = reversalContext,
                    source = reversalSource(original.id),
                    eventCode = REVERSAL_EVENT,
                    entryType = JournalEntryType.REVERSAL,
                    dates = command.dates,
                    narrative = command.reason,
                    reversesJournalEntryId = original.id,
                ),
            ) { ResolvedLegs(lines.map(::mirror), postingRuleVersionId = null) }

        recordAudit(command, reversalContext, original.id, receipt)
        return receipt
    }

    /**
     * A reason is mandatory, and bounded by what the ledger can store.
     *
     * The reason becomes `posting_request.narrative` and `journal_entry.narrative`, both capped at
     * [MAX_REASON_LENGTH] characters by `V7`. Checking it here means an over-long reason is a named
     * application failure rather than a constraint violation surfacing as a data-access exception.
     */
    private fun requireReason(command: ReversePostingCommand) {
        if (command.reason.isBlank()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REVERSAL_REASON_REQUIRED,
                safeDetail = "A reversal requires a reason.",
            )
        }
        if (command.reason.length > MAX_REASON_LENGTH) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REVERSAL_REASON_TOO_LONG,
                safeDetail = "A reversal reason is at most $MAX_REASON_LENGTH characters.",
            )
        }
    }

    /**
     * Reconciles the caller's claimed context against the ambient one and returns the ambient.
     *
     * Deliberately the first thing [reverse] does. The engine performs the same reconciliation when
     * it posts, but by then this service has already read the journal and could have answered with
     * a code that distinguishes another tenant's journals from nothing at all.
     */
    private fun reconcileContext(claimed: AccountingContext): AccountingContext {
        val ambient = contextLookup.require()
        val branchAgrees = claimed.branchId == null || claimed.branchId == ambient.branchId
        if (claimed.organisationId != ambient.organisationId ||
            claimed.actorId != ambient.actorId ||
            !branchAgrees
        ) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.CONTEXT_MISMATCH,
                safeDetail = "The posting context does not match the active request context.",
            )
        }
        return ambient
    }

    /**
     * The context the reversal posts under: the caller's, with the original's branch.
     *
     * A tenant-level original reverses at tenant level. A branch original reverses into that
     * branch, and only by a caller already operating in it - re-attributing the caller's branch
     * would leave the original's branch permanently out of balance, and silently accepting a
     * tenant-level caller would hide which branch the correction belongs to.
     */
    private fun reversalContextFor(
        claimed: AccountingContext,
        ambient: AccountingContext,
        original: JournalEntryView,
    ): AccountingContext {
        val branchId = original.branchId ?: return claimed.copy(branchId = null)
        if (ambient.branchId != branchId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.REVERSAL_BRANCH_MISMATCH,
                safeDetail =
                    "A journal is reversed from the branch it was posted to; select that branch.",
            )
        }
        return claimed.copy(branchId = branchId)
    }

    /**
     * The original exists in this tenant, is not itself a reversal, and has no reversal yet.
     *
     * Decided under the advisory lock, so a concurrent second reversal waits here and then finds
     * the first one's row rather than racing it to the unique index.
     */
    private fun requireReversible(
        organisationId: UUID,
        command: ReversePostingCommand,
    ): JournalEntryView {
        val original =
            journals.findJournalEntry(organisationId, command.originalJournalEntryId)
                ?: throw ResourceNotFoundException(
                    code = PostingErrorCodes.JOURNAL_NOT_FOUND,
                    safeDetail = "The journal entry does not exist.",
                )
        requireNotAlreadyCorrected(organisationId, original)
        return original
    }

    /** A reversal is never reversed, and a journal is reversed at most once. */
    private fun requireNotAlreadyCorrected(
        organisationId: UUID,
        original: JournalEntryView,
    ) {
        if (original.entryType == JournalEntryType.REVERSAL) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REVERSAL_NOT_REVERSIBLE,
                safeDetail =
                    "A reversal cannot be reversed; post the correction afresh and name the " +
                        "original request it corrects.",
            )
        }
        if (journals.findReversalOf(organisationId, original.id) != null) {
            throw ConflictException(
                code = PostingErrorCodes.JOURNAL_ALREADY_REVERSED,
                safeDetail = "The journal entry has already been reversed.",
            )
        }
    }

    private fun requireDifferentActorFromThePoster(
        original: JournalEntryView,
        command: ReversePostingCommand,
    ) {
        if (original.postedBy == command.context.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.JOURNAL_SELF_REVERSAL,
                safeDetail = "The actor who posted a journal cannot reverse it.",
            )
        }
    }

    private fun mirror(line: JournalLineView) =
        PostingLeg(
            accountId = line.accountId,
            side =
                when (line.side) {
                    PostingSide.DEBIT -> PostingSide.CREDIT
                    PostingSide.CREDIT -> PostingSide.DEBIT
                },
            amount = MonetaryAmount(line.amount, line.currencyCode),
            narrative = line.narrative,
            subledgerReference = line.subledgerReference,
        )

    /**
     * Records the reversal in the same transaction as the journal, so the audit and the ledger
     * stand or fall together. The reason is the reversal's, and the metadata carries both ids so an
     * auditor can walk from either journal to the other without joining the ledger.
     */
    private fun recordAudit(
        command: ReversePostingCommand,
        reversalContext: AccountingContext,
        originalId: UUID,
        receipt: PostingReceipt,
    ) {
        auditService.record(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = command.context.actorId.toString(),
                tenantId = command.context.organisationId.toString(),
                branchId = reversalContext.branchId?.toString(),
                action = AccountingAuditActions.JOURNAL_REVERSE,
                resourceType = RESOURCE_TYPE_JOURNAL_ENTRY,
                resourceId = originalId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.CRITICAL,
                reason = command.reason,
                metadata =
                    mapOf(
                        "reversalJournalEntryId" to receipt.journalEntryId.toString(),
                        "reversalEntryNumber" to receipt.journalReference,
                        "postingDate" to command.dates.postingDate?.toString(),
                    ),
            ),
        )
    }

    private companion object {
        /** `chk_posting_request_narrative` and `chk_journal_entry_narrative`, both from `V7`. */
        const val MAX_REASON_LENGTH = 500

        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE_JOURNAL_ENTRY = "JOURNAL_ENTRY"
        const val REVERSAL_EVENT = "JOURNAL_REVERSAL"
        const val REVERSAL_SOURCE_MODULE = "accounting"
        const val REVERSAL_SOURCE_TYPE = "JOURNAL_REVERSAL"

        /**
         * The reversal's own durable identity is the journal it reverses: there can only ever be
         * one, so `uq_posting_request_source` backs `uq_journal_entry_reversal_once` up at the
         * request level as well.
         */
        fun reversalSource(originalJournalEntryId: UUID) =
            AccountingSourceReference(
                sourceModule = REVERSAL_SOURCE_MODULE,
                sourceType = REVERSAL_SOURCE_TYPE,
                sourceId = originalJournalEntryId,
                idempotencyKey = "reversal:$originalJournalEntryId",
            )
    }
}

/**
 * Serialises reversals of one journal.
 *
 * Declared in the application layer and implemented over a PostgreSQL transaction advisory lock,
 * because a row lock on the immutable `journal_entry` is unavailable under the target privilege
 * model. Held for the remainder of the caller's transaction and released at commit or rollback.
 */
fun interface JournalReversalLock {
    /** Blocks until no other transaction is reversing [journalEntryId] in [organisationId]. */
    fun lockForReversal(
        organisationId: UUID,
        journalEntryId: UUID,
    )
}
