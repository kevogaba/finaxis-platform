package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore

/**
 * The maker-checker rule of a membership approval: the inviter (maker) and the invited user
 * (beneficiary) may never approve, each refusal with its own code. A refusal is audited `DENIED`
 * (HIGH, action `user.approve`, the response code as reason) through
 * [AuditService.recordIndependently] before it is raised, because the caller's transaction then
 * rolls back and would take a row written inside it, as the branch and tenant rules do.
 */
internal class MembershipCheckerRule(
    private val store: UserProvisioningStore,
    private val auditService: AuditService,
) {
    /** Throws, after auditing the refusal, when [command]'s approver made or is the membership. */
    fun requireApproverIsNotMakerOrBeneficiary(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ) {
        val inviter = store.membershipInvitedBy(command.organisationId, command.membershipId)
        val refusal =
            when {
                inviter != null &&
                    command.approvedBy != SystemActor.ID &&
                    command.approvedBy == inviter -> {
                    ForbiddenOperationException(
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_MAKER,
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_MAKER_DETAIL,
                    )
                }

                command.approvedBy == snapshot.userId -> {
                    ForbiddenOperationException(
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_BENEFICIARY,
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_BENEFICIARY_DETAIL,
                    )
                }

                else -> {
                    return
                }
            }
        auditService.recordIndependently(
            AuditCommand(
                actorType = "USER",
                actorId = command.approvedBy.toString(),
                tenantId = command.organisationId.toString(),
                action = "user.approve",
                resourceType = "USER",
                resourceId = snapshot.userId.toString(),
                outcome = AuditOutcome.DENIED,
                severity = AuditSeverity.HIGH,
                reason = refusal.code,
                requestId = command.requestId,
                metadata =
                    mapOf("membershipId" to snapshot.id.toString()) +
                        checkerScopeMetadata(command.scope),
                subjectUserId = snapshot.userId.toString(),
            ),
        )
        throw refusal
    }
}
