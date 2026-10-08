package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

private const val ACTOR_ID_DOC = "Deprecated alias of actor_user_id, always the same value."
private const val ACTOR_USER_ID_DOC = "Acting user; null when a system process acted."
private const val EVENT_TYPE_DOC =
    "Domain event name of the row: its action (for example organisation.activate). Rows " +
        "written before event_type was derived from the action carry the resource type instead."
private const val RESOURCE_TYPE_DOC = "Deprecated alias of entity_type, always the same value."
private const val RESOURCE_ID_DOC =
    "Deprecated alias of entity_id as a string, always the same value."
private const val ENTITY_ID_DOC =
    "Id of the resource acted on. UUIDs only: a resource id that is not a UUID (for example a " +
        "setting key) is not stored, so it reads as null here and in resource_id."
private const val WITHHELD_DOC =
    "Withheld (null) in the items of the platform audit pages by owner decision; populated on " +
        "the tenant routes and the platform detail route."

/**
 * One audit event in a paginated search. Carries exactly the fields of
 * [AuditEventDetailResponse] under the same names (#187): `resource_type`, `resource_id` and
 * `actor_id` are kept as deprecated aliases of `entity_type`, `entity_id` and `actor_user_id`.
 */
data class AuditEventSummaryResponse(
    val id: UUID,
    val organisationId: UUID,
    val occurredAt: Instant,
    val actorType: String,
    @field:Schema(description = ACTOR_ID_DOC, deprecated = true)
    val actorId: UUID?,
    @field:Schema(description = ACTOR_USER_ID_DOC)
    val actorUserId: UUID?,
    @field:Schema(description = WITHHELD_DOC)
    val actorExternalSubject: String?,
    val branchId: UUID?,
    @field:Schema(description = EVENT_TYPE_DOC)
    val eventType: String,
    val action: String,
    @field:Schema(description = RESOURCE_TYPE_DOC, deprecated = true)
    val resourceType: String,
    @field:Schema(description = RESOURCE_ID_DOC, deprecated = true)
    val resourceId: String?,
    val entityType: String,
    @field:Schema(description = ENTITY_ID_DOC)
    val entityId: UUID?,
    val outcome: String,
    val severity: String,
    val reason: String?,
    @field:Schema(description = WITHHELD_DOC)
    val ipAddress: String?,
    @field:Schema(description = WITHHELD_DOC)
    val userAgent: String?,
    val correlationId: String?,
    val requestId: String?,
    @field:Schema(description = WITHHELD_DOC)
    val beforeJson: String?,
    @field:Schema(description = WITHHELD_DOC)
    val afterJson: String?,
    @field:Schema(description = WITHHELD_DOC)
    val metadataJson: String?,
)

/**
 * One audit event read by id. Carries exactly the fields of [AuditEventSummaryResponse] under
 * the same names (#187), including the deprecated aliases `resource_type`, `resource_id` and
 * `actor_id`.
 */
data class AuditEventDetailResponse(
    val id: UUID,
    val organisationId: UUID,
    val occurredAt: Instant,
    val actorType: String,
    @field:Schema(description = ACTOR_ID_DOC, deprecated = true)
    val actorId: UUID?,
    @field:Schema(description = ACTOR_USER_ID_DOC)
    val actorUserId: UUID?,
    val actorExternalSubject: String?,
    val branchId: UUID?,
    @field:Schema(description = EVENT_TYPE_DOC)
    val eventType: String,
    val action: String,
    @field:Schema(description = RESOURCE_TYPE_DOC, deprecated = true)
    val resourceType: String,
    @field:Schema(description = RESOURCE_ID_DOC, deprecated = true)
    val resourceId: String?,
    val entityType: String,
    @field:Schema(description = ENTITY_ID_DOC)
    val entityId: UUID?,
    val outcome: String,
    val severity: String,
    val reason: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val correlationId: String?,
    val requestId: String?,
    val beforeJson: String?,
    val afterJson: String?,
    val metadataJson: String,
)
