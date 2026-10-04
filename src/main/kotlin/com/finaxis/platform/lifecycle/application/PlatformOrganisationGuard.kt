package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.SystemActor
import java.util.UUID

/**
 * Refuses the reserved `PLATFORM` organisation (409), for every use case here that takes
 * a tenant id and reads, locks, mutates or transitions it. One place, called first by each
 * of them (after the caller's permission check where the service makes one), so the
 * protection of the platform organisation is a single, visible line per method.
 *
 * It is a refusal and not a 404, unlike the platform branch and user routes, which answer
 * 404 for the platform organisation: those are routes *under* a tenant and the platform
 * organisation is never a valid `{tenant_id}` for them. Here the platform organisation is
 * the very resource addressed, it exists, and the caller holding the platform permission
 * is entitled to be told why the action is unavailable. Three layers hold the same line:
 * this guard, the organisation transition graph's guard, and `V20`'s CHECK constraint.
 *
 * The refusal is audited `DENIED` before it is raised, as the transition engine audits its
 * own guard refusals (an attempt on the one organisation every platform principal depends on
 * is exactly what an audit trail is for). It goes through
 * [AuditService.recordIndependently], a new transaction, because the caller's use case then
 * throws and rolls its own transaction back; an audit row written inside it would go with it.
 * [attemptedAction] is the audit action code of the attempt; [actorId] is the command's own
 * actor where it has one, else the authenticated request actor, else the system actor.
 */
internal fun AuditService.requireNotPlatformOrganisation(
    organisationId: UUID,
    attemptedAction: String,
    actorId: UUID? = null,
) {
    if (organisationId != PlatformOrganisation.ID) return
    val actor = actorId ?: RequestContexts.actor()?.userId ?: SystemActor.ID
    recordIndependently(
        AuditCommand(
            actorType = if (SystemActor.isSystemActor(actor)) "SYSTEM" else "USER",
            actorId = actor.toString(),
            tenantId = organisationId.toString(),
            action = attemptedAction,
            resourceType = "ORGANISATION",
            resourceId = organisationId.toString(),
            outcome = AuditOutcome.DENIED,
            severity = AuditSeverity.HIGH,
            reason = LifecycleErrorCodes.PLATFORM_ORGANISATION_PROTECTED,
        ),
    )
    throw ConflictException(
        LifecycleErrorCodes.PLATFORM_ORGANISATION_PROTECTED,
        LifecycleErrorCodes.PLATFORM_ORGANISATION_PROTECTED_DETAIL,
    )
}
