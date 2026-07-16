package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/** Durable append-only audit adapter backed by generated PostgreSQL jOOQ metadata. */
@Component
class JooqAuditEventRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) : AuditEventRepository {
    override fun save(event: AuditEvent) {
        val context = RequestContexts.current()
        val organisationId =
            event.tenantId?.let(UUID::fromString)
                ?: context?.tenant?.organisationId
                ?: fallbackOrganisationId(event)
        val actorId = event.actorId?.toUuidOrNull()
        val actorType =
            if (SystemActor.isSystemActor(actorId)) {
                SYSTEM
            } else {
                event.actorType.ifBlank { SYSTEM }
            }
        val userActorId = actorId.takeIf { actorType == USER }

        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, event.id)
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(AUDIT_EVENT.EVENT_TIME, event.occurredAt.atOffset(UTC))
            .set(AUDIT_EVENT.ACTOR_USER_ID, userActorId)
            .set(AUDIT_EVENT.ACTOR_EXTERNAL_SUBJECT, context?.actor?.externalSubject)
            .set(AUDIT_EVENT.ACTOR_TYPE, actorType)
            .set(
                AUDIT_EVENT.BRANCH_ID,
                (event.branchId?.toUuidOrNull()) ?: context?.branch?.branchId,
            ).set(AUDIT_EVENT.EVENT_TYPE, event.resourceType)
            .set(AUDIT_EVENT.ENTITY_TYPE, event.resourceType)
            .set(AUDIT_EVENT.ENTITY_ID, event.resourceId?.toUuidOrNull())
            .set(AUDIT_EVENT.ACTION, event.action)
            .set(AUDIT_EVENT.OUTCOME, event.outcome.name)
            .set(AUDIT_EVENT.REASON, event.reason)
            .set(AUDIT_EVENT.SEVERITY, event.severity.name)
            .set(AUDIT_EVENT.IP_ADDRESS, event.sourceIp)
            .set(AUDIT_EVENT.USER_AGENT, event.userAgent ?: context?.userAgent)
            .set(AUDIT_EVENT.CORRELATION_ID, context?.correlation?.correlationId)
            .set(AUDIT_EVENT.REQUEST_ID, event.requestId ?: context?.correlation?.requestId)
            .set(AUDIT_EVENT.BEFORE_JSONB, event.before?.let(::toJsonb))
            .set(AUDIT_EVENT.AFTER_JSONB, event.after?.let(::toJsonb))
            .set(AUDIT_EVENT.METADATA_JSONB, toJsonb(event.metadata))
            .execute()
    }

    /**
     * Audit events without a tenant would otherwise be silently dropped by the `NOT NULL`
     * `organisation_id` foreign key. Falling back to the reserved platform organisation keeps
     * every audit call durable and discoverable instead of disappearing without a trace.
     */
    private fun fallbackOrganisationId(event: AuditEvent): UUID {
        logger.warn(
            "audit_event has no tenant id; recording under the platform organisation " +
                "action={} resourceType={} resourceId={}",
            event.action,
            event.resourceType,
            event.resourceId,
        )
        return PlatformOrganisation.ID
    }

    private fun toJsonb(value: Map<String, Any?>): JSONB =
        JSONB.jsonb(objectMapper.writeValueAsString(value))

    private fun String.toUuidOrNull(): UUID? = runCatching(UUID::fromString).getOrNull()

    private companion object {
        const val SYSTEM = "SYSTEM"
        const val USER = "USER"
        val UTC: java.time.ZoneOffset = java.time.ZoneOffset.UTC
        val logger: org.slf4j.Logger = LoggerFactory.getLogger(JooqAuditEventRepository::class.java)
    }
}
