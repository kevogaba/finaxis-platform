package com.finaxis.platform.common.audit

import com.finaxis.platform.common.id.uuidV7
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * Application service for recording audit events without coupling callers to storage details.
 */
@Service
class AuditService(
    private val repository: AuditEventRepository,
    private val clock: Clock,
) {
    /**
     * Records the supplied audit command as an immutable audit event.
     */
    fun record(command: AuditCommand): AuditEvent {
        val event =
            AuditEvent(
                id = uuidV7(),
                actorType = command.actorType,
                actorId = command.actorId,
                tenantId = command.tenantId,
                action = command.action,
                resourceType = command.resourceType,
                resourceId = command.resourceId,
                outcome = command.outcome,
                reason = command.reason,
                requestId = command.requestId,
                sourceIp = command.sourceIp,
                metadata = command.metadata,
                occurredAt = clock.instant(),
            )
        repository.save(event)
        return event
    }
}
