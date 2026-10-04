package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * Organisation-scoped branch lifecycle and user-assignment use cases. Every status change uses
 * the foundation FSM; assignment changes are externally published through the Modulith outbox.
 */
@Service
class BranchProvisioningService(
    private val lifecycleService: FoundationLifecycleService,
    private val lifecycleStore: BranchLifecycleStore,
    private val assignmentStore: BranchAssignmentStore,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val permissionGuard: PermissionGuard,
) {
    /** Creates a branch draft under an active or provisioning organisation. */
    @Transactional
    fun createDraft(command: CreateBranchCommand): BranchDraftResult {
        when (command.scope) {
            ActingScope.TENANT -> {
                permissionGuard.requireTenantPermission(
                    command.requestedBy,
                    command.organisationId,
                    "branch.create",
                )
            }

            ActingScope.PLATFORM -> {
                requirePlatformPermission(
                    command.requestedBy,
                    command.organisationId,
                    "branch.create",
                )
            }
        }
        requireOrganisationAllowsBranch(command.organisationId)
        invalidOperationUnless(command.branchCode.isNotBlank())
        invalidOperationUnless(command.branchName.isNotBlank())
        conflictUnless(!lifecycleStore.branchCodeExists(command.organisationId, command.branchCode))
        command.parentBranchId?.let { parentId ->
            resourceNotFoundUnless(
                lifecycleStore.parentBelongsToOrganisation(command.organisationId, parentId),
            )
        }
        val branchId = lifecycleStore.createDraft(command)
        audit(
            command.organisationId,
            branchId.toString(),
            "branch.create_draft",
            command.requestedBy.toString(),
        )
        return BranchDraftResult(branchId, BranchLifecycleState.DRAFT)
    }

    /**
     * Updates a draft or active branch's name, parent, timezone or address in place. A draft is
     * amendable so a returned or withdrawn one can be corrected; a branch that is pending approval,
     * suspended or closed is not. `branch.update` is the permission, not `branch.create`: editing a
     * live branch is a higher-trust act than drafting one, and a maker-only role must not be able
     * to do it with no checker (issue #203). It is checked against the target branch, before any
     * signal of whether that branch exists.
     */
    @Transactional
    fun update(command: UpdateBranchCommand) {
        requireBranchPermission(
            command.actorId,
            command.organisationId,
            command.branchId,
            "branch.update",
        )
        conflictUnless(
            lifecycleStore.branchState(command.organisationId, command.branchId) in
                AMENDABLE_BRANCH_STATES,
        )
        invalidOperationUnless(command.changedFields.isNotEmpty())
        command.branchName?.let { invalidOperationUnless(it.isNotBlank()) }
        command.timezone?.let(::requireZoneId)
        if (command.changesParent && command.parentBranchId != null) {
            requireAcyclicParent(command, command.parentBranchId)
        }
        conflictUnless(lifecycleStore.updateBranch(command))
        // Field names only: an address is personal data and a name is not worth repeating.
        audit(
            command.organisationId,
            command.branchId.toString(),
            BRANCH_UPDATE_AUDIT_ACTION,
            command.actorId.toString(),
            mapOf("changedFields" to command.changedFields.joinToString(",")),
        )
    }

    /**
     * The new parent must be a branch of this organisation other than the branch itself, not
     * closed or archived, and the branch must not already sit above it. The hierarchy lock is taken
     * first so the ancestor walk reads a tree no concurrent move can change before this one
     * commits, and the parent is claimed last so a concurrent close cannot slip past it.
     */
    private fun requireAcyclicParent(
        command: UpdateBranchCommand,
        parentId: java.util.UUID,
    ) {
        invalidOperationUnless(parentId != command.branchId)
        lifecycleStore.lockBranchHierarchy(command.organisationId)
        resourceNotFoundUnless(
            lifecycleStore.parentBelongsToOrganisation(command.organisationId, parentId),
        )
        val visited = mutableSetOf<java.util.UUID>()
        var ancestor: java.util.UUID? = parentId
        while (ancestor != null && visited.add(ancestor)) {
            invalidOperationUnless(ancestor != command.branchId)
            ancestor = lifecycleStore.parentBranchId(command.organisationId, ancestor)
        }
        // The closure guard refuses to close a branch that still has an active child, and this is
        // the re-parenting tool, so it must not put a branch under one that is already closed.
        if (!lifecycleStore.claimOpenParent(command.organisationId, parentId)) {
            throw ConflictException(safeDetail = "A closed or archived branch cannot be a parent.")
        }
    }

    /** Validates branch boundaries and submits a draft for approval. */
    @Transactional
    fun submitForApproval(command: SubmitBranchForApprovalCommand) {
        requireBranchPermission(
            command.actorId,
            command.organisationId,
            command.branchId,
            "branch.create",
            command.scope,
        )
        if (command.scope == ActingScope.PLATFORM) {
            requireOrganisationAllowsBranch(command.organisationId)
            requirePlatformCheckerOpen(command.organisationId)
        }
        val branchCode =
            lifecycleStore.branchCode(command.organisationId, command.branchId).orResourceNotFound()
        conflictUnless(
            !lifecycleStore.branchCodeExists(command.organisationId, branchCode, command.branchId),
        )
        lifecycleStore.parentBranchId(command.organisationId, command.branchId)?.let { parentId ->
            resourceNotFoundUnless(
                lifecycleStore.parentBelongsToOrganisation(command.organisationId, parentId),
            )
        }
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.SUBMIT,
                TransitionCommand(reason = command.reason?.value),
            ),
        )
        if (command.scope == ActingScope.PLATFORM) {
            audit(
                command.organisationId,
                command.branchId.toString(),
                "branch.submit_as_platform_checker",
                command.actorId.toString(),
                mapOf(CHECKER_SCOPE to ActingScope.PLATFORM.name),
            )
        }
    }

    /**
     * Approves and activates a pending branch (`branch.approve`) only in an active organisation.
     * The creator can never activate it, whatever the scope: a platform actor is held to the same
     * maker-checker rule as a tenant user (ADR 0028), and so is anyone who amended it
     * (see [requireApproverIsNotBranchMaker]). `branch.activate` is deprecated (V21) and no
     * longer checked.
     */
    @Transactional
    fun activate(command: ActivateBranchCommand) {
        requireBranchPermission(
            command.actorId,
            command.organisationId,
            command.branchId,
            "branch.approve",
            command.scope,
        )
        if (command.scope ==
            ActingScope.PLATFORM
        ) {
            requirePlatformCheckerOpen(command.organisationId)
        }
        requireApproverIsNotBranchMaker(command)
        conflictUnless(
            lifecycleStore.organisationState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        )
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.ACTIVATE,
                TransitionCommand(reason = command.reason?.value),
            ),
        )
        if (command.scope == ActingScope.PLATFORM) {
            audit(
                command.organisationId,
                command.branchId.toString(),
                "branch.activate_as_platform_checker",
                command.actorId.toString(),
                mapOf(CHECKER_SCOPE to ActingScope.PLATFORM.name),
            )
        }
    }

    /**
     * Returns a pending branch to draft with a reason (ADR 0029, 3b). One transition, two intents,
     * told apart by the actor: the branch's creator or latest submitter withdraws their own request
     * and needs `branch.create`; anyone else returns it as a checker and needs `branch.approve`.
     * A returned branch can be amended and resubmitted, and keeps its code and its creator.
     *
     * The permission asked depends on the class of actor, so classifying comes first; it reveals
     * nothing, and a branch absent from the path organisation has no maker and so classifies as a
     * return. Then permission, platform-organisation and branch 404, the platform checker window
     * (a checker step only; a withdrawal grants nothing), the organisation state, and last the
     * FSM's own state conflict.
     */
    @Transactional
    fun returnForChanges(command: ReturnBranchCommand) {
        val withdrawing =
            isBranchMaker(
                command.actorId,
                command.organisationId,
                command.branchId,
                includeSubmitter = true,
            )
        requireBranchPermission(
            command.actorId,
            command.organisationId,
            command.branchId,
            if (withdrawing) "branch.create" else "branch.approve",
            command.scope,
        )
        val platformChecker = command.scope == ActingScope.PLATFORM && !withdrawing
        if (platformChecker) requirePlatformCheckerOpen(command.organisationId)
        requireOrganisationAllowsBranch(command.organisationId)
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.RETURN_FOR_CHANGES,
                TransitionCommand(reason = command.reason.value),
            ),
        )
        // A withdrawal never carries the checker marker, even on the platform route: the marker
        // means "an approval the tenant did not make for itself" in ADR 0028's review filter.
        if (withdrawing) {
            audit(
                command.organisationId,
                command.branchId.toString(),
                "branch.withdraw",
                command.actorId.toString(),
                reason = command.reason.value,
            )
        } else if (platformChecker) {
            audit(
                command.organisationId,
                command.branchId.toString(),
                "branch.return_for_changes_as_platform_checker",
                command.actorId.toString(),
                mapOf(CHECKER_SCOPE to ActingScope.PLATFORM.name),
                command.reason.value,
            )
        }
    }

    /** Suspends a branch, preventing new operational assignments. */
    @Transactional
    fun suspend(command: SuspendBranchCommand) {
        requireBranchPermission(
            command.actorId,
            command.organisationId,
            command.branchId,
            "branch.suspend",
        )
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.SUSPEND,
                TransitionCommand(reason = command.reason.value),
            ),
        )
    }

    /** Reactivates a branch only after its organisation returns to active status. */
    @Transactional
    fun reactivate(command: ReactivateBranchCommand) {
        requireBranchPermission(
            command.actorId,
            command.organisationId,
            command.branchId,
            "branch.reactivate",
        )
        conflictUnless(
            lifecycleStore.organisationState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        )
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.REACTIVATE,
                TransitionCommand(reason = command.reason?.value),
            ),
        )
    }

    /** Closes a branch through the FSM after existing closure guards have been evaluated. */
    @Transactional
    fun close(command: CloseBranchCommand) {
        requireBranchPermission(
            command.actorId,
            command.organisationId,
            command.branchId,
            "branch.close",
        )
        val transition =
            when (lifecycleStore.branchState(command.organisationId, command.branchId)) {
                BranchLifecycleState.ACTIVE -> BranchLifecycleTransition.CLOSE
                BranchLifecycleState.SUSPENDED -> BranchLifecycleTransition.CLOSE_SUSPENDED
                else -> throw ConflictException()
            }
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                transition,
                TransitionCommand(reason = command.reason.value),
            ),
        )
    }

    /** Creates or reactivates a user assignment only inside an active organisation and branch. */
    @Transactional
    fun assignUser(command: AssignUserToBranchCommand) {
        requireBranchPermission(
            command.assignedBy,
            command.organisationId,
            command.branchId,
            "user.assign_branch",
        )
        resourceNotFoundUnless(assignmentStore.userExists(command.userId))
        conflictUnless(
            lifecycleStore.organisationState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        )
        conflictUnless(
            lifecycleStore.branchState(command.organisationId, command.branchId) ==
                BranchLifecycleState.ACTIVE,
        )
        val membership =
            assignmentStore.membership(command.organisationId, command.userId).orResourceNotFound()
        conflictUnless(
            membership.status !=
                com.finaxis.platform.lifecycle.domain.MembershipLifecycleState.REVOKED,
        )
        if (!assignmentStore.assign(command)) return
        audit(
            command.organisationId,
            command.branchId.toString(),
            "branch.assign_user",
            command.assignedBy.toString(),
        )
        publishAssignment(command)
    }

    /** Revokes an assignment while preserving required operational access for ordinary members. */
    @Transactional
    fun revokeUserAssignment(command: RevokeUserBranchAssignmentCommand) {
        requireBranchPermission(
            command.revokedBy,
            command.organisationId,
            command.branchId,
            "user.revoke_branch",
        )
        if (!assignmentStore.isActive(command)) return
        val membership =
            assignmentStore.membership(command.organisationId, command.userId).orResourceNotFound()
        val exempt = membership.type in setOf(MembershipType.SYSTEM, MembershipType.AUDITOR)
        conflictUnless(
            exempt || assignmentStore.activeAssignments(command.organisationId, command.userId) > 1,
        )
        if (!assignmentStore.revoke(command)) return
        audit(
            command.organisationId,
            command.branchId.toString(),
            "branch.revoke_user",
            command.revokedBy.toString(),
        )
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = BRANCH_ASSIGNMENT_REVOKED_TARGET,
                aggregateType = "USER_BRANCH_ASSIGNMENT",
                aggregateId = "${command.userId}:${command.branchId}:${command.assignmentType}",
                transition = "REVOKE",
                fromState = "ACTIVE",
                toState = "REVOKED",
                actor = TransitionActor("USER", command.revokedBy.toString()),
                occurredAt = Instant.now(),
                metadata = mapOf("organisationId" to command.organisationId.toString()),
            ),
        )
    }

    private fun requireOrganisationAllowsBranch(organisationId: java.util.UUID) {
        val state = lifecycleStore.organisationState(organisationId).orResourceNotFound()
        conflictUnless(state in ALLOWED_BRANCH_CREATION_STATES)
    }

    /**
     * The one maker-checker rule of branch approval, shared by the tenant `/activate` route and
     * the platform checker route. The branch's creator may not activate it. A platform checker
     * also may not activate a branch it submitted itself, so no single platform actor can both put
     * a tenant's draft up for approval and approve it (ADR 0028); the tenant route keeps the
     * creator rule for the submitter. On either route anyone who amended the branch (has a
     * successful `branch.update` on it) may not approve it either, so nobody can amend, resubmit
     * and approve another person's draft, however many later amends follow theirs. A returning
     * checker is none of these, so may still approve.
     */
    private fun requireApproverIsNotBranchMaker(command: ActivateBranchCommand) {
        val actedAsMaker =
            isBranchMaker(
                command.actorId,
                command.organisationId,
                command.branchId,
                includeSubmitter = command.scope == ActingScope.PLATFORM,
            )
        if (actedAsMaker) throw ForbiddenOperationException()
        if (command.actorId != SystemActor.ID &&
            lifecycleStore.hasAmended(command.organisationId, command.branchId, command.actorId)
        ) {
            throw ForbiddenOperationException(
                LifecycleErrorCodes.APPROVER_IS_BRANCH_MODIFIER,
                LifecycleErrorCodes.APPROVER_IS_BRANCH_MODIFIER_DETAIL,
            )
        }
    }

    /**
     * Whether [actorId] made the branch: its creator, or when [includeSubmitter] the actor of its
     * latest SUBMIT. Read scoped to [organisationId], so a branch of another tenant has no maker.
     */
    private fun isBranchMaker(
        actorId: java.util.UUID,
        organisationId: java.util.UUID,
        branchId: java.util.UUID,
        includeSubmitter: Boolean,
    ): Boolean {
        if (actorId == SystemActor.ID) return false
        val creator = lifecycleStore.createdBy(organisationId, branchId)
        val submitter =
            if (includeSubmitter) lifecycleStore.submittedBy(organisationId, branchId) else null
        return actorId == creator || actorId == submitter
    }

    /**
     * A platform checker acts only while the tenant has no ACTIVE branch beyond the one the
     * bootstrap seeds (the head office, created by the system actor), so it is the way out of the
     * first-approval deadlock and not a standing approver (ADR 0028).
     */
    private fun requirePlatformCheckerOpen(organisationId: java.util.UUID) {
        if (lifecycleStore.hasActiveBranchBeyondBootstrap(organisationId)) {
            throw ConflictException(
                LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED,
                LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED_DETAIL,
            )
        }
    }

    /** The platform permission first; the platform organisation is never a tenant to act on. */
    private fun requirePlatformPermission(
        actorId: java.util.UUID,
        organisationId: java.util.UUID,
        permissionCode: String,
    ) {
        permissionGuard.requirePlatformPermission(actorId, permissionCode)
        resourceNotFoundUnless(organisationId != PlatformOrganisation.ID)
    }

    private fun requireBranchPermission(
        actorId: java.util.UUID,
        organisationId: java.util.UUID,
        branchId: java.util.UUID,
        permissionCode: String,
        scope: ActingScope = ActingScope.TENANT,
    ) {
        when (scope) {
            ActingScope.TENANT -> {
                permissionGuard.requireBranchPermission(
                    actorId,
                    organisationId,
                    branchId,
                    permissionCode,
                )
            }

            ActingScope.PLATFORM -> {
                requirePlatformPermission(actorId, organisationId, permissionCode)
            }
        }
        // After the permission check so a caller without it cannot probe for existence. A branch
        // outside the organisation reads as absent, whatever branch the caller has selected.
        resourceNotFoundUnless(lifecycleStore.branchState(organisationId, branchId) != null)
    }

    private fun publishAssignment(command: AssignUserToBranchCommand) {
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = BRANCH_ASSIGNED_TARGET,
                aggregateType = "USER_BRANCH_ASSIGNMENT",
                aggregateId = "${command.userId}:${command.branchId}:${command.assignmentType}",
                transition = "ASSIGN",
                fromState = "INACTIVE",
                toState = "ACTIVE",
                actor = TransitionActor("USER", command.assignedBy.toString()),
                occurredAt = Instant.now(),
                metadata = mapOf("organisationId" to command.organisationId.toString()),
            ),
        )
    }

    private fun audit(
        organisationId: java.util.UUID,
        resourceId: String,
        action: String,
        actorId: String,
        metadata: Map<String, String> = emptyMap(),
        reason: String? = null,
    ) {
        auditService.record(
            AuditCommand(
                actorType = "USER",
                actorId = actorId,
                tenantId = organisationId.toString(),
                action = action,
                resourceType = BRANCH_AUDIT_ENTITY_TYPE,
                resourceId = resourceId,
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
                metadata = metadata,
            ),
        )
    }

    private companion object {
        val AMENDABLE_BRANCH_STATES =
            setOf(BranchLifecycleState.DRAFT, BranchLifecycleState.ACTIVE)
        val ALLOWED_BRANCH_CREATION_STATES =
            setOf(OrganisationLifecycleState.ACTIVE, OrganisationLifecycleState.PROVISIONING)
        const val CHECKER_SCOPE = "checkerScope"
        const val BRANCH_ASSIGNED_TARGET = "finaxis.lifecycle.branch.user-assigned"
        const val BRANCH_ASSIGNMENT_REVOKED_TARGET = "finaxis.lifecycle.branch.user-revoked"
    }
}

private fun requireZoneId(timezone: String) {
    try {
        ZoneId.of(timezone)
    } catch (_: DateTimeException) {
        throw InvalidOperationException()
    }
}

private fun invalidOperationUnless(condition: Boolean) {
    if (!condition) throw InvalidOperationException()
}

private fun conflictUnless(condition: Boolean) {
    if (!condition) throw ConflictException()
}

private fun resourceNotFoundUnless(condition: Boolean) {
    if (!condition) throw ResourceNotFoundException()
}

private fun <T : Any> T?.orResourceNotFound(): T = this ?: throw ResourceNotFoundException()
