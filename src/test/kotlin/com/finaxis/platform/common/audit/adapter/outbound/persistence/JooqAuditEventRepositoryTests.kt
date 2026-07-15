package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqAuditEventRepositoryTests(
    private val dsl: DSLContext,
    private val repository: JooqAuditEventRepository,
) {
    @Test
    fun `save persists the audit event source IP address`() {
        val organisationId = insertOrganisation()
        val eventId = uuidV7()

        repository.save(baseEvent(eventId, organisationId, sourceIp = "203.0.113.7"))

        val persistedIp =
            dsl
                .select(AUDIT_EVENT.IP_ADDRESS)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ID.eq(eventId))
                .fetchOne(AUDIT_EVENT.IP_ADDRESS)

        assertEquals("203.0.113.7", persistedIp)
    }

    @Test
    fun `save stores the common system actor without a user foreign key`() {
        val organisationId = insertOrganisation()
        val eventId = uuidV7()

        repository.save(
            baseEvent(
                eventId,
                organisationId,
                actorType = "USER",
                actorId = SystemActor.ID.toString(),
            ),
        )

        val audit =
            requireNotNull(
                dsl
                    .select(AUDIT_EVENT.ACTOR_TYPE, AUDIT_EVENT.ACTOR_USER_ID)
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ID.eq(eventId))
                    .fetchOne(),
            )

        assertEquals("SYSTEM", audit.get(AUDIT_EVENT.ACTOR_TYPE))
        assertNull(audit.get(AUDIT_EVENT.ACTOR_USER_ID))
    }

    @Test
    fun `save persists severity, user agent, and before-after summaries`() {
        val organisationId = insertOrganisation()
        val eventId = uuidV7()

        repository.save(
            baseEvent(eventId, organisationId).copy(
                severity = AuditSeverity.HIGH,
                userAgent = "curl/8.0",
                before = mapOf("status" to "SUSPENDED"),
                after = mapOf("status" to "ACTIVE"),
            ),
        )

        val audit =
            requireNotNull(
                dsl
                    .select(
                        AUDIT_EVENT.SEVERITY,
                        AUDIT_EVENT.USER_AGENT,
                        AUDIT_EVENT.BEFORE_JSONB,
                        AUDIT_EVENT.AFTER_JSONB,
                    ).from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ID.eq(eventId))
                    .fetchOne(),
            )

        assertEquals("HIGH", audit.get(AUDIT_EVENT.SEVERITY))
        assertEquals("curl/8.0", audit.get(AUDIT_EVENT.USER_AGENT))
        // Postgres's jsonb output function normalizes to a space after each colon.
        assertEquals("""{"status": "SUSPENDED"}""", audit.get(AUDIT_EVENT.BEFORE_JSONB)?.data())
        assertEquals("""{"status": "ACTIVE"}""", audit.get(AUDIT_EVENT.AFTER_JSONB)?.data())
    }

    @Test
    fun `save falls back to the platform organisation when tenant id is missing`() {
        val eventId = uuidV7()

        repository.save(baseEvent(eventId, organisationId = null))

        val persistedOrganisationId =
            dsl
                .select(AUDIT_EVENT.ORGANISATION_ID)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ID.eq(eventId))
                .fetchOne(AUDIT_EVENT.ORGANISATION_ID)

        assertEquals(PlatformOrganisation.ID, persistedOrganisationId)
    }

    @Test
    fun `save prefers the request context tenant over the platform organisation fallback`() {
        val organisationId = insertOrganisation()
        val eventId = uuidV7()

        RequestContexts.with(RequestContext(tenant = TenantContext(organisationId))) {
            repository.save(baseEvent(eventId, organisationId = null))
        }

        val persistedOrganisationId =
            dsl
                .select(AUDIT_EVENT.ORGANISATION_ID)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ID.eq(eventId))
                .fetchOne(AUDIT_EVENT.ORGANISATION_ID)

        assertEquals(organisationId, persistedOrganisationId)
    }

    private fun baseEvent(
        eventId: UUID,
        organisationId: UUID?,
        actorType: String = "SYSTEM",
        actorId: String? = null,
        sourceIp: String? = null,
    ): AuditEvent =
        AuditEvent(
            id = eventId,
            actorType = actorType,
            actorId = actorId,
            tenantId = organisationId?.toString(),
            branchId = null,
            action = "organisation.activate",
            resourceType = "ORGANISATION",
            resourceId = organisationId?.toString(),
            outcome = AuditOutcome.SUCCESS,
            severity = AuditSeverity.INFO,
            reason = null,
            requestId = null,
            sourceIp = sourceIp,
            userAgent = null,
            before = null,
            after = null,
            metadata = emptyMap(),
            occurredAt = Instant.parse("2026-07-13T10:00:00Z"),
        )

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }
}
