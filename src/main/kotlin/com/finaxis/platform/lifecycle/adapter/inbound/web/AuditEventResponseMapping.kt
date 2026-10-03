package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditEventDetail
import com.finaxis.platform.common.audit.AuditEventSummary
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventSummaryResponse

/** Maps an audit summary projection to its public response; shared by tenant and platform reads. */
internal fun AuditEventSummary.toResponse() =
    AuditEventSummaryResponse(
        id = id,
        occurredAt = occurredAt,
        actorType = actorType,
        actorId = actorId,
        branchId = branchId,
        action = action,
        resourceType = resourceType,
        resourceId = resourceId,
        outcome = outcome.name,
        severity = severity.name,
        reason = reason,
    )

/** Maps an audit detail projection to its public response. */
internal fun AuditEventDetail.toResponse() =
    AuditEventDetailResponse(
        id = id,
        organisationId = organisationId,
        occurredAt = occurredAt,
        actorUserId = actorUserId,
        actorExternalSubject = actorExternalSubject,
        actorType = actorType,
        branchId = branchId,
        eventType = eventType,
        entityType = entityType,
        entityId = entityId,
        action = action,
        outcome = outcome.name,
        severity = severity.name,
        ipAddress = ipAddress,
        userAgent = userAgent,
        correlationId = correlationId,
        requestId = requestId,
        beforeJson = beforeJson,
        afterJson = afterJson,
        metadataJson = metadataJson,
        reason = reason,
    )
