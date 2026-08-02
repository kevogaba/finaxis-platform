package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import io.namastack.outbox.Outbox
import io.namastack.outbox.annotation.OutboxHandler
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration canaries for the Namastack starter-owned outbox schema and retry contract used by
 * foundation event externalization.
 */
@Import(TestcontainersConfiguration::class, FailingOutboxHandlerConfiguration::class)
@SpringBootTest(
    properties = [
        // Matches the suite-wide test interval in src/test/resources/application.yaml; restated
        // here only because this class overrides the retry block below and the two should be read
        // together.
        "namastack.outbox.polling.fixed.interval=200ms",
        "namastack.outbox.retry.max-retries=5",
        "namastack.outbox.retry.exponential.initial-delay=1s",
        "namastack.outbox.retry.exponential.max-delay=1s",
        "namastack.outbox.retry.jitter=0ms",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OutboxSchemaAndRetryIntegrationTests(
    private val jdbcTemplate: JdbcTemplate,
    private val outbox: Outbox,
) {
    @Test
    fun `outbox record table exposes columns and indexes the application depends on`() {
        // Canary for io.namastack:namastack-outbox-starter-jdbc upgrades changing the table
        // contract consumed by monitoring, retry checks, and completed-record assertions.
        assertEquals(expectedColumns, outboxColumns())

        val indexes = outboxIndexes()
        expectedIndexes.forEach { (name, indexedColumns) ->
            val definition = assertNotNull(indexes[name], "Missing outbox index $name")
            indexedColumns.forEach { column ->
                assertTrue(definition.contains(column), "Index $name did not include $column")
            }
        }
    }

    @Test
    fun `publisher failure leaves the record retryable`() {
        val recordKey = scheduleFailingEvent()

        await()
            .atMost(10, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val row = outboxRecord(recordKey)
                assertEquals("NEW", row.status)
                assertTrue(row.failureCount in 1 until MAX_RETRIES)
                assertNotNull(row.nextRetryAt)
            }
    }

    @Test
    fun `publisher failure marks the record failed after max retries`() {
        val recordKey = scheduleFailingEvent()

        await()
            .atMost(30, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val row = outboxRecord(recordKey)
                // `FAILED` is Namastack's terminal dead-letter state; the library has no separate
                // DEAD_LETTERED status (see ADR 0008). `failure_count` counts *attempts*, so
                // max-retries=5 lands on 6: the initial attempt plus five retries.
                assertEquals("FAILED", row.status)
                assertEquals(MAX_RETRIES + 1, row.failureCount)
                assertNotNull(row.failureReason)
            }
    }

    private fun scheduleFailingEvent(): String {
        val recordKey = "phase-b-outbox-${uuidV7()}"
        outbox.schedule(FailingOutboxPayload(recordKey), recordKey)
        return recordKey
    }

    private fun outboxColumns(): List<OutboxColumn> =
        jdbcTemplate.query(
            """
            SELECT column_name, data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'outbox_record'
            ORDER BY ordinal_position
            """.trimIndent(),
        ) { rs, _ ->
            OutboxColumn(
                name = rs.getString("column_name"),
                dataType = rs.getString("data_type"),
                nullable = rs.getString("is_nullable"),
            )
        }

    private fun outboxIndexes(): Map<String, String> =
        jdbcTemplate
            .query(
                """
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'outbox_record'
                """.trimIndent(),
            ) { rs, _ -> rs.getString("indexname") to rs.getString("indexdef") }
            .toMap()

    private fun outboxRecord(recordKey: String): OutboxRecordRow =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT status, failure_count, failure_reason, next_retry_at
                FROM outbox_record
                WHERE record_key = ?
                """.trimIndent(),
                { rs, _ ->
                    OutboxRecordRow(
                        status = rs.getString("status"),
                        failureCount = rs.getInt("failure_count"),
                        failureReason = rs.getString("failure_reason"),
                        nextRetryAt = rs.getTimestamp("next_retry_at"),
                    )
                },
                recordKey,
            ),
        ) { "No outbox row found for $recordKey" }

    private companion object {
        const val MAX_RETRIES = 5

        val expectedColumns =
            listOf(
                OutboxColumn("id", "character varying", "NO"),
                OutboxColumn("status", "character varying", "NO"),
                OutboxColumn("record_key", "character varying", "NO"),
                OutboxColumn("record_type", "character varying", "NO"),
                OutboxColumn("payload", "text", "NO"),
                OutboxColumn("context", "text", "YES"),
                OutboxColumn("created_at", "timestamp with time zone", "NO"),
                OutboxColumn("completed_at", "timestamp with time zone", "YES"),
                OutboxColumn("failure_count", "integer", "NO"),
                OutboxColumn("failure_reason", "character varying", "YES"),
                OutboxColumn("next_retry_at", "timestamp with time zone", "NO"),
                OutboxColumn("partition_no", "integer", "NO"),
                OutboxColumn("handler_id", "character varying", "NO"),
            )

        val expectedIndexes =
            mapOf(
                "idx_outbox_record_record_key_created" to listOf("record_key", "created_at"),
                "idx_outbox_record_partition_status_retry" to
                    listOf("partition_no", "status", "next_retry_at"),
                "idx_outbox_record_status_retry" to listOf("status", "next_retry_at"),
                "idx_outbox_record_status" to listOf("status"),
                "idx_outbox_record_record_key_completed_created" to
                    listOf("record_key", "completed_at", "created_at"),
            )
    }
}

private data class OutboxColumn(
    val name: String,
    val dataType: String,
    val nullable: String,
)

private data class OutboxRecordRow(
    val status: String,
    val failureCount: Int,
    val failureReason: String?,
    val nextRetryAt: Any?,
)

@TestConfiguration(proxyBeanMethods = false)
private class FailingOutboxHandlerConfiguration {
    @Bean
    fun failingOutboxHandler(): FailingOutboxHandler = FailingOutboxHandler()
}

private class FailingOutboxHandler {
    @OutboxHandler
    fun handle(payload: FailingOutboxPayload) {
        error("Simulated outbox handler failure for ${payload.id}")
    }
}

private data class FailingOutboxPayload(
    val id: String,
)
