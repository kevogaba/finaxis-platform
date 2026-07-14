package com.finaxis.platform.common.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JdbcAuditingIntegrationTests(
    private val jdbcAggregateTemplate: JdbcAggregateTemplate,
) {
    @Test
    fun `jdbc auditing writes timestamps and system actor without request context`() {
        val saved = jdbcAggregateTemplate.insert(organisation("system-audit"))

        assertNotNull(saved.createdAt)
        assertNotNull(saved.updatedAt)
        assertEquals(SystemActor.ID, saved.createdBy)
        assertEquals(SystemActor.ID, saved.updatedBy)
        assertEquals(0, saved.rowVersion)
    }

    @Test
    fun `jdbc auditing writes authenticated actor metadata`() {
        val actorId = uuidV7()

        val saved =
            RequestContexts.withActor(
                ActorContext(actorId, "subject", "auditor", "audit@example.test"),
            ) {
                jdbcAggregateTemplate.insert(organisation("actor-audit"))
            }

        assertEquals(actorId, saved.createdBy)
        assertEquals(actorId, saved.updatedBy)
    }

    private fun organisation(suffix: String): OrganisationJdbcEntity =
        OrganisationJdbcEntity(
            id = uuidV7(),
            tenantCode = "test-$suffix-${uuidV7()}",
            displayName = "Test $suffix",
            countryCode = "KE",
            baseCurrencyCode = "KES",
            timezone = "Africa/Nairobi",
            status = "DRAFT",
        )
}
