package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.persistence.SystemActor
import java.util.UUID

/**
 * The tenant maker-checker rule, shared by every checker decision on a pending tenant (approve,
 * reject and return in [OrganisationProvisioningService]; #221 mirrors the branch rule of
 * ADR 0028, decision 5). The actor may not be the tenant's **maker**, its requester or the
 * submitter of the current submission (403 [LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER]). On
 * approve and reject, the two decisions that settle the request, the actor may also not be anyone
 * who ever amended the draft, so nobody can amend, resubmit and decide another person's draft
 * (403 [LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER]). A checker who only returned the tenant
 * is neither, and may decide a later resubmission; an amender may still return it, as a branch
 * amender may, since returning approves nothing.
 *
 * Callers judge it after the organisation row lock, so it reads the submitter and the amenders of
 * the submission they then decide. A refusal is audited `DENIED` (HIGH, the code as reason)
 * through [AuditService.recordIndependently] before it is raised, because the caller's
 * transaction then rolls back and would take an audit row written inside it, as
 * `requireNotPlatformOrganisation` does for its own refusal.
 */
internal class TenantCheckerRule(
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val auditService: AuditService,
) {
    /**
     * Refuses [actorId] on [organisationId] when it is the maker of [record] or, when
     * [refuseAmender], an amender of the tenant; [attemptedAction] is the audit action of the
     * refused decision. [record] is null only for an organisation without a bootstrap record,
     * which has no maker to compare.
     */
    fun require(
        organisationId: UUID,
        record: InitialAdministratorBootstrapRecord?,
        actorId: UUID,
        attemptedAction: String,
        refuseAmender: Boolean,
    ) {
        val maker =
            record != null && (actorId == record.requestedBy || actorId == record.submittedBy)
        val refusal =
            when {
                maker -> {
                    LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER to
                        LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER_DETAIL
                }

                refuseAmender &&
                    !SystemActor.isSystemActor(actorId) &&
                    lifecycleStore.hasAmendedOrganisation(organisationId, actorId) -> {
                    LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER to
                        LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER_DETAIL
                }

                else -> {
                    return
                }
            }
        auditService.recordIndependently(
            AuditCommand(
                actorType = if (SystemActor.isSystemActor(actorId)) "SYSTEM" else "USER",
                actorId = actorId.toString(),
                tenantId = organisationId.toString(),
                action = attemptedAction,
                resourceType = ORGANISATION_AUDIT_ENTITY_TYPE,
                resourceId = organisationId.toString(),
                outcome = AuditOutcome.DENIED,
                severity = AuditSeverity.HIGH,
                reason = refusal.first,
            ),
        )
        throw ForbiddenOperationException(refusal.first, refusal.second)
    }
}
