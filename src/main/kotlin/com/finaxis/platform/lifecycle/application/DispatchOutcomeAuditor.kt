package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningDispatchStore
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The failure detail a failed dispatch keeps in `identity_dispatch_log.last_error`: the closed
 * [InitialAdministratorBootstrapFailureCode] for this failure's type, a colon, and the exception
 * class name, for example `INVALID_STATE:IllegalStateException`. The message is never used, since
 * it can carry an email address or identity-provider output. Historic rows keep what they stored.
 */
fun Throwable.toDispatchFailureDetail(): String =
    "${InitialAdministratorBootstrapFailureCode.from(this).name}:${javaClass.simpleName}"

/**
 * Wraps a dispatch-state update and its audit record in one transaction. Without this, a crash or
 * failure between the two writes could leave a dispatch marked succeeded/failed with no matching
 * audit row - and a JobRunr retry's idempotent `SUCCEEDED`/`FAILED` guard would then skip the
 * dispatch permanently, silently losing the audit trail forever.
 */
@Component
class DispatchOutcomeAuditor(
    private val store: UserProvisioningDispatchStore,
    private val auditService: AuditService,
) {
    /** Marks the dispatch succeeded and records its audit event atomically. */
    @Transactional
    fun recordSuccess(
        dispatchKey: String,
        dispatchRef: String,
        externalSystemRef: String,
        actorId: UUID,
        tenantId: UUID,
        action: String,
        resourceId: String,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        store.markDispatchSucceeded(dispatchKey, dispatchRef)
        auditService.recordExternalDispatch(
            actorId = actorId,
            tenantId = tenantId,
            action = action,
            outcome = AuditOutcome.SUCCESS,
            externalSystemRef = externalSystemRef,
            resourceType = USER_RESOURCE,
            resourceId = resourceId,
            metadata = metadata,
        )
    }

    /**
     * Marks the dispatch failed and records its audit event atomically. [detail] is the failure
     * detail kept in the dispatch-tracking table (`identity_dispatch_log.last_error`): the closed
     * failure code and the exception class name, from [toDispatchFailureDetail], never the
     * exception message. [reason] is the bounded, content-free value persisted to the audit trail,
     * which `AuditEventController` returns to audit API callers verbatim.
     */
    @Transactional
    fun recordFailure(
        dispatchKey: String,
        externalSystemRef: String,
        actorId: UUID,
        tenantId: UUID,
        action: String,
        resourceId: String,
        reason: String,
        detail: String = reason,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        store.markDispatchFailed(dispatchKey, detail)
        auditService.recordExternalDispatch(
            actorId = actorId,
            tenantId = tenantId,
            action = action,
            outcome = AuditOutcome.FAILURE,
            externalSystemRef = externalSystemRef,
            resourceType = USER_RESOURCE,
            resourceId = resourceId,
            reason = reason,
            metadata = metadata,
        )
    }

    private companion object {
        const val USER_RESOURCE = "USER"
    }
}
