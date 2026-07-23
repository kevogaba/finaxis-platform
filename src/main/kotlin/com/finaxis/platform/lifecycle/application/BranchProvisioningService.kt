package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

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
) {
    /** Creates a branch draft under an active or provisioning organisation. */
    @Transactional
    fun createDraft(command: CreateBranchCommand): BranchDraftResult {
        requireOrganisationAllowsBranch(command.organisationId)
        require(command.branchCode.isNotBlank()) { "Branch code is required." }
        require(command.branchName.isNotBlank()) { "Branch name is required." }
        require(!lifecycleStore.branchCodeExists(command.organisationId, command.branchCode)) {
            "Branch code already exists in the organisation."
        }
        command.parentBranchId?.let { parentId ->
            require(lifecycleStore.parentBelongsToOrganisation(command.organisationId, parentId)) {
                "Parent branch must belong to the selected organisation."
            }
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

    /** Validates branch boundaries and submits a draft for approval. */
    @Transactional
    fun submitForApproval(command: SubmitBranchForApprovalCommand) {
        val branchCode =
            requireNotNull(lifecycleStore.branchCode(command.organisationId, command.branchId)) {
                "Branch was not found in the selected organisation."
            }
        require(
            !lifecycleStore.branchCodeExists(command.organisationId, branchCode, command.branchId),
        ) {
            "Branch code already exists in the organisation."
        }
        lifecycleStore.parentBranchId(command.organisationId, command.branchId)?.let { parentId ->
            require(lifecycleStore.parentBelongsToOrganisation(command.organisationId, parentId)) {
                "Parent branch must belong to the selected organisation."
            }
        }
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.SUBMIT,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Activates an approved branch only in an active organisation. */
    @Transactional
    fun activate(command: ActivateBranchCommand) {
        val creator = lifecycleStore.createdBy(command.organisationId, command.branchId)
        if (creator != null &&
            command.actorId != com.finaxis.platform.common.persistence.SystemActor.ID &&
            command.actorId == creator
        ) {
            throw ForbiddenOperationException()
        }
        require(
            lifecycleStore.organisationState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        ) {
            "A branch can be activated only for an active organisation."
        }
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.ACTIVATE,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Suspends a branch, preventing new operational assignments. */
    @Transactional
    fun suspend(command: SuspendBranchCommand) {
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.SUSPEND,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Reactivates a branch only after its organisation returns to active status. */
    @Transactional
    fun reactivate(command: ReactivateBranchCommand) {
        require(
            lifecycleStore.organisationState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        ) {
            "A branch can be reactivated only for an active organisation."
        }
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                BranchLifecycleTransition.REACTIVATE,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Closes a branch through the FSM after existing closure guards have been evaluated. */
    @Transactional
    fun close(command: CloseBranchCommand) {
        val transition =
            when (lifecycleStore.branchState(command.organisationId, command.branchId)) {
                BranchLifecycleState.ACTIVE -> BranchLifecycleTransition.CLOSE

                BranchLifecycleState.SUSPENDED -> BranchLifecycleTransition.CLOSE_SUSPENDED

                else -> throw IllegalStateException(
                    "Only active or suspended branches can be closed.",
                )
            }
        lifecycleService.transition(
            BranchTransitionCommand(
                command.organisationId,
                command.branchId,
                transition,
                TransitionCommand(reason = command.reason),
            ),
        )
    }

    /** Creates or reactivates a user assignment only inside an active organisation and branch. */
    @Transactional
    fun assignUser(command: AssignUserToBranchCommand) {
        require(assignmentStore.userExists(command.userId)) { "User account was not found." }
        require(
            lifecycleStore.organisationState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        ) {
            "User branch assignments require an active organisation."
        }
        require(
            lifecycleStore.branchState(command.organisationId, command.branchId) ==
                BranchLifecycleState.ACTIVE,
        ) {
            "User branch assignments require an active branch in the selected organisation."
        }
        val membership =
            requireNotNull(assignmentStore.membership(command.organisationId, command.userId)) {
                "User does not have a membership in the selected organisation."
            }
        require(
            membership.status !=
                com.finaxis.platform.lifecycle.domain.MembershipLifecycleState.REVOKED,
        ) {
            "A revoked membership cannot receive branch assignments."
        }
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
        if (!assignmentStore.isActive(command)) return
        val membership =
            requireNotNull(assignmentStore.membership(command.organisationId, command.userId)) {
                "User does not have a membership in the selected organisation."
            }
        val exempt = membership.type in setOf(MembershipType.SYSTEM, MembershipType.AUDITOR)
        require(
            exempt || assignmentStore.activeAssignments(command.organisationId, command.userId) > 1,
        ) {
            "An active ordinary membership must retain at least one active branch assignment."
        }
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
        require(
            lifecycleStore.organisationState(organisationId) in ALLOWED_BRANCH_CREATION_STATES,
        ) {
            "Branch creation requires an active or provisioning organisation."
        }
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
    ) {
        auditService.record(
            AuditCommand(
                actorType = "USER",
                actorId = actorId,
                tenantId = organisationId.toString(),
                action = action,
                resourceType = "BRANCH",
                resourceId = resourceId,
                outcome = AuditOutcome.SUCCESS,
            ),
        )
    }

    private companion object {
        val ALLOWED_BRANCH_CREATION_STATES =
            setOf(OrganisationLifecycleState.ACTIVE, OrganisationLifecycleState.PROVISIONING)
        const val BRANCH_ASSIGNED_TARGET = "finaxis.lifecycle.branch.user-assigned"
        const val BRANCH_ASSIGNMENT_REVOKED_TARGET = "finaxis.lifecycle.branch.user-revoked"
    }
}
