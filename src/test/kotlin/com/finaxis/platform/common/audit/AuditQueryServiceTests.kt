package com.finaxis.platform.common.audit

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditQueryServiceTests {
    private val queries = CapturingAuditEventQueries()
    private val permissionGuard = FakeAuditPermissionGuard()
    private val service = AuditQueryService(queries, permissionGuard)

    @Test
    fun `listByTenant delegates a tenant-scoped filter and verifies permission`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()

        service.listByTenant(organisationId, actorId, page = 2, size = 10)

        val filter = queries.lastFilter
        assertEquals(organisationId, filter?.organisationId)
        assertEquals(2, filter?.page)
        assertEquals(10, filter?.size)
        assertEquals(actorId, permissionGuard.lastActorId)
        assertEquals(organisationId, permissionGuard.lastOrganisationId)
        assertEquals("audit.view", permissionGuard.lastPermissionCode)
    }

    @Test
    fun `listByEntity scopes the filter and verifies permission`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val entityId = UUID.randomUUID()

        service.listByEntity(organisationId, actorId, "ORGANISATION", entityId)

        val filter = queries.lastFilter
        assertEquals("ORGANISATION", filter?.entityType)
        assertEquals(entityId, filter?.entityId)
        assertEquals(actorId, permissionGuard.lastActorId)
        assertEquals(organisationId, permissionGuard.lastOrganisationId)
    }

    @Test
    fun `listByActor scopes the filter to target actor and verifies permission`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val targetActorId = UUID.randomUUID()

        service.listByActor(organisationId, actorId, targetActorId)

        assertEquals(targetActorId, queries.lastFilter?.actorId)
        assertEquals(actorId, permissionGuard.lastActorId)
    }

    @Test
    fun `listByActionAndDateRange scopes the filter and verifies permission`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val from = Instant.parse("2026-07-01T00:00:00Z")
        val to = Instant.parse("2026-07-15T00:00:00Z")

        service.listByActionAndDateRange(
            organisationId,
            actorId,
            action = "organisation.activate",
            occurredFrom = from,
            occurredTo = to,
        )

        val filter = queries.lastFilter
        assertEquals("organisation.activate", filter?.action)
        assertEquals(from, filter?.occurredFrom)
        assertEquals(to, filter?.occurredTo)
        assertEquals(actorId, permissionGuard.lastActorId)
    }

    @Test
    fun `search rejects a negative page`() {
        val actorId = UUID.randomUUID()
        assertFailsWith<InvalidPageRequestException> {
            service.search(AuditEventFilter(organisationId = UUID.randomUUID(), page = -1), actorId)
        }
    }

    @Test
    fun `search rejects a page size outside the allowed bounds`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()

        assertFailsWith<InvalidPageRequestException> {
            service.search(AuditEventFilter(organisationId = organisationId, size = 0), actorId)
        }
        assertFailsWith<InvalidPageRequestException> {
            service.search(AuditEventFilter(organisationId = organisationId, size = 101), actorId)
        }
    }

    @Test
    fun `get throws ForbiddenOperationException when permission is denied`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        permissionGuard.shouldDeny = true

        assertFailsWith<ForbiddenOperationException> {
            service.get(eventId, organisationId, actorId)
        }
    }

    @Test
    fun `get throws ResourceNotFoundException when audit event not found`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        queries.stubDetail = null

        assertFailsWith<ResourceNotFoundException> {
            service.get(eventId, organisationId, actorId)
        }
    }

    @Test
    fun `get returns detailed audit event when found`() {
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val detail =
            AuditEventDetail(
                id = eventId,
                organisationId = organisationId,
                occurredAt = Instant.now(),
                actorUserId = actorId,
                actorExternalSubject = "ext-subj",
                actorType = "USER",
                branchId = null,
                eventType = "USER_LOGIN",
                entityType = "USER",
                entityId = actorId,
                action = "user.login",
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.INFO,
                ipAddress = "127.0.0.1",
                userAgent = "Browser",
                correlationId = "corr-1",
                requestId = "req-1",
                beforeJson = null,
                afterJson = null,
                metadataJson = "{}",
                reason = null,
            )
        queries.stubDetail = detail

        val result = service.get(eventId, organisationId, actorId)
        assertEquals(detail, result)
        assertEquals(actorId, permissionGuard.lastActorId)
        assertEquals(organisationId, permissionGuard.lastOrganisationId)
        assertEquals("audit.view", permissionGuard.lastPermissionCode)
    }
}

private class CapturingAuditEventQueries : AuditEventQueries {
    var lastFilter: AuditEventFilter? = null
    var stubDetail: AuditEventDetail? = null

    override fun search(filter: AuditEventFilter): AuditEventPage {
        lastFilter = filter
        return AuditEventPage(emptyList(), 0)
    }

    override fun findById(
        id: UUID,
        organisationId: UUID,
    ): AuditEventDetail? = stubDetail
}

private class FakeAuditPermissionGuard : AuditPermissionGuard {
    var lastActorId: UUID? = null
    var lastOrganisationId: UUID? = null
    var lastPermissionCode: String? = null
    var shouldDeny: Boolean = false

    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        lastActorId = actorId
        lastOrganisationId = organisationId
        lastPermissionCode = permissionCode
        if (shouldDeny) {
            throw ForbiddenOperationException()
        }
    }
}
