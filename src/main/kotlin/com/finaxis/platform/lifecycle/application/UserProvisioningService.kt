package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
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
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityDispatchType
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
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
    private val permissionGuard: PermissionGuard,
) {
    /**
     * Persists a local user invitation with branch and role prerequisites. The inviter must hold
     * `user.invite` (and the views it implies) in the tenant, checked first, before the
     * organisation state or any lookup (ADR 0030 decision 4).
     */
    @Transactional
    fun inviteUser(command: InviteUserCommand): UserInvitationResult {
        permissionGuard.requireTenantPermission(
            command.invitedBy,
            command.organisationId,
            "user.invite",
        )
        return invite(command)
    }

    /**
     * The same invitation as [inviteUser] for a system-run workflow that has no actor holding the
     * permission: only the initial-administrator bootstrap of a freshly approved tenant, which
     * invites its first administrator as the system actor. No web adapter calls it directly; the
     * one route that reaches it, `POST /platform/tenants/{id}/bootstrap/retry` through
     * `retryBootstrap` and the bootstrap service, needs `tenant.bootstrap_retry` in the platform
     * organisation and a FAILED bootstrap record, and takes every input from that stored record.
     * The inviter must be the system actor, so a caller cannot borrow this permission-free entry
     * point for a user identity, and an architecture rule limits its callers to the bootstrap.
     */
    @Transactional
    fun inviteAsSystem(command: InviteUserCommand): UserInvitationResult {
        require(
            command.invitedBy == SystemActor.ID,
        ) { "A system invitation is made by the system." }
        return invite(command)
    }

    private fun invite(command: InviteUserCommand): UserInvitationResult {
        store.validateInvitation(command)
        val userId = store.findOrCreateInvitee(command)
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
        audit(
            organisationId = command.organisationId,
            action = "user.invite",
            actorId = command.invitedBy,
            resourceType = USER,
            resourceId = userId,
            requestId = command.requestId,
            metadata = mapOf(MEMBERSHIP_ID to membershipId.toString()),
        )
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

    /**
     * Approves a pending local invitation and emits downstream provisioning requests as needed.
     * The checker is neither the maker nor the beneficiary, whatever the scope: the inviter and
     * the invited user can never approve, and a platform checker is held to the same rule as a
     * tenant user (ADR 0028). The approver authorises here, first: a platform-scope approval
     * against the platform organisation (then never the platform organisation itself, a 404), a
     * tenant-scope approval against the tenant, each with `membership.view` (ADR 0030).
     */
    @Transactional
    fun approveUser(command: ApproveUserCommand): UserApprovalResult {
        when (command.scope) {
            ActingScope.PLATFORM -> {
                permissionGuard.requirePlatformChecker(command)
            }

            ActingScope.TENANT -> {
                permissionGuard.requireTenantPermission(
                    command.approvedBy,
                    command.organisationId,
                    "user.approve",
                )
            }
        }
        return approve(command)
    }

    /**
     * The same approval as [approveUser], tenant scope, for a system-run workflow whose approver
     * holds no tenant permission yet: only the initial-administrator bootstrap, whose approver is
     * the platform actor that approved the tenant. The maker-checker exclusions still apply. No
     * web adapter calls it directly; it is reached only through the bootstrap service, so also
     * from the bootstrap retry route (see [inviteAsSystem]).
     * Unlike [inviteAsSystem] it cannot pin its actor: the approver is the platform user who
     * approved the tenant (the stored `approvedBy`), a real identity the maker-checker rules
     * must see, so its only guard is the architecture rule that limits its callers to the
     * bootstrap service.
     */
    @Transactional
    fun approveAsSystem(command: ApproveUserCommand): UserApprovalResult {
        require(command.scope == ActingScope.TENANT) { "A system approval acts in tenant scope." }
        return approve(command)
    }

    private fun approve(command: ApproveUserCommand): UserApprovalResult {
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        if (store.isMakerOrBeneficiary(command, snapshot)) throw ForbiddenOperationException()
        if (command.scope == ActingScope.PLATFORM) store.requirePlatformCheckerOpen(command)
        if (snapshot.status != MembershipLifecycleState.PENDING_APPROVAL) throw ConflictException()
        store.requireActiveAccessPrerequisites(command.organisationId, snapshot)

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
            reason = command.reason?.value,
            requestId = command.requestId,
            metadata =
                mapOf(
                    MEMBERSHIP_ID to snapshot.id.toString(),
                    "membershipActivated" to membershipActivated.toString(),
                ) + checkerScopeMetadata(command.scope),
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

    /**
     * Suspends a global user account through the shared lifecycle FSM. The actor needs
     * `user.suspend` and `user.view` in the platform organisation, checked first, before the user
     * is looked up (ADR 0030 decision 4), so an unknown id never answers a caller without them.
     */
    @Transactional
    fun suspendUser(command: SuspendUserCommand) {
        permissionGuard.requirePlatformPermission(command.actorId, "user.suspend")
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.SUSPEND,
                command = transitionCommand(command.reason.value, command.requestId),
            ),
        )
        audit(
            command.organisationId,
            "user.suspend",
            command.actorId,
            USER,
            command.userId,
            command.reason.value,
            command.requestId,
        )
    }

    /**
     * Reactivates a suspended global user account through the shared lifecycle FSM. The actor
     * needs `user.activate` and `user.view` in the platform organisation, checked first.
     */
    @Transactional
    fun reactivateUser(command: ReactivateUserCommand) {
        permissionGuard.requirePlatformPermission(command.actorId, "user.activate")
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.REACTIVATE,
                command = transitionCommand(command.reason?.value, command.requestId),
            ),
        )
        audit(
            command.organisationId,
            "user.reactivate",
            command.actorId,
            USER,
            command.userId,
            command.reason?.value,
            command.requestId,
        )
    }

    /**
     * Deactivates a global user account through start and complete FSM transitions. The actor
     * needs `user.deactivate` and `user.view` in the platform organisation, checked first.
     */
    @Transactional
    fun deactivateUser(command: DeactivateUserCommand) {
        permissionGuard.requirePlatformPermission(command.actorId, "user.deactivate")
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.START_DEACTIVATION,
                command = transitionCommand(command.reason.value, command.requestId),
            ),
        )
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.COMPLETE_DEACTIVATION,
                command = transitionCommand(command.reason.value, command.requestId),
            ),
        )
        audit(
            command.organisationId,
            "user.deactivate",
            command.actorId,
            USER,
            command.userId,
            command.reason.value,
            command.requestId,
        )
        deactivationAssignmentRevoker.revoke(command)
    }

    /**
     * Revokes a tenant membership and all active local branch and role grants for it. The actor
     * needs `membership.revoke` and `membership.view` in the tenant, checked first.
     */
    @Transactional
    fun revokeTenantMembership(command: RevokeTenantMembershipCommand) {
        permissionGuard.requireTenantPermission(
            command.actorId,
            command.organisationId,
            "membership.revoke",
        )
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                command.membershipId,
                transition = membershipRevocationTransition(snapshot.status),
                command = transitionCommand(command.reason.value, command.requestId),
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
            reason = command.reason.value,
            requestId = command.requestId,
            metadata =
                mapOf(
                    USER_ID to snapshot.userId.toString(),
                    "branchAssignmentsRevoked" to branchRevocations.toString(),
                    "roleAssignmentsRevoked" to roleRevocations.toString(),
                ),
        )
    }

    /**
     * Suspends an organisation membership through the shared lifecycle FSM. The actor needs
     * `membership.suspend` and `membership.view` in the tenant, checked first.
     */
    @Transactional
    fun suspendMembership(command: SuspendMembershipCommand) {
        permissionGuard.requireTenantPermission(
            command.actorId,
            command.organisationId,
            "membership.suspend",
        )
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                command.membershipId,
                transition = MembershipLifecycleTransition.SUSPEND,
                command = transitionCommand(command.reason.value, command.requestId),
            ),
        )
        audit(
            command.organisationId,
            "membership.suspend",
            command.actorId,
            USER,
            snapshot.userId,
            command.reason.value,
            command.requestId,
        )
    }

    /**
     * Reactivates a suspended organisation membership through the shared lifecycle FSM. The actor
     * needs `membership.reactivate` and `membership.view` in the tenant, checked first.
     */
    @Transactional
    fun reactivateMembership(command: ReactivateMembershipCommand) {
        permissionGuard.requireTenantPermission(
            command.actorId,
            command.organisationId,
            "membership.reactivate",
        )
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                command.membershipId,
                transition = MembershipLifecycleTransition.REACTIVATE,
                command = transitionCommand(command.reason?.value, command.requestId),
            ),
        )
        audit(
            command.organisationId,
            "membership.reactivate",
            command.actorId,
            USER,
            snapshot.userId,
            command.reason?.value,
            command.requestId,
        )
    }

    private fun requestKeycloakProvisioningIfNeeded(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ): Boolean {
        if (store.hasKeycloakIdentity(snapshot.userId)) return false
        if (snapshot.userStatus !in
            setOf(UserLifecycleState.DRAFT, UserLifecycleState.PENDING_APPROVAL)
        ) {
            throw ConflictException(
                safeDetail =
                    "The user account is not in a state that allows identity provisioning.",
            )
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
        if (snapshot.userStatus !in setOf(UserLifecycleState.ACTIVE, UserLifecycleState.INVITED)) {
            throw ConflictException(
                safeDetail =
                    "The user account is not in a state that allows the membership to be " +
                        "activated.",
            )
        }
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                snapshot.id,
                transition = MembershipLifecycleTransition.ACTIVATE,
                command = transitionCommand(command.reason?.value, command.requestId),
            ),
        )
        return true
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
        val correlation = mutableMapOf<String, Any>()
        command.bootstrapRequestId?.let { correlation["bootstrapRequestId"] = it }
        command.bootstrapAttempt?.let { correlation["bootstrapAttempt"] = it }

        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = target,
                aggregateType = USER_ACCOUNT,
                aggregateId = snapshot.userId.toString(),
                transition = transition,
                fromState = snapshot.userStatus.name,
                // Internal invariant: the user was just read through this same transaction.
                toState = requireNotNull(store.userStatus(snapshot.userId)).name,
                actor = TransitionActor(USER, command.approvedBy.toString()),
                occurredAt = clock.instant(),
                metadata =
                    mapOf(
                        ORGANISATION_ID to command.organisationId.toString(),
                        MEMBERSHIP_ID to snapshot.id.toString(),
                        USER_ID to snapshot.userId.toString(),
                    ) + metadata + correlation,
            ),
        )
    }

    private fun requireMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot =
        store.membershipSnapshot(organisationId, membershipId) ?: throw ResourceNotFoundException()

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
            throw ConflictException(safeDetail = "This membership has already been revoked.")
        }
    }

