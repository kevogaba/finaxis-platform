package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.id.uuidV7
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

        repository.save(
            AuditEvent(
                id = eventId,
                actorType = "SYSTEM",
                actorId = null,
                tenantId = organisationId.toString(),
                action = "organisation.activate",
                resourceType = "ORGANISATION",
                resourceId = organisationId.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = null,
                requestId = null,
                sourceIp = "203.0.113.7",
                metadata = emptyMap(),
                occurredAt = Instant.parse("2026-07-13T10:00:00Z"),
            ),
        )

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
            AuditEvent(
                id = eventId,
                actorType = "USER",
                actorId = SystemActor.ID.toString(),
                tenantId = organisationId.toString(),
                action = "organisation.create_draft",
                resourceType = "ORGANISATION",
                resourceId = organisationId.toString(),
                outcome = AuditOutcome.SUCCESS,
                reason = null,
                requestId = null,
                sourceIp = null,
                metadata = emptyMap(),
                occurredAt = Instant.parse("2026-07-13T10:00:00Z"),
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
