package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Revokes every active branch and role assignment left behind by a completed user deactivation.
 * Assignment mutation, audit, and externalized-event publication are kept in this dedicated
 * collaborator so the generic FSM transition and [UserProvisioningService] stay free of
 * assignment side effects.
 */
@Component
class UserDeactivationAssignmentRevoker(
    private val writer: FoundationLifecycleWriter,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val clock: Clock,
) {
    /** Revokes and audits every active assignment left behind by [command]'s deactivated user. */
    fun revoke(command: DeactivateUserCommand) {
        val revoked = writer.revokeActiveAssignments(command.organisationId, command.userId)
        revoked.forEach { assignment ->
            auditService.record(
                AuditCommand(
                    actorType = USER,
                    actorId = command.actorId.toString(),
                    tenantId = command.organisationId.toString(),
                    action = "user.deactivation_assignment_revoked",
                    resourceType = assignment.assignmentType,
                    resourceId = assignment.assignmentId.toString(),
                    outcome = AuditOutcome.SUCCESS,
                    reason = command.reason,
                    requestId = command.requestId,
                    metadata = mapOf(USER_ID to command.userId.toString()),
                ),
            )
            eventPublisher.publish(
                ExternalizedTransitionEvent(
                    target = USER_DEACTIVATION_ASSIGNMENT_REVOKED_TARGET,
                    aggregateType = assignment.assignmentType,
                    aggregateId = assignment.assignmentId.toString(),
                    transition = "REVOKE",
                    fromState = "ACTIVE",
                    toState = "REVOKED",
                    actor = TransitionActor(USER, command.actorId.toString()),
                    occurredAt = clock.instant(),
                    metadata =
                        mapOf(
                            ORGANISATION_ID to command.organisationId.toString(),
                            USER_ID to command.userId.toString(),
                        ),
                ),
            )
        }
    }

    private companion object {
        const val USER_DEACTIVATION_ASSIGNMENT_REVOKED_TARGET =
            "finaxis.lifecycle.user.deactivation-assignment-revoked"
        const val USER = "USER"
        const val ORGANISATION_ID = "organisationId"
        const val USER_ID = "userId"
    }
}
