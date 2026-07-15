package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleTransition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Local user invitation and membership provisioning use cases. The service never calls Keycloak;
 * approval emits durable outbox events for downstream workers and all status changes use the FSM.
 */
@Service
class UserProvisioningService(
    private val lifecycleService: FoundationLifecycleService,
    private val store: UserProvisioningStore,
    private val branchProvisioningService: BranchProvisioningService,
    private val deactivationAssignmentRevoker: UserDeactivationAssignmentRevoker,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val clock: Clock,
) {
    /** Persists a local user invitation with branch and role prerequisites. */
    @Transactional
    fun inviteUser(command: InviteUserCommand): UserInvitationResult {
        validateInvitation(command)
        val userId =
            store.findUserIdByEmail(command.email)
                ?: store.createUserAccount(
                    command.email,
                    command.username,
                    command.displayName,
                    command.phoneE164,
                    command.invitedBy,
                )
        require(!store.membershipExists(command.organisationId, userId)) {
            "User already has a membership in the selected organisation."
        }
        val membershipId =
            store.createMembership(
                command.organisationId,
                userId,
                command.membershipType,
                command.primaryBranchId,
                command.invitedBy,
            )
        store.saveInvitationPreferences(
            command.organisationId,
            membershipId,
            command.sendKeycloakInvite,
            command.sendApplicationInvite,
            command.invitedBy,
        )
        createInvitationAssignments(command, userId)
        val snapshot = requireMembership(command.organisationId, membershipId)
        auditInvitation(command, userId, membershipId)
        return UserInvitationResult(userId, membershipId, snapshot.userStatus, snapshot.status)
    }

    private fun createInvitationAssignments(
        command: InviteUserCommand,
        userId: UUID,
    ) {
        command.branchAssignments.forEach { assignment ->
            branchProvisioningService.assignUser(
                AssignUserToBranchCommand(
                    command.organisationId,
                    userId,
                    assignment.branchId,
                    assignment.assignmentType,
                    command.invitedBy,
                ),
            )
        }
        command.roleAssignments.forEach { assignment ->
            store.assignRole(
                command.organisationId,
                userId,
                assignment.roleId,
                assignment.scopeType,
                assignment.branchId,
                command.invitedBy,
            )
        }
    }

    private fun auditInvitation(
        command: InviteUserCommand,
        userId: UUID,
        membershipId: UUID,
    ) {
        audit(
            organisationId = command.organisationId,
            action = "user.invite",
            actorId = command.invitedBy,
            resourceType = USER,
            resourceId = userId,
            requestId = command.requestId,
            metadata = mapOf(MEMBERSHIP_ID to membershipId.toString()),
        )
    }

    /** Approves a pending local invitation and emits downstream provisioning requests as needed. */
    @Transactional
    fun approveUser(command: ApproveUserCommand): UserApprovalResult {
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        require(snapshot.status == MembershipLifecycleState.PENDING_APPROVAL) {
            "Only pending memberships can be approved."
        }
        requireActiveAccessPrerequisites(command.organisationId, snapshot)

        val keycloakRequested = requestKeycloakProvisioningIfNeeded(command, snapshot)
        val membershipActivated =
            if (keycloakRequested) {
                false
            } else {
                activateMembershipIfPossible(command, snapshot)
            }
        val applicationInviteRequested =
            if (snapshot.sendApplicationInvite) {
                publishApplicationInvite(command, snapshot)
                true
            } else {
                false
            }
        audit(
            organisationId = command.organisationId,
            action = "user.approve",
            actorId = command.approvedBy,
            resourceType = USER,
            resourceId = snapshot.userId,
            requestId = command.requestId,
            metadata =
                mapOf(
                    MEMBERSHIP_ID to snapshot.id.toString(),
                    "membershipActivated" to membershipActivated.toString(),
                ),
        )
        val approved = requireMembership(command.organisationId, command.membershipId)
        return UserApprovalResult(
            snapshot.userId,
            snapshot.id,
            approved.userStatus,
            approved.status,
            keycloakRequested,
            applicationInviteRequested,
        )
    }

    /** Suspends a global user account through the shared lifecycle FSM. */
    @Transactional
    fun suspendUser(command: SuspendUserCommand) {
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.SUSPEND,
                command = transitionCommand(command.reason, command.requestId),
            ),
        )
        auditUser("user.suspend", command.organisationId, command.userId, command.actorId, command)
    }

    /** Reactivates a suspended global user account through the shared lifecycle FSM. */
    @Transactional
    fun reactivateUser(command: ReactivateUserCommand) {
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.REACTIVATE,
                command = transitionCommand(command.reason, command.requestId),
            ),
        )
        auditUser(
            "user.reactivate",
            command.organisationId,
            command.userId,
            command.actorId,
            command,
        )
    }

    /** Deactivates a global user account through start and complete FSM transitions. */
    @Transactional
    fun deactivateUser(command: DeactivateUserCommand) {
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.START_DEACTIVATION,
                command = transitionCommand(command.reason, command.requestId),
            ),
        )
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.COMPLETE_DEACTIVATION,
                command = transitionCommand(command.reason, command.requestId),
            ),
        )
        auditUser(
            "user.deactivate",
            command.organisationId,
            command.userId,
            command.actorId,
            command,
        )
        deactivationAssignmentRevoker.revoke(command)
    }

    /** Revokes a tenant membership and all active local branch and role grants for it. */
    @Transactional
    fun revokeTenantMembership(command: RevokeTenantMembershipCommand) {
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                command.membershipId,
                transition = membershipRevocationTransition(snapshot.status),
                command = transitionCommand(command.reason, command.requestId),
            ),
        )
        val branchRevocations =
            store.revokeBranchAssignmentsForMembership(
                command.organisationId,
                command.membershipId,
                command.actorId,
            )
        val roleRevocations =
            store.revokeRoleAssignmentsForMembership(
                command.organisationId,
                command.membershipId,
                command.actorId,
            )
        audit(
            organisationId = command.organisationId,
            action = "membership.revoke",
            actorId = command.actorId,
            resourceType = MEMBERSHIP,
            resourceId = command.membershipId,
            reason = command.reason,
            requestId = command.requestId,
            metadata =
                mapOf(
                    USER_ID to snapshot.userId.toString(),
                    "branchAssignmentsRevoked" to branchRevocations.toString(),
                    "roleAssignmentsRevoked" to roleRevocations.toString(),
                ),
        )
    }

    private fun validateInvitation(command: InviteUserCommand) {
        require(
            store.organisationState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        ) {
            "User invitations require an active organisation."
        }
        require(command.email.isNotBlank() && EMAIL_REGEX.matches(command.email)) {
            "A valid email address is required."
        }
        require(command.username.isNotBlank()) { "Username is required." }
        require(command.displayName.isNotBlank()) { "Display name is required." }
        command.primaryBranchId?.let { branchId ->
            requireActiveBranch(command.organisationId, branchId)
        }
        command.branchAssignments.forEach { assignment ->
            requireActiveBranch(command.organisationId, assignment.branchId)
        }
        command.roleAssignments.forEach { assignment ->
            require(store.roleExists(command.organisationId, assignment.roleId)) {
                "Role was not found in the selected organisation."
            }
            validateRoleScope(command.organisationId, assignment)
        }
        require(
            command.membershipType in BRANCH_EXEMPT_TYPES ||
                command.branchAssignments.isNotEmpty(),
        ) {
            "At least one branch assignment is required."
        }
        require(command.roleAssignments.isNotEmpty()) {
            "At least one role assignment is required."
        }
    }

    private fun requireActiveBranch(
        organisationId: UUID,
        branchId: UUID,
    ) {
        require(store.branchState(organisationId, branchId) == BranchLifecycleState.ACTIVE) {
            "Branch must be active in the selected organisation."
        }
    }

    private fun validateRoleScope(
        organisationId: UUID,
        assignment: RoleAssignmentRequest,
    ) {
        when (assignment.scopeType) {
            RoleAssignmentScopeType.TENANT -> {
                require(assignment.branchId == null) {
                    "Tenant-scoped role assignments must not include a branch."
                }
            }

            RoleAssignmentScopeType.BRANCH -> {
                requireNotNull(assignment.branchId) {
                    "Branch-scoped role assignments require a branch."
                }
                requireActiveBranch(organisationId, assignment.branchId)
            }
        }
    }

    private fun requestKeycloakProvisioningIfNeeded(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ): Boolean {
        if (store.hasKeycloakIdentity(snapshot.userId)) return false
        require(
            snapshot.userStatus in
                setOf(UserLifecycleState.DRAFT, UserLifecycleState.PENDING_APPROVAL),
        ) {
            "Only draft or pending users can start local identity provisioning."
        }
        if (snapshot.userStatus == UserLifecycleState.DRAFT) {
            lifecycleService.transition(
                UserTransitionCommand(
                    command.organisationId,
                    snapshot.userId,
                    transition = UserLifecycleTransition.SUBMIT,
                    command = transitionCommand(requestId = command.requestId),
                ),
            )
        }
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                snapshot.userId,
                transition = UserLifecycleTransition.START_IDP_PROVISIONING,
                command = transitionCommand(requestId = command.requestId),
            ),
        )
        publishKeycloakProvisioning(command, snapshot)
        return true
    }

    private fun activateMembershipIfPossible(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ): Boolean {
        require(
            snapshot.userStatus in
                setOf(UserLifecycleState.ACTIVE, UserLifecycleState.INVITED),
        ) {
            "Only active or invited users can activate a membership during approval."
        }
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                snapshot.id,
                transition = MembershipLifecycleTransition.ACTIVATE,
                command = transitionCommand(requestId = command.requestId),
            ),
        )
        return true
    }

    private fun requireActiveAccessPrerequisites(
        organisationId: UUID,
        snapshot: MembershipProvisioningSnapshot,
    ) {
        val branchExempt = snapshot.type in BRANCH_EXEMPT_TYPES
        require(branchExempt || store.hasActiveBranchAssignment(organisationId, snapshot.userId)) {
            "Membership requires an active branch assignment."
        }
        require(store.hasActiveRoleAssignment(organisationId, snapshot.userId)) {
            "Membership requires an active role assignment."
        }
    }

    private fun publishKeycloakProvisioning(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ) {
        val dispatchKey = "${command.organisationId}:${snapshot.userId}:KEYCLOAK_PROVISIONING"
        store.recordDispatch(
            command.organisationId,
            snapshot.userId,
            IdentityDispatchType.KEYCLOAK_PROVISIONING,
            dispatchKey,
            command.approvedBy,
        )
        publishApprovalRequest(
            target = KEYCLOAK_USER_PROVISIONING_REQUESTED_TARGET,
            transition = "KEYCLOAK_PROVISIONING_REQUESTED",
            command = command,
            snapshot = snapshot,
            metadata =
                mapOf(
                    EMAIL to snapshot.email,
                    USERNAME to snapshot.username,
                    DISPLAY_NAME to snapshot.displayName,
                    "sendKeycloakInvite" to snapshot.sendKeycloakInvite,
                    DISPATCH_KEY to dispatchKey,
                ),
        )
    }

    private fun publishApplicationInvite(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ) {
        val dispatchKey = "${snapshot.userId}:${command.organisationId}:APPLICATION_INVITE"
        store.recordDispatch(
            command.organisationId,
            snapshot.userId,
            IdentityDispatchType.APPLICATION_INVITE,
            dispatchKey,
            command.approvedBy,
        )
        publishApprovalRequest(
            target = APPLICATION_INVITE_REQUESTED_TARGET,
            transition = "APPLICATION_INVITE_REQUESTED",
            command = command,
            snapshot = snapshot,
            metadata = mapOf(EMAIL to snapshot.email, DISPATCH_KEY to dispatchKey),
        )
    }

    private fun publishApprovalRequest(
        target: String,
        transition: String,
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
        metadata: Map<String, Any?>,
    ) {
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = target,
                aggregateType = USER_ACCOUNT,
                aggregateId = snapshot.userId.toString(),
                transition = transition,
                fromState = snapshot.userStatus.name,
                toState = requireNotNull(store.userStatus(snapshot.userId)).name,
                actor = TransitionActor(USER, command.approvedBy.toString()),
                occurredAt = clock.instant(),
                metadata =
                    mapOf(
                        ORGANISATION_ID to command.organisationId.toString(),
                        MEMBERSHIP_ID to snapshot.id.toString(),
                        USER_ID to snapshot.userId.toString(),
                    ) + metadata,
            ),
        )
    }

    private fun requireMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot =
        requireNotNull(store.membershipSnapshot(organisationId, membershipId)) {
            "Membership was not found in the selected organisation."
        }

    private fun auditUser(
        action: String,
        organisationId: UUID,
        userId: UUID,
        actorId: UUID,
        command: Any,
    ) {
        val reason =
            when (command) {
                is SuspendUserCommand -> command.reason
                is ReactivateUserCommand -> command.reason
                is DeactivateUserCommand -> command.reason
                else -> null
            }
        val requestId =
            when (command) {
                is SuspendUserCommand -> command.requestId
                is ReactivateUserCommand -> command.requestId
                is DeactivateUserCommand -> command.requestId
                else -> null
            }
        audit(organisationId, action, actorId, USER, userId, reason, requestId)
    }

    private fun audit(
        organisationId: UUID,
        action: String,
        actorId: UUID,
        resourceType: String,
        resourceId: UUID,
        reason: String? = null,
        requestId: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        auditService.record(
            AuditCommand(
                actorType = USER,
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = action,
                resourceType = resourceType,
                resourceId = resourceId.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = reason,
                requestId = requestId,
                metadata = metadata,
            ),
        )
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val BRANCH_EXEMPT_TYPES = setOf(MembershipType.SYSTEM, MembershipType.AUDITOR)
        const val KEYCLOAK_USER_PROVISIONING_REQUESTED_TARGET =
            "finaxis.lifecycle.user.keycloak-provisioning-requested"
        const val APPLICATION_INVITE_REQUESTED_TARGET =
            "finaxis.lifecycle.user.application-invite-requested"
        const val USER = "USER"
        const val USER_ACCOUNT = "USER_ACCOUNT"
        const val MEMBERSHIP = "MEMBERSHIP"
        const val ORGANISATION_ID = "organisationId"
        const val MEMBERSHIP_ID = "membershipId"
        const val USER_ID = "userId"
        const val EMAIL = "email"
        const val USERNAME = "username"
        const val DISPLAY_NAME = "displayName"
        const val DISPATCH_KEY = "dispatchKey"
    }
}

private fun membershipRevocationTransition(
    status: MembershipLifecycleState,
): MembershipLifecycleTransition =
    when (status) {
        MembershipLifecycleState.ACTIVE -> {
            MembershipLifecycleTransition.REVOKE
        }

        MembershipLifecycleState.PENDING_APPROVAL -> {
            MembershipLifecycleTransition.REVOKE_PENDING
        }

        MembershipLifecycleState.SUSPENDED -> {
            MembershipLifecycleTransition.REVOKE_SUSPENDED
        }

        MembershipLifecycleState.REVOKED -> {
            error("Membership is already revoked.")
        }
    }

private fun transitionCommand(
    reason: String? = null,
    requestId: String? = null,
): TransitionCommand = TransitionCommand(reason = reason, requestId = requestId)
