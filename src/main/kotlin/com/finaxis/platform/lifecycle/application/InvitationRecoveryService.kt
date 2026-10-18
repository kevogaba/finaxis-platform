package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.ResendTouch
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Recovery of a member's invitation (#274): run failed identity provisioning again, or send the
 * invitation email again. Neither is an approval and neither changes a state machine; each writes
 * its own audit row.
 */
@Service
class InvitationRecoveryService(
    private val store: UserProvisioningStore,
    private val provisioning: UserProvisioningService,
    private val permissionGuard: PermissionGuard,
    private val eventPublisher: TransitionEventPublisher,
    private val auditService: AuditService,
    private val clock: Clock,
    @Value($$"""${finaxis.invitation.resend-cooldown:5m}""")
    private val cooldown: Duration = Duration.ofMinutes(DEFAULT_COOLDOWN_MINUTES),
) {
    /**
     * Runs the Keycloak provisioning job of a membership again when its last attempt failed: the
     * endpoint behind `POST /memberships/{id}/invitation/retry`, which until now could only be
     * reached by approving the membership a second time. The actor needs `user.approve` and
     * `membership.view`, checked first. Only a membership still awaiting approval whose user is
     * waiting on identity provisioning can be retried (409 otherwise, as for a repeat approval).
     * The maker-checker rules were judged once, at the approval that queued the job, so a retry is
     * not judged again; a job still scheduled or running is left alone.
     */
    @Transactional
    fun retry(command: RetryInvitationCommand) {
        permissionGuard.requireTenantPermission(
            command.actorId,
            command.organisationId,
            "user.approve",
        )
        store.lockOrganisation(command.organisationId)
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        if (snapshot.status != MembershipLifecycleState.PENDING_APPROVAL ||
            snapshot.userStatus != UserLifecycleState.PROVISIONING_IDP
        ) {
            throw ConflictException(safeDetail = NOTHING_TO_RETRY)
        }
        val outcome =
            provisioning.requeueFailedProvisioning(
                ApproveUserCommand(
                    command.organisationId,
                    command.membershipId,
                    command.actorId,
                    command.requestId,
                ),
                snapshot,
            )
        audit(
            command.organisationId,
            "user.invitation_retry",
            command.actorId,
            command.requestId,
            snapshot,
            mapOf("job" to outcome.name),
        )
    }

    /**
     * Queues the invitation email again, for an account the platform has already provisioned in
     * Keycloak (`INVITED`: it exists and has not logged in), which a retry cannot reach. Each
     * resend is one durable job, deduplicated by the dispatch's attempt count, and rate limited per
     * member: a second resend inside the cooldown is refused with 409
     * `lifecycle.invitation_resend_too_soon`, decided in the statement that records it. The actor
     * needs `user.invite` and the views it implies, checked first.
     */
    @Transactional
    fun resend(command: ResendInvitationCommand) {
        permissionGuard.requireTenantPermission(
            command.actorId,
            command.organisationId,
            "user.invite",
        )
        val snapshot = requireMembership(command.organisationId, command.membershipId)
        requireInvitedAccount(snapshot)
        val touch =
            when (val touched = store.touchDispatchForResend(snapshot.userId, cooldown)) {
                is ResendTouch.Touched -> touched
                ResendTouch.TooSoon -> throw tooSoon()
                ResendTouch.NoDispatch -> throw notApplicable()
            }
        publishResend(command, snapshot, touch)
        audit(
            command.organisationId,
            "user.invitation_resend",
            command.actorId,
            command.requestId,
            snapshot,
            mapOf("attempt" to touch.attempt.toString()),
        )
    }

    /** An invitation goes only to an invited account whose membership here is ACTIVE. */
    private fun requireInvitedAccount(snapshot: MembershipProvisioningSnapshot) {
        if (
            snapshot.userStatus != UserLifecycleState.INVITED ||
            snapshot.status != MembershipLifecycleState.ACTIVE
        ) {
            throw notApplicable()
        }
    }

    private fun tooSoon() =
        ConflictException(
            LifecycleErrorCodes.INVITATION_RESEND_TOO_SOON,
            LifecycleErrorCodes.INVITATION_RESEND_TOO_SOON_DETAIL,
        )

    private fun notApplicable() =
        ConflictException(
            LifecycleErrorCodes.INVITATION_RESEND_NOT_APPLICABLE,
            LifecycleErrorCodes.INVITATION_RESEND_NOT_APPLICABLE_DETAIL,
        )

    private fun publishResend(
        command: ResendInvitationCommand,
        snapshot: MembershipProvisioningSnapshot,
        touch: ResendTouch.Touched,
    ) {
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = INVITATION_RESEND_REQUESTED_TARGET,
                aggregateType = "USER_ACCOUNT",
                aggregateId = snapshot.userId.toString(),
                transition = "INVITATION_RESEND_REQUESTED",
                fromState = snapshot.userStatus.name,
                toState = snapshot.userStatus.name,
                actor = TransitionActor("USER", command.actorId.toString()),
                occurredAt = clock.instant(),
                metadata =
                    mapOf(
                        "organisationId" to command.organisationId.toString(),
                        "userId" to snapshot.userId.toString(),
                        "dispatchKey" to touch.dispatchKey,
                        "attempt" to touch.attempt.toString(),
                    ),
            ),
        )
    }

    private fun requireMembership(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipProvisioningSnapshot =
        store.membershipSnapshot(organisationId, membershipId) ?: throw ResourceNotFoundException()

    @Suppress("LongParameterList")
    private fun audit(
        organisationId: UUID,
        action: String,
        actorId: UUID,
        requestId: String?,
        snapshot: MembershipProvisioningSnapshot,
        metadata: Map<String, String>,
    ) {
        auditService.record(
            AuditCommand(
                actorType = "USER",
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = action,
                resourceType = "USER",
                resourceId = snapshot.userId.toString(),
                outcome = AuditOutcome.SUCCESS,
                requestId = requestId,
                metadata = mapOf("membershipId" to snapshot.id.toString()) + metadata,
                subjectUserId = snapshot.userId.toString(),
            ),
        )
    }

    private companion object {
        const val INVITATION_RESEND_REQUESTED_TARGET =
            "finaxis.lifecycle.user.invitation-resend-requested"
        const val NOTHING_TO_RETRY =
            "The user account is not in a state that allows identity provisioning."
        const val DEFAULT_COOLDOWN_MINUTES = 5L
    }
}
