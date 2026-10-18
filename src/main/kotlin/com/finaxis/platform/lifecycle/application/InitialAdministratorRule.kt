package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity

/**
 * The bootstrap approves its administrator's membership in the approver's name, and a user may
 * never approve their own membership, so a tenant's approver may not be the account its draft
 * names as initial administrator: the refusal comes now rather than failing later. It is audited
 * like the tenant's other maker-checker refusals, in its own transaction so the rollback it causes
 * does not take the row.
 */
internal class InitialAdministratorRule(
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val auditService: AuditService,
) {
    /** Throws, after auditing the refusal, when the approver is the draft's administrator. */
    fun requireApproverIsNotAdministrator(command: ApproveOrganisationProvisioningCommand) {
        if (adminBootstrapStore.existingAdministratorUserId(command.organisationId) !=
            command.actorId
        ) {
            return
        }
        auditService.recordIndependently(
            AuditCommand(
                actorType = "USER",
                actorId = command.actorId.toString(),
                tenantId = command.organisationId.toString(),
                action = "organisation.approve",
                resourceType = ORGANISATION_AUDIT_ENTITY_TYPE,
                resourceId = command.organisationId.toString(),
                outcome = AuditOutcome.DENIED,
                severity = AuditSeverity.HIGH,
                reason = LifecycleErrorCodes.APPROVER_IS_INITIAL_ADMINISTRATOR,
            ),
        )
        throw ForbiddenOperationException(
            LifecycleErrorCodes.APPROVER_IS_INITIAL_ADMINISTRATOR,
            LifecycleErrorCodes.APPROVER_IS_INITIAL_ADMINISTRATOR_DETAIL,
        )
    }
}
