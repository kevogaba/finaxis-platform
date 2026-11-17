package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import java.util.UUID

/**
 * One attempt the [SelfAccessGuard] checks: who acts, in which organisation, under which audit
 * action (the transition's own, ADR 0031), and the request it belongs to.
 */
internal data class SelfAccessAttempt(
    val actorId: UUID,
    val organisationId: UUID,
    val action: String,
    val requestId: String?,
)

/**
 * The self-access guard (#341, ADR 0043): nobody suspends or revokes their own membership, or
 * suspends or deactivates their own account. A caller runs it after its permission check (and, for
 * a membership, the lookup) and before any write. A refusal is audited `DENIED` (HIGH, the
 * attempt's action, the response code as reason) through [AuditService.recordIndependently]
 * before it is raised, because the caller's transaction then rolls back and would take a row
 * written inside it, as the maker-checker rules do.
 */
internal class SelfAccessGuard(
    private val auditService: AuditService,
) {
    /** Throws, after auditing the refusal, when the attempt's actor is [memberUserId]. */
    fun requireNotOwnMembership(
        attempt: SelfAccessAttempt,
        membershipId: UUID,
        memberUserId: UUID,
    ) {
        if (attempt.actorId != memberUserId) return
        refuse(
            attempt,
            MEMBERSHIP,
            membershipId,
            ForbiddenOperationException(
                LifecycleErrorCodes.SELF_MEMBERSHIP_CHANGE,
                LifecycleErrorCodes.SELF_MEMBERSHIP_CHANGE_DETAIL,
            ),
        )
    }

    /** Throws, after auditing the refusal, when the attempt's actor is the account [userId]. */
    fun requireNotOwnAccount(
        attempt: SelfAccessAttempt,
        userId: UUID,
    ) {
        if (attempt.actorId != userId) return
        refuse(
            attempt,
            USER_ACCOUNT,
            userId,
            ForbiddenOperationException(
                LifecycleErrorCodes.SELF_ACCOUNT_CHANGE,
                LifecycleErrorCodes.SELF_ACCOUNT_CHANGE_DETAIL,
            ),
        )
    }

    private fun refuse(
        attempt: SelfAccessAttempt,
        resourceType: String,
        resourceId: UUID,
        refusal: ForbiddenOperationException,
    ): Nothing {
        auditService.recordIndependently(
            AuditCommand(
                actorType = "USER",
                actorId = attempt.actorId.toString(),
                tenantId = attempt.organisationId.toString(),
                action = attempt.action,
                resourceType = resourceType,
                resourceId = resourceId.toString(),
                outcome = AuditOutcome.DENIED,
                subjectUserId = attempt.actorId.toString(),
                severity = AuditSeverity.HIGH,
                reason = refusal.code,
                requestId = attempt.requestId,
            ),
        )
        throw refusal
    }

    private companion object {
        const val MEMBERSHIP = "MEMBERSHIP"
        const val USER_ACCOUNT = "USER_ACCOUNT"
    }
}
