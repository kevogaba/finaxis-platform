package com.finaxis.platform.config

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeImageResourceHintsTests {
    private val hints =
        RuntimeHints().also {
            NativeImageResourceHints().registerHints(it, javaClass.classLoader)
        }

    @Test
    fun `registers the namastack outbox schema script`() {
        assertResourceRegistered("schema/postgres/outbox-tables.sql")
    }

    @Test
    fun `registers the modulith event publication schema script`() {
        assertResourceRegistered(
            "org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql",
        )
    }

    @Test
    fun `registers the jobrunr migration directories`() {
        assertResourceRegistered(
            "org/jobrunr/storage/sql/common/migrations/v000__create_tables.sql",
        )
        assertResourceRegistered(
            "org/jobrunr/storage/sql/postgres/migrations/v014__improve_job_stats.sql",
        )
    }

    @Test
    fun `registers the outbound email templates`() {
        assertResourceRegistered("templates/email/welcome.ftlh")
        assertResourceRegistered("templates/email/organisation-invite.ftlh")
    }

    private fun assertResourceRegistered(resource: String) {
        assertTrue(
            RuntimeHintsPredicates.resource().forResource(resource).test(hints),
            "expected $resource to be registered as a native image resource",
        )
    }
}
