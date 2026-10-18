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
    private val policy: MakerCheckerPolicy,
) {
    /**
     * Throws, after auditing the refusal, when [command]'s approver made or is the membership.
     * Returns true when the approver did, but the tenant has switched this segregation off for
     * its own approvers, so the caller can audit the approval as a self-approval; false for an
     * ordinary approval. The platform checker is always held to the rule.
     */
    fun requireApproverIsNotMakerOrBeneficiary(
        command: ApproveUserCommand,
        snapshot: MembershipProvisioningSnapshot,
    ): Boolean {
        val inviter = store.membershipInvitedBy(command.organisationId, command.membershipId)
        val isMaker =
            inviter != null &&
                command.approvedBy != SystemActor.ID &&
                command.approvedBy == inviter
        val isBeneficiary = command.approvedBy == snapshot.userId
        if (!isMaker && !isBeneficiary) return false
        // Only the tenant's own approvers can be relaxed by the tenant: the platform checker is a
        // platform safeguard (ADR 0028) that a tenant setting never loosens.
        if (command.scope == ActingScope.TENANT &&
            !policy.requiredForUserInvites(command.organisationId)
        ) {
            return true
        }
        val refusal =
            when {
                isMaker -> {
                    ForbiddenOperationException(
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_MAKER,
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_MAKER_DETAIL,
                    )
                }

                else -> {
                    ForbiddenOperationException(
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_BENEFICIARY,
                        LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_BENEFICIARY_DETAIL,
                    )
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