/**
 * A platform checker acts only on an ACTIVE tenant that has no ACTIVE member beyond its bootstrap
 * administrator, so it is the way out of the first-approval deadlock and not a standing approver.
 */
private fun UserProvisioningStore.requirePlatformCheckerOpen(command: ApproveUserCommand) {
    if (organisationState(command.organisationId) != OrganisationLifecycleState.ACTIVE) {
        throw ConflictException()
    }
    if (hasActiveMembershipBeyondBootstrap(command.organisationId)) {
        throw ConflictException(
            LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED,
            LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED_DETAIL,
        )
    }
}

/** The inviter (maker) and the invited user (beneficiary) may never approve. */
private fun UserProvisioningStore.isMakerOrBeneficiary(
    command: ApproveUserCommand,
    snapshot: MembershipProvisioningSnapshot,
): Boolean {
    val inviter = membershipInvitedBy(command.organisationId, command.membershipId)
    val isMaker =
        inviter != null &&
            command.approvedBy != com.finaxis.platform.common.persistence.SystemActor.ID &&
            command.approvedBy == inviter
    return isMaker || command.approvedBy == snapshot.userId
}

/** Platform checker gate: the platform permission first, then never the platform organisation. */
private fun PermissionGuard.requirePlatformChecker(command: ApproveUserCommand) {
    requirePlatformPermission(command.approvedBy, "user.approve")
    if (command.organisationId == PlatformOrganisation.ID) throw ResourceNotFoundException()
}

private fun checkerScopeMetadata(scope: ActingScope): Map<String, String> =
    if (scope == ActingScope.PLATFORM) mapOf("checkerScope" to scope.name) else emptyMap()

private fun transitionCommand(
    reason: String? = null,
    requestId: String? = null,
): TransitionCommand = TransitionCommand(reason = reason, requestId = requestId)
