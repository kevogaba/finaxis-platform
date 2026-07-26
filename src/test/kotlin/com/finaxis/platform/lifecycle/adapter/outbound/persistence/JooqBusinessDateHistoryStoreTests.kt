package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryEntry
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqBusinessDateHistoryStoreTests(
    private val dsl: DSLContext,
    private val store: JooqBusinessDateHistoryStore,
) {
    @Test
    fun `list returns empty page for extreme page offset`() {
        val organisationId = insertOrganisation()
        store.append(
            BusinessDateHistoryEntry(
                organisationId = organisationId,
                eventType = "ADVANCED",
                fromStatus = "ACTIVE",
                toStatus = "ACTIVE",
                fromBusinessDate = LocalDate.parse("2026-07-24"),
                toBusinessDate = LocalDate.parse("2026-07-25"),
                actorId = uuidV7(),
                reason = "test",
                occurredAt = Instant.parse("2026-07-25T08:00:00Z"),
            ),
        )

        val page = store.list(organisationId, page = Int.MAX_VALUE, size = 100)

        assertEquals(emptyList(), page.items)
        assertEquals(1, page.totalItems)
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "history-$id")
            .set(ORGANISATION.DISPLAY_NAME, "History Org")
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
