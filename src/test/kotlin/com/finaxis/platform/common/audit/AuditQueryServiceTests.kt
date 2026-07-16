package com.finaxis.platform.common.audit

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuditQueryServiceTests {
    @Test
    fun `listByTenant delegates a tenant-scoped filter`() {
        val queries = CapturingAuditEventQueries()
        val service = AuditQueryService(queries)
        val organisationId = UUID.randomUUID()

        service.listByTenant(organisationId, page = 2, size = 10)

        val filter = queries.lastFilter
        assertEquals(organisationId, filter?.organisationId)
        assertEquals(2, filter?.page)
        assertEquals(10, filter?.size)
    }

    @Test
    fun `listByEntity scopes the filter to entity type and id`() {
        val queries = CapturingAuditEventQueries()
        val service = AuditQueryService(queries)
        val organisationId = UUID.randomUUID()
        val entityId = UUID.randomUUID()

        service.listByEntity(organisationId, "ORGANISATION", entityId)

        val filter = queries.lastFilter
        assertEquals("ORGANISATION", filter?.entityType)
        assertEquals(entityId, filter?.entityId)
    }

    @Test
    fun `listByActor scopes the filter to the actor id`() {
        val queries = CapturingAuditEventQueries()
        val service = AuditQueryService(queries)
        val organisationId = UUID.randomUUID()
        val actorId = UUID.randomUUID()

        service.listByActor(organisationId, actorId)

        assertEquals(actorId, queries.lastFilter?.actorId)
    }

    @Test
    fun `listByActionAndDateRange scopes the filter to action and range`() {
        val queries = CapturingAuditEventQueries()
        val service = AuditQueryService(queries)
        val organisationId = UUID.randomUUID()
        val from = Instant.parse("2026-07-01T00:00:00Z")
        val to = Instant.parse("2026-07-15T00:00:00Z")

        service.listByActionAndDateRange(
            organisationId,
            action = "organisation.activate",
            occurredFrom = from,
            occurredTo = to,
        )

        val filter = queries.lastFilter
        assertEquals("organisation.activate", filter?.action)
        assertEquals(from, filter?.occurredFrom)
        assertEquals(to, filter?.occurredTo)
    }

    @Test
    fun `search rejects a negative page`() {
        val service = AuditQueryService(CapturingAuditEventQueries())

        assertFailsWith<IllegalArgumentException> {
            service.search(AuditEventFilter(organisationId = UUID.randomUUID(), page = -1))
        }
    }

    @Test
    fun `search rejects a page size outside the allowed bounds`() {
        val service = AuditQueryService(CapturingAuditEventQueries())
        val organisationId = UUID.randomUUID()

        assertFailsWith<IllegalArgumentException> {
            service.search(AuditEventFilter(organisationId = organisationId, size = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            service.search(AuditEventFilter(organisationId = organisationId, size = 101))
        }
    }
}

private class CapturingAuditEventQueries : AuditEventQueries {
    var lastFilter: AuditEventFilter? = null

    override fun search(filter: AuditEventFilter): AuditEventPage {
        lastFilter = filter
        return AuditEventPage(emptyList(), 0)
    }
}
