package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.jobs.FailedJobRequeuer
import com.finaxis.platform.common.jobs.JobRequeueOutcome
import com.finaxis.platform.common.jobs.deterministicJobId
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
    private val failedJobRequeuer: FailedJobRequeuer,
    makerChecker: MakerCheckerPolicy,
) {
    private val membershipCheckerRule = MembershipCheckerRule(store, auditService, makerChecker)

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
        val invitee = store.findOrCreateInvitee(command)
        val userId = invitee.userId
        // The one new branch of the flow (ADR 0037): a revoked member's row is reused and reset;
        // everything after it is the first invitation's own path.
        val reinvitedId = invitee.revokedMembershipId
        val membershipId =
            if (reinvitedId != null) {
                reinviteMembership(lifecycleService, store, command, reinvitedId)
            } else {
                store.createMembership(
                    command.organisationId,
                    userId,
                    command.membershipType,
                    command.primaryBranchId,
                    command.invitedBy,
                )
            }
        store.saveInvitationPreferences(
            command.organisationId,
            membershipId,
            command.sendKeycloakInvite,
            command.sendApplicationInvite,
            command.invitedBy,
        )
        createInvitationAssignments(command, userId)
        val snapshot = store.requireMembership(command.organisationId, membershipId)
        // A re-invitation is a transition, so its one audit row is the FSM's `membership.reinvite`
        // and no `user.invite` row repeats it (ADR 0031).
        if (reinvitedId == null) {
            audit(
                organisationId = command.organisationId,
                action = "user.invite",
                actorId = command.invitedBy,
                resourceType = USER,
                resourceId = userId,
                requestId = command.requestId,
                metadata = mapOf(MEMBERSHIP_ID to membershipId.toString()),
                subjectUserId = userId,
            )
        }
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
     *
     * Either scope then takes the tenant's organisation row lock before it activates anything,
     * and before it reads the membership (which `approve` then locks too, ADR 0037): the platform
     * checker right after its permission check, the tenant's own approver likewise, so the two are
     * serialised and a platform checker that follows a tenant approval counts the tenant's own
     * members as that approval committed them (#250, ADR 0028 point 8).
     */
    @Transactional
    fun approveUser(command: ApproveUserCommand): UserApprovalResult {
        when (command.scope) {
            ActingScope.PLATFORM -> {
                permissionGuard.requirePlatformChecker(command)
                // The organisation row before the membership row, as the tenant approver takes
                // them: `approve` locks the membership next, and two paths taking the two locks
                // in opposite orders could deadlock. It is also the lock the window check needs.
                store.lockOrganisation(command.organisationId)
            }

            ActingScope.TENANT -> {
                permissionGuard.requireTenantPermission(
                    command.approvedBy,
                    command.organisationId,
                    "user.approve",
                )
                // Unbounded itself, but it can close the platform checker's window, so it takes
                // that window's lock, in the same order: the organisation row before any other.
                store.lockOrganisation(command.organisationId)
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
     *
     * It takes no organisation lock: the administrator it activates was invited by the system
     * actor, so it never counts towards the platform checker's window (ADR 0028 point 7), and
     * the bootstrap holds its bootstrap row by then, which the organisation provisioning
     * decisions update only after the organisation row: locking it here would invert that order.
     */
    @Transactional
    fun approveAsSystem(command: ApproveUserCommand): UserApprovalResult {
        require(command.scope == ActingScope.TENANT) { "A system approval acts in tenant scope." }
        return approve(command)
    }

    private fun approve(command: ApproveUserCommand): UserApprovalResult {
        // The membership row is locked first and read after, in a second statement, and held to the
        // end of the transaction: a revoke and a re-invitation cannot commit between this read, the
        // maker-checker check made on it and the activation, which would otherwise land on the next
        // life of the membership and never be checked against its new maker (ADR 0037). The
        // organisation lock the callers take comes first; no path takes them the other way round.
        val snapshot =
            store.lockedMembershipSnapshot(command.organisationId, command.membershipId)
                ?: throw ResourceNotFoundException()
        val selfApproved =
            membershipCheckerRule.requireApproverIsNotMakerOrBeneficiary(command, snapshot)
        if (command.scope == ActingScope.PLATFORM) store.requirePlatformCheckerOpen(command)
        if (snapshot.status != MembershipLifecycleState.PENDING_APPROVAL) throw ConflictException()
        store.requireActiveAccessPrerequisites(command.organisationId, snapshot)

        // An earlier approval already queued this user's provisioning and any application invite,
        // so a repeat resumes the queued job and requests no second invite. A re-invited member
        // whose user is still waiting on provisioning from the first life has had no approval of
        // this life, so the first approval of a generation is not a repeat (ADR 0037).
        val firstOfGeneration = store.isFirstApprovalOfGeneration(command.organisationId, snapshot)
        val resumed =
            snapshot.userStatus == UserLifecycleState.PROVISIONING_IDP && !firstOfGeneration
        val keycloakRequested =
            requestKeycloakProvisioningIfNeeded(command, snapshot, firstOfGeneration)
        val membershipActivated =
            if (keycloakRequested) {
                false
            } else {
                activateMembershipIfPossible(command, snapshot)
            }
        val applicationInviteRequested =
            if (snapshot.sendApplicationInvite && !resumed) {
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
                ) + checkerScopeMetadata(command.scope) + selfApprovalMetadata(selfApproved),
            subjectUserId = snapshot.userId,
            severity = if (selfApproved) AuditSeverity.HIGH else null,
        )
        val approved = store.requireMembership(command.organisationId, command.membershipId)
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
        // The FSM writes the transition's one audit row (ADR 0031); no service row repeats it.
        lifecycleService.transition(
            UserTransitionCommand(
                command.organisationId,
                command.userId,
                transition = UserLifecycleTransition.SUSPEND,
                command = transitionCommand(command.reason.value, command.requestId),
            ),
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
        val snapshot = store.requireMembership(command.organisationId, command.membershipId)
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
        // The FSM row above is the transition's record. This row carries only what the cascade
        // ended, so the revoke is not counted twice under one action (ADR 0031).
        audit(
            organisationId = command.organisationId,
            action = "membership.assignments_revoked",
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
            subjectUserId = snapshot.userId,
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
        store.requireMembership(command.organisationId, command.membershipId)
        // The FSM writes the transition's one audit row, keyed by the membership (ADR 0031).
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                command.membershipId,
                transition = MembershipLifecycleTransition.SUSPEND,
                command = transitionCommand(command.reason.value, command.requestId),
            ),
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
        store.requireMembership(command.organisationId, command.membershipId)
        // The FSM writes the transition's one audit row, keyed by the membership (ADR 0031).
        lifecycleService.transition(
            MembershipTransitionCommand(
                command.organisationId,
                command.membershipId,
                transition = MembershipLifecycleTransition.REACTIVATE,
                command = transitionCommand(command.reason?.value, command.requestId),
            ),
        )
    }

    /**
     * Gets the job an earlier approval queued (#162) running again. It runs again under its own id
     * when FAILED; a job still scheduled, enqueued or processing is left alone; a job that is
     * gone is queued again under the same key. Within one generation of the membership a new
     * job id is never made, since two jobs could create the same Keycloak user. A re-invited
     * member's next generation has its own key and so its own job (ADR 0037), which can overlap
     * a first-life job still inside its Keycloak call: a race that the gateway's lookup before
     * create, the idempotent identity link and JobRunr's retry already absorb. A dispatch never
     * recorded, or one that SUCCEEDED, is refused with the 409 of a first approval.
     */
    internal fun requeueFailedProvisioning(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ): JobRequeueOutcome {
        val dispatchKey =
            keycloakDispatchKey(command.organisationId, snapshot.userId, snapshot.generation)
        val status = store.dispatchStatus(dispatchKey)
        if (status == null || status == DISPATCH_SUCCEEDED) {
            throw ConflictException(safeDetail = IDENTITY_PROVISIONING_REFUSED)
        }
        // The requeue is an external write on JobRunr's own connection and commits at once. If
        // this transaction rolls back later (for example, the audit write), the job still runs
        // without a committed audit row for this repeat. That is tolerated: the job is skipped
        // only when the dispatch SUCCEEDED, and a FAILED row is harmless.
        val outcome = failedJobRequeuer.requeueIfFailed(deterministicJobId(dispatchKey))
        when (outcome) {
            JobRequeueOutcome.REQUEUED -> {
                store.markDispatchPending(dispatchKey)
            }

            JobRequeueOutcome.NOT_FAILED -> {
                // nothing to do: the job is still scheduled or running
            }

            JobRequeueOutcome.NOT_FOUND -> {
                store.markDispatchPending(dispatchKey)
                publishKeycloakProvisioning(command, snapshot)
            }
        }
        return outcome
    }

    private fun requestKeycloakProvisioningIfNeeded(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
        firstOfGeneration: Boolean,
    ): Boolean {
        if (snapshot.userStatus == UserLifecycleState.PROVISIONING_IDP) {
            if (firstOfGeneration) {
                publishKeycloakProvisioning(command, snapshot)
            } else {
                requeueFailedProvisioning(command, snapshot)
            }
            return true
        }
        if (store.hasKeycloakIdentity(snapshot.userId)) return false
        if (snapshot.userStatus !in
            setOf(UserLifecycleState.DRAFT, UserLifecycleState.PENDING_APPROVAL)
        ) {
            throw ConflictException(safeDetail = IDENTITY_PROVISIONING_REFUSED)
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
        val dispatchKey =
            keycloakDispatchKey(command.organisationId, snapshot.userId, snapshot.generation)
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
                    GENERATION to snapshot.generation,
                ),
        )
    }

    private fun publishApplicationInvite(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ) {
        val dispatchKey =
            applicationInviteDispatchKey(
                command.organisationId,
                snapshot.userId,
                snapshot.generation,
            )
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
            metadata =
                mapOf(
                    EMAIL to snapshot.email,
                    DISPATCH_KEY to dispatchKey,
                    GENERATION to snapshot.generation,
                ),
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

    private fun audit(
        organisationId: UUID,
        action: String,
        actorId: UUID,
        resourceType: String,
        resourceId: UUID,
        reason: String? = null,
        requestId: String? = null,
        metadata: Map<String, String> = emptyMap(),
        subjectUserId: UUID? = null,
        severity: AuditSeverity? = null,
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
                severity = severity,
                reason = reason,
                requestId = requestId,
                metadata = metadata,
                subjectUserId = subjectUserId?.toString(),
            ),
        )
    }

    private companion object {
        const val IDENTITY_PROVISIONING_REFUSED =
            "The user account is not in a state that allows identity provisioning."
        const val DISPATCH_SUCCEEDED = "SUCCEEDED"
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
        const val GENERATION = "generation"
    }
}

/**
 * Takes a revoked membership back to `PENDING_APPROVAL` through the FSM, then resets what the new
 * invitation decides on the same row. Both run in the invitation's transaction, so the state, its
 * transition log and audit row, the reset and the cleared overrides commit or roll back together.
 * The transition comes first: it is the check that the row is still revoked, and the branch
 * assignments the invitation creates next refuse a revoked membership.
 */
private fun reinviteMembership(
    lifecycleService: FoundationLifecycleService,
    store: UserProvisioningStore,
    command: InviteUserCommand,
    membershipId: UUID,
): UUID {
    lifecycleService.transition(
        MembershipTransitionCommand(
            command.organisationId,
            membershipId,
            transition = MembershipLifecycleTransition.REINVITE,
            command = transitionCommand(requestId = command.requestId),
        ),
    )
    store.resetMembershipForReinvite(
        command.organisationId,
        membershipId,
        command.membershipType,
        command.primaryBranchId,
        command.invitedBy,
    )
    return membershipId
}

/**
 * Whether no approval of this life of the membership has queued its provisioning yet although the
 * user is already waiting on it: a re-invited member (generation above 0) whose user was left in
 * `PROVISIONING_IDP` by the first life's job, which the revoke cancelled. The new generation's
 * dispatch key has no row, which for generation 0 would be a refusal and here is the first
 * approval.
 */
private fun UserProvisioningStore.isFirstApprovalOfGeneration(
    organisationId: UUID,
    snapshot: MembershipProvisioningSnapshot,
): Boolean =
    snapshot.generation > 0 &&
        snapshot.userStatus == UserLifecycleState.PROVISIONING_IDP &&
        dispatchStatus(
            keycloakDispatchKey(organisationId, snapshot.userId, snapshot.generation),
        ) == null

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
 * The tenant's organisation row is locked before the count (by `approveUser`, ahead of the
 * membership lock `approve` takes), so two platform checkers approving
 * two memberships of one tenant at once are serialised and the second counts the first's commit,
 * and so is a tenant approval, which takes the same lock (ADR 0028 point 8, #250).
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

/** Platform checker gate: the platform permission first, then never the platform organisation. */
private fun PermissionGuard.requirePlatformChecker(command: ApproveUserCommand) {
    requirePlatformPermission(command.approvedBy, "user.approve")
    if (command.organisationId == PlatformOrganisation.ID) throw ResourceNotFoundException()
}

/** Marks an approval that only passed because the tenant switched the segregation off. */
internal fun selfApprovalMetadata(selfApproved: Boolean): Map<String, String> =
    if (selfApproved) mapOf("selfApproved" to "true") else emptyMap()

internal fun checkerScopeMetadata(scope: ActingScope): Map<String, String> =
    if (scope == ActingScope.PLATFORM) mapOf("checkerScope" to scope.name) else emptyMap()

private fun transitionCommand(
    reason: String? = null,
    requestId: String? = null,
): TransitionCommand = TransitionCommand(reason = reason, requestId = requestId)

private fun UserProvisioningStore.requireMembership(
    organisationId: UUID,
    membershipId: UUID,
): MembershipProvisioningSnapshot =
    membershipSnapshot(organisationId, membershipId) ?: throw ResourceNotFoundException()
