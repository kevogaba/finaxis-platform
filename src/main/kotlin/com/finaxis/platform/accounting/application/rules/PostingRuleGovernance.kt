package com.finaxis.platform.accounting.application.rules

import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.PostingRuleVersion
import com.finaxis.platform.accounting.domain.PostingRuleVersionTransition
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * The maker-checker and window guards [PostingRuleService] applies to a posting-rule version.
 *
 * Every guard here is called by the service **under the rule's row lock**, which every version
 * transition of a rule takes, so the maker, submitter and approver it reads cannot change between
 * the check and the write. None of them is switchable by a tenant setting: posting rules decide
 * which accounts every automated posting lands in, so their separation of duties is enforced
 * unconditionally (`docs/security/accounting-authorization.md`, issue #127).
 */
@Component
class PostingRuleGovernance(
    private val makers: PostingRuleVersionMakerResolver,
    private val businessDates: AccountingBusinessDateLookup,
    private val permissions: AccountingPermissionGuard,
) {
    /**
     * Only the author of a version touches its draft.
     *
     * `posting_rule.update` and `posting_rule.submit` are tenant-wide permissions, so without this
     * any holder of them could rewrite the legs another administrator wrote, or submit them - which
     * would make the real author eligible to approve their own configuration. The author is
     * `posting_rule_version.created_by`. A version with no recorded author is refused rather than
     * opened to everyone: the service always records one, so a null here is a row it did not write.
     */
    fun requireMaker(
        version: PostingRuleVersion,
        actorId: UUID,
    ) {
        if (version.createdBy != actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.POSTING_RULE_NOT_THE_MAKER,
                safeDetail = "Only the author of a posting-rule version may amend or submit it.",
            )
        }
    }

    /**
     * Who may cancel a draft: its author under `posting_rule.update`, or anyone else under
     * `posting_rule.approve`.
     *
     * The second half is the recovery path [requireMaker] would otherwise remove. A draft blocks
     * its rule's next version until it is submitted, and only its author may submit or amend it,
     * so an author who leaves the tenant would freeze the rule for good. Cancelling moves nothing
     * into force - a cancelled version never governed a date and is never selectable - so letting
     * a checker withdraw someone else's draft gives nobody the power to approve their own legs.
     */
    fun requireMayCancel(
        command: PostingRuleVersionTransitionCommand,
        version: PostingRuleVersion,
    ) {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            if (version.createdBy == command.actorId) {
                AccountingPermissions.POSTING_RULE_UPDATE
            } else {
                AccountingPermissions.POSTING_RULE_APPROVE
            },
        )
    }

    /**
     * The amendment was prepared against the draft as it stands, not against an earlier view.
     *
     * The expected version comes from the *caller*: read under the lock it would always equal
     * itself, and comparing a value to itself protects nobody - the defect issue #127 removed.
     */
    fun requireCurrentVersion(
        version: PostingRuleVersion,
        expectedRowVersion: Long,
    ) {
        if (version.rowVersion != expectedRowVersion) {
            throw staleEdit()
        }
    }

    /**
     * Refuses an approval by the version's submitter or its author.
     *
     * [requireMaker] makes the two the same actor for every version submitted since issue #127,
     * so the author check is defence in depth: it still refuses the author of a version another
     * actor submitted before that guard existed, which would otherwise wait in `PENDING_APPROVAL`
     * for exactly the self-approval the guard was added to prevent.
     */
    fun requireDifferentActorFromTheMaker(
        command: PostingRuleVersionTransitionCommand,
        version: PostingRuleVersion,
    ) {
        val submitter =
            makers.lastActorFor(
                command.organisationId,
                version.id,
                PostingRuleVersionTransition.SUBMIT,
            )
        if (submitter != null && submitter == command.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.POSTING_RULE_SELF_APPROVAL,
                safeDetail = "The actor who submitted a posting-rule version cannot approve it.",
            )
        }
        if (version.createdBy == command.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.POSTING_RULE_SELF_APPROVAL,
                safeDetail = "The actor who authored a posting-rule version cannot approve it.",
            )
        }
    }

    /** Refuses a retirement by the actor who activated the version. */
    fun requireDifferentActorFromTheApprover(
        command: PostingRuleVersionTransitionCommand,
        version: PostingRuleVersion,
    ) {
        val approver =
            makers.lastActorFor(
                command.organisationId,
                version.id,
                PostingRuleVersionTransition.APPROVE,
            )
        if (approver != null && approver == command.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.POSTING_RULE_SELF_APPROVAL,
                safeDetail = "The actor who activated a posting-rule version cannot retire it.",
            )
        }
    }

    /**
     * Refuses a retirement cutoff before the tenant's current business date.
     *
     * A backdated `effective_to` would leave every posting date between it and today with no
     * approved version, so a prior-period correction that must re-post under the rule in force on
     * its posting date could no longer be made. The business date, not the wall clock, is the
     * authority, because it is what the ledger itself posts by.
     *
     * Read **under a shared lock** on the `business_date` row, not with the plain lookup. A plain
     * read could answer D while a concurrent advance to D+1 commits before this retirement does,
     * which would persist a cutoff of D after the tenant had moved on - exactly the backdating
     * this check exists to refuse. `BusinessDateService.advance` updates that row, so it and a
     * retirement now serialise: whichever commits second sees the other's result.
     */
    fun requireNotBeforeTheBusinessDate(
        organisationId: UUID,
        closeOn: LocalDate,
    ) {
        val businessDate =
            businessDates.currentBusinessDateForPosting(organisationId)?.businessDate
                ?: throw ConflictException(
                    code = PostingErrorCodes.BUSINESS_DATE_UNAVAILABLE,
                    safeDetail = "The organisation has no initialised business date.",
                )
        if (closeOn.isBefore(businessDate)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.POSTING_RULE_WINDOW_INVALID,
                safeDetail =
                    "A version cannot be retired before the current business date, " +
                        "$businessDate: the dates in between have already been governed by it.",
            )
        }
    }

    /** Stale-edit refusals. */
    companion object {
        /**
         * The one refusal every stale-edit path raises, so the comparison under the lock and the
         * store's compare-and-set cannot answer a retrying caller differently.
         */
        fun staleEdit(): ConflictException =
            ConflictException(
                code = PostingErrorCodes.POSTING_RULE_VERSION_STALE,
                safeDetail = "The version changed while it was being amended; reload and retry.",
            )
    }
}
