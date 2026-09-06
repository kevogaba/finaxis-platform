package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountAggregate
import com.finaxis.platform.accounting.domain.GlAccountLifecycle
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.GlAccountTransition
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
 * The chart-of-accounts lifecycle, under the reusable FSM and its maker-checker control.
 *
 * [ChartOfAccountsService] owns what an account *is* — its code, class, placement and the rules
 * that keep the chart structurally valid. This owns how it *moves*: submit, approve, reject and
 * deactivate, each a declared transition that writes a `gl_account_transition_log` row and an
 * audit record. Splitting them that way is why neither can quietly become the other's back door:
 * the create path always lands in `DRAFT` and the update path never changes status.
 *
 * **Approval requires a different actor from the submitter.** Not a different *role* — a role
 * holding both `gl_account.submit` and `gl_account.approve` is legitimate and `TENANT_ADMIN`
 * deliberately holds both, because separation of duties is a property of who acts on a given
 * record, not of how permissions are partitioned. What is forbidden is one person performing both
 * acts on the same account.
 */
@Service
class GlAccountLifecycleService(
    private val accounts: GlAccountStore,
    private val writes: GlAccountWriteStore,
    private val makers: GlAccountMakerResolver,
    private val permissions: AccountingPermissionGuard,
    private val transitions: TransitionExecutor,
    private val auditService: AuditService,
) {
    /** Submits a draft account for approval. */
    @Transactional
    fun submit(command: GlAccountTransitionCommand): GlAccount =
        move(command, GlAccountTransition.SUBMIT, AccountingPermissions.GL_ACCOUNT_SUBMIT)

    /**
     * Approves a submitted account, refusing the actor who submitted it.
     *
     * The submitter is read from `gl_account_transition_log`, not from `gl_account.created_by`: an
     * account that was submitted, rejected, corrected by someone else and resubmitted has a
     * different maker from its creator, and the control is about the most recent submission.
     */
    @Transactional
    fun approve(command: GlAccountTransitionCommand): GlAccount {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.GL_ACCOUNT_APPROVE,
        )
        // Under the lock, not before it. See `requireDifferentActorFrom`.
        return move(
            command,
            GlAccountTransition.APPROVE,
            permissionAlreadyChecked = true,
            underLock = { requireDifferentActorFrom(command, GlAccountTransition.SUBMIT) },
        )
    }

    /** Rejects a submitted account back to `DRAFT`, with a mandatory reason. */
    @Transactional
    fun reject(command: GlAccountTransitionCommand): GlAccount {
        // Permission first, then the reason. An earlier revision checked the reason first, so an
        // actor holding no accounting permission at all learned from the reason_required response
        // that the operation exists and what it needs. Every other entry point here and in the
        // fiscal-period service gates on the permission first.
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.GL_ACCOUNT_APPROVE,
        )
        requireReason(command)
        return move(command, GlAccountTransition.REJECT, permissionAlreadyChecked = true)
    }

    /**
     * Withdraws an active account from use.
     *
     * There is no delete anywhere in this design: an account that has ever been referenced must
     * stay resolvable, so `INACTIVE` is the only withdrawal. Before this it was a status write on
     * [ChartOfAccountsService]; it is a transition now, so it leaves history like every other move.
     */
    @Transactional
    fun deactivate(command: GlAccountTransitionCommand): GlAccount {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.GL_ACCOUNT_DEACTIVATE,
        )
        requireReason(command)
        // Two actors, against the account's most recent approver. `INV-10` names GL-account
        // deactivation as privileged, and an earlier revision made it the one privileged transition
        // here that a single actor could complete alone: permission plus a reason and the account
        // was out of the chart. Deactivation changes the shape of every future report, so the actor
        // who put the account into service must not also be the one who withdraws it.
        //
        // Against APPROVE rather than SUBMIT, because approval is the act that made the account
        // usable and the approver is therefore this transition's maker. There is no
        // `gl_account.deactivate_request` code to build a submit/approve pair from, and the
        // catalogue is frozen; actor identity from the transition log is how the fiscal-period
        // service satisfies the same invariant with the codes `V5` seeded.
        return move(
            command,
            GlAccountTransition.DEACTIVATE,
            permissionAlreadyChecked = true,
            underLock = {
                requireDifferentActorFrom(command, GlAccountTransition.APPROVE)
                requireNotReferencedByActiveRule(command)
            },
        )
    }

    /**
     * Locks the account, validates against the snapshot read under it, and runs the transition.
     *
     * The order is the same every time and each step is where it is for a reason: check the
     * permission, validate the request's own shape, **take the lock**, re-read under it, validate
     * every state- or history-dependent control against that snapshot, then write. [underLock] is
     * how a caller adds one of those controls — the maker-checker checks are exactly that, and
     * running them before the lock is what made them bypassable.
     */
    private fun move(
        command: GlAccountTransitionCommand,
        transition: GlAccountTransition,
        permissionCode: String? = null,
        permissionAlreadyChecked: Boolean = false,
        underLock: () -> Unit = {},
    ): GlAccount {
        if (!permissionAlreadyChecked) {
            permissions.requireTenantPermission(
                command.actorId,
                command.organisationId,
                requireNotNull(permissionCode),
            )
        }
        val current =
            accounts.lockForStateChange(command.organisationId, command.accountId)
                ?: throw ResourceNotFoundException(
                    code = NOT_FOUND,
                    safeDetail = "The general-ledger account does not exist.",
                )
        val definition = requireLegal(current, transition)
        underLock()

        // updateStatus, not update: the edit path cannot write a status at all, which is what
        // stops it being a way around this approval. The compare-and-set on the status this
        // transaction observed also makes two concurrent approvals of one account fail rather than
        // both succeeding - an earlier revision read, copied and wrote the whole row, so the later
        // write simply overwrote the earlier one.
        val moved = current.copy(status = definition.to, statusReason = command.reason)
        if (!writes.updateStatus(
                organisationId = command.organisationId,
                accountId = command.accountId,
                from = current.status,
                to = definition.to,
                reason = command.reason,
                actorId = command.actorId,
            )
        ) {
            throw ConflictException(
                code = CONCURRENT_CHANGE,
                safeDetail =
                    "The general-ledger account changed while this transition was in " +
                        "progress.",
            )
        }

        transitions.execute(
            TransitionExecution(
                aggregate =
                    GlAccountAggregate(command.accountId, current.status),
                graph = GlAccountLifecycle.GRAPH,
                transition = transition,
                actor = TransitionActor(type = ACTOR_TYPE_USER, id = command.actorId.toString()),
                command =
                    TransitionCommand(
                        reason = command.reason,
                        metadata = mapOf(ORGANISATION_ID to command.organisationId.toString()),
                    ),
                persist = { it },
            ),
        )
        recordAudit(command, transition, moved)
        return moved
    }

    /**
     * Resolves the transition, reporting an illegal one through the published error contract.
     *
     * The graph raises `TransitionNotAllowedException`, which is reusable-infrastructure vocabulary
     * rather than part of accounting's RFC 9457 contract, so a caller would otherwise see a
     * framework type carrying no stable code.
     */
    private fun requireLegal(
        current: GlAccount,
        transition: GlAccountTransition,
    ) = try {
        GlAccountLifecycle.GRAPH.requireDefinition(current.status, transition)
    } catch (ex: TransitionException) {
        throw ConflictException(
            code = TRANSITION_NOT_ALLOWED,
            safeDetail = "A ${current.status} account cannot undergo ${transition.name}.",
            cause = ex,
        )
    }

    /**
     * Rejects an actor who also performed the account's most recent [maker] transition.
     *
     * **Must run while the account's exclusive row lock is held**, which is why `move` invokes it
     * after `lockForStateChange` rather than each entry point calling it first. The check reads
     * `gl_account_transition_log`, so it looks independent of the account row - the trap.
     * Run before the lock, an approval whose lookup happens while the submission is still
     * uncommitted resolves **no** submitter at all; the submission then commits, `move` reads
     * `PENDING_APPROVAL`, and the same actor approves their own work. Holding the lock removes the
     * window: every transition writes that log under this same lock, so while it is held the most
     * recent [maker] row cannot change.
     */
    private fun requireDifferentActorFrom(
        command: GlAccountTransitionCommand,
        maker: GlAccountTransition,
    ) {
        val makerActor =
            makers.lastActorFor(
                command.organisationId,
                command.accountId,
                maker,
            )
        if (makerActor != null && makerActor == command.actorId) {
            throw ForbiddenOperationException(
                code = SELF_APPROVAL,
                safeDetail = MAKER_DETAIL.getValue(maker),
            )
        }
    }

    /**
     * Refuses deactivation while the account is named by a leg of an approved (`ACTIVE`,
     * `SUPERSEDED` or `RETIRED`) posting-rule version.
     *
     * Run under the account's exclusive [GlAccountStore.lockForStateChange] lock, the same lock
     * [com.finaxis.platform.accounting.application.ledger.PostingEngine] takes in shared mode
     * before it validates a posting's accounts, and posting-rule approval also takes before it
     * validates a version's accounts. That serialises this check against both: a version cannot be
     * approved to reference this account while it is being deactivated, and this account cannot be
     * deactivated out from under a version that just became `ACTIVE`, because whichever transaction
     * gets there first is the one the other waits for.
     *
     * Without the guard the resolver would keep selecting the version for any posting date its
     * effective window still covers - including a `SUPERSEDED` or `RETIRED` one resolving a
     * backdated posting - and every posting through it would fail at the engine's account
     * eligibility check instead, far from the change that caused it.
     */
    private fun requireNotReferencedByActiveRule(command: GlAccountTransitionCommand) {
        if (accounts.hasActivePostingRuleLegs(command.organisationId, command.accountId)) {
            throw ConflictException(
                code = REFERENCED_BY_ACTIVE_RULE,
                safeDetail =
                    "The account is used by an in-force posting rule and cannot be deactivated " +
                        "until that rule is retired or amended.",
            )
        }
    }

    private fun requireReason(command: GlAccountTransitionCommand) {
        if (command.reason.isNullOrBlank()) {
            throw InvalidOperationException(
                code = REASON_REQUIRED,
                safeDetail = "This chart-of-accounts operation requires a reason.",
            )
        }
    }

    private fun recordAudit(
        command: GlAccountTransitionCommand,
        transition: GlAccountTransition,
        moved: GlAccount,
    ) {
        val (action, severity) = AUDIT_ACTIONS.getValue(transition)
        auditService.record(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = command.actorId.toString(),
                tenantId = command.organisationId.toString(),
                action = action,
                resourceType = RESOURCE_TYPE_GL_ACCOUNT,
                resourceId = command.accountId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = severity,
                reason = command.reason,
                metadata = mapOf("accountCode" to moved.code.value, "status" to moved.status.name),
            ),
        )
    }

    private companion object {
        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE_GL_ACCOUNT = "GL_ACCOUNT"
        const val ORGANISATION_ID = "organisationId"
        const val NOT_FOUND = "accounting.gl_account_not_found"
        const val SELF_APPROVAL = "accounting.gl_account_self_approval"
        const val REASON_REQUIRED = "accounting.gl_account_reason_required"
        const val TRANSITION_NOT_ALLOWED = "accounting.gl_account_transition_not_allowed"
        const val CONCURRENT_CHANGE = "accounting.gl_account_concurrent_change"
        const val REFERENCED_BY_ACTIVE_RULE = "accounting.gl_account_referenced_by_active_rule"

        /**
         * The audit action a rejection records.
         *
         * Not a permission code: rejection is authorised by `gl_account.approve`, because the
         * checker who can approve is exactly who can refuse. It still needs an action of its own.
         * An earlier revision recorded a rejection as `gl_account.update`, and the audit query API
         * filters on an exact action, so a rejection was unsearchable and indistinguishable from an
         * ordinary amendment — while the only thing in its metadata that hinted otherwise was the
         * resulting status.
         */
        const val REJECT_ACTION = "gl_account.reject"

        /** Which act the different-actor rule is protecting, phrased for the caller. */
        val MAKER_DETAIL =
            mapOf(
                GlAccountTransition.SUBMIT to
                    "The actor who submitted an account change cannot approve it.",
                GlAccountTransition.APPROVE to
                    "The actor who approved an account cannot also deactivate it.",
            )

        /**
         * The audit action each transition records, and the severity it carries.
         *
         * `APPROVE` and `DEACTIVATE` come from [AccountingAuditActions], which is the registry of
         * **high-risk** actions the audit-coverage ratchet tracks; these two are what it was
         * waiting on. `SUBMIT` and `REJECT` are not high-risk operations — a submission changes
         * nothing a report can see and a rejection returns an account to `DRAFT` — so they take
         * their names from the permission codes instead and are recorded at `MEDIUM`. Putting
         * them in the high-risk registry would say something about them that is not true.
         */
        val AUDIT_ACTIONS =
            mapOf(
                GlAccountTransition.SUBMIT to
                    (AccountingPermissions.GL_ACCOUNT_SUBMIT to AuditSeverity.MEDIUM),
                GlAccountTransition.APPROVE to
                    (AccountingAuditActions.GL_ACCOUNT_APPROVE to AuditSeverity.HIGH),
                GlAccountTransition.REJECT to (REJECT_ACTION to AuditSeverity.MEDIUM),
                GlAccountTransition.DEACTIVATE to
                    (AccountingAuditActions.GL_ACCOUNT_DEACTIVATE to AuditSeverity.HIGH),
            )
    }
}

/** A request to move one GL account to its next lifecycle state. */
data class GlAccountTransitionCommand(
    val organisationId: UUID,
    val accountId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)
