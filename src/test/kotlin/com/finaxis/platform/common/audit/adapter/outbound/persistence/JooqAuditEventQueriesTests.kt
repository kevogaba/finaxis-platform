package com.finaxis.platform.common.audit.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditEventFilter
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqAuditEventQueriesTests(
    private val dsl: DSLContext,
    private val queries: JooqAuditEventQueries,
) {
    @Test
    fun `search scopes results to the requested tenant`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        insertAuditEvent(organisationId, actorId = null, action = "organisation.activate")
        insertAuditEvent(otherOrganisationId, actorId = null, action = "organisation.activate")

        val page = queries.search(AuditEventFilter(organisationId = organisationId))

        assertEquals(1, page.items.size)
        assertEquals(1, page.totalItems)
    }

    @Test
    fun `search filters by entity type and id`() {
        val organisationId = insertOrganisation()
        val entityId = uuidV7()
        insertAuditEvent(
            organisationId,
            actorId = null,
            action = "branch.activate",
            entityType = "BRANCH",
            entityId = entityId,
        )
        insertAuditEvent(
            organisationId,
            actorId = null,
            action = "organisation.activate",
            entityType = "ORGANISATION",
            entityId = uuidV7(),
        )

        val page =
            queries.search(
                AuditEventFilter(
                    organisationId = organisationId,
                    entityType = "BRANCH",
                    entityId = entityId,
                ),
            )

        assertEquals(1, page.items.size)
        assertEquals("branch.activate", page.items.single().action)
    }

    @Test
    fun `search filters by actor id`() {
        val organisationId = insertOrganisation()
        val actorId = insertUserAccount()
        insertAuditEvent(organisationId, actorId = actorId, action = "user.invite")
        insertAuditEvent(organisationId, actorId = insertUserAccount(), action = "user.invite")

        val page =
            queries.search(AuditEventFilter(organisationId = organisationId, actorId = actorId))

        assertEquals(1, page.items.size)
        assertEquals(actorId, page.items.single().actorId)
    }

    @Test
    fun `search orders results by event time descending and paginates`() {
        val organisationId = insertOrganisation()
        val base = Instant.parse("2026-07-01T00:00:00Z")
        repeat(3) { index ->
            insertAuditEvent(
                organisationId,
                actorId = null,
                action = "organisation.activate",
                eventTime = base.plusSeconds(index.toLong()),
            )
        }

        val firstPage =
            queries.search(AuditEventFilter(organisationId = organisationId, page = 0, size = 2))
        val secondPage =
            queries.search(AuditEventFilter(organisationId = organisationId, page = 1, size = 2))

        assertEquals(2, firstPage.items.size)
        assertEquals(3, firstPage.totalItems)
        assertEquals(1, secondPage.items.size)
        assertEquals(base.plusSeconds(2), firstPage.items.first().occurredAt)
    }

    @Test
    fun `findById retrieves a detailed audit event within the organisation scope`() {
        val organisationId = insertOrganisation()
        val eventId = uuidV7()
        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, eventId)
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(
                AUDIT_EVENT.EVENT_TIME,
                Instant.parse("2026-07-13T10:00:00Z").atOffset(ZoneOffset.UTC),
            ).set(AUDIT_EVENT.ACTOR_TYPE, "SYSTEM")
            .set(AUDIT_EVENT.EVENT_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ENTITY_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ACTION, "organisation.activate")
            .set(AUDIT_EVENT.OUTCOME, "SUCCESS")
            .set(AUDIT_EVENT.SEVERITY, "INFO")
            .set(AUDIT_EVENT.METADATA_JSONB, org.jooq.JSONB.jsonb("{}"))
            .execute()

        val detail = queries.findById(eventId, organisationId)
        assertNotNull(detail)
        assertEquals(eventId, detail.id)
        assertEquals(organisationId, detail.organisationId)
        assertEquals("organisation.activate", detail.action)
    }

    @Test
    fun `findById returns null for cross-tenant request`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val eventId = uuidV7()
        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, eventId)
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(
                AUDIT_EVENT.EVENT_TIME,
                Instant.parse("2026-07-13T10:00:00Z").atOffset(ZoneOffset.UTC),
            ).set(AUDIT_EVENT.ACTOR_TYPE, "SYSTEM")
            .set(AUDIT_EVENT.EVENT_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ENTITY_TYPE, "ORGANISATION")
            .set(AUDIT_EVENT.ACTION, "organisation.activate")
            .set(AUDIT_EVENT.OUTCOME, "SUCCESS")
            .set(AUDIT_EVENT.SEVERITY, "INFO")
            .execute()

        val detail = queries.findById(eventId, otherOrganisationId)
        kotlin.test.assertNull(detail)
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

    private fun insertUserAccount(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertAuditEvent(
        organisationId: UUID,
        actorId: UUID?,
        action: String,
        entityType: String = "ORGANISATION",
        entityId: UUID? = null,
        eventTime: Instant = Instant.parse("2026-07-13T10:00:00Z"),
    ) {
        dsl
            .insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.ID, uuidV7())
            .set(AUDIT_EVENT.ORGANISATION_ID, organisationId)
            .set(AUDIT_EVENT.EVENT_TIME, eventTime.atOffset(ZoneOffset.UTC))
            .set(AUDIT_EVENT.ACTOR_USER_ID, actorId)
            .set(AUDIT_EVENT.ACTOR_TYPE, if (actorId == null) "SYSTEM" else "USER")
            .set(AUDIT_EVENT.EVENT_TYPE, entityType)
            .set(AUDIT_EVENT.ENTITY_TYPE, entityType)
            .set(AUDIT_EVENT.ENTITY_ID, entityId)
            .set(AUDIT_EVENT.ACTION, action)
            .set(AUDIT_EVENT.OUTCOME, "SUCCESS")
            .set(AUDIT_EVENT.SEVERITY, "INFO")
            .execute()
    }
}
