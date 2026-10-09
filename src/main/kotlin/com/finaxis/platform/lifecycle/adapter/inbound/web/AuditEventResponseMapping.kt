package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditEventDetail
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventDetailResponse
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AuditEventSummaryResponse

/**
 * Maps an audit event to its page item; shared by tenant and platform reads. Same fields and
 * values as [toResponse] (#187): the deprecated aliases repeat the canonical columns.
 */
internal fun AuditEventDetail.toSummaryResponse() =
    AuditEventSummaryResponse(
        id = id,
        organisationId = organisationId,
        occurredAt = occurredAt,
        actorType = actorType,
        actorId = actorUserId,
        actorUserId = actorUserId,
        actorExternalSubject = actorExternalSubject,
        branchId = branchId,
        eventType = eventType,
        action = action,
        resourceType = entityType,
        resourceId = entityId?.toString(),
        entityType = entityType,
        entityId = entityId,
        outcome = outcome.name,
        severity = severity.name,
        reason = reason,
        ipAddress = ipAddress,
        userAgent = userAgent,
        correlationId = correlationId,
        requestId = requestId,
        beforeJson = beforeJson,
        afterJson = afterJson,
        metadataJson = metadataJson,
    )

/**
 * Maps an audit event to an item of a platform audit page (a tenant's log or the platform log).
 * The same fields as [toSummaryResponse], but by owner decision (#187 review) the state and
 * request-context fields are withheld: `before_json`, `after_json`, `metadata_json`,
 * `user_agent`, `ip_address` and `actor_external_subject` stay in the shape with `null` values,
 * so platform operators cannot bulk-read them across tenants. The tenant routes and the platform
 * detail route (the platform log only) are unchanged.
 */
internal fun AuditEventDetail.toPlatformSummaryResponse() =
    toSummaryResponse().copy(
        actorExternalSubject = null,
        ipAddress = null,
        userAgent = null,
        beforeJson = null,
        afterJson = null,
        metadataJson = null,
    )

/** Maps an audit event to its detail response; same fields and values as [toSummaryResponse]. */
internal fun AuditEventDetail.toResponse() =
    AuditEventDetailResponse(
        id = id,
        organisationId = organisationId,
        occurredAt = occurredAt,
        actorType = actorType,
        actorId = actorUserId,
        actorUserId = actorUserId,
        actorExternalSubject = actorExternalSubject,
        branchId = branchId,
        eventType = eventType,
        action = action,
        resourceType = entityType,
        resourceId = entityId?.toString(),
        entityType = entityType,
        entityId = entityId,
        outcome = outcome.name,
        severity = severity.name,
        reason = reason,
        ipAddress = ipAddress,
        userAgent = userAgent,
        correlationId = correlationId,
        requestId = requestId,
        beforeJson = beforeJson,
        afterJson = afterJson,
        metadataJson = metadataJson,
    )
