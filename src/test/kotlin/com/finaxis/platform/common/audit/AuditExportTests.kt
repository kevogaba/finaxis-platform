package com.finaxis.platform.common.audit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.CorrelationContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOW = Instant.parse("2026-10-25T12:00:00Z")
private val FROM = NOW.minus(Duration.ofDays(2))

/**
 * The export's orchestration without a database (#167, ADR 0039): the order permission, filter,
 * count; the cap and the batch bound; and the one audit row, in each outcome.
 */
@Suppress("TooManyFunctions")
class AuditExportTests {
    private val organisationId = UUID.fromString("00000000-0000-7000-8000-0000000000a1")
    private val actorId = UUID.fromString("00000000-0000-7000-8000-0000000000a2")
    private val queries = ScriptedQueries()
    private val guard = RecordingGuard(queries)
    private val repository = RecordingRepository()
    private val service =
        AuditQueryService(
            queries,
            guard,
            AuditService(repository, Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private val context =
        RequestContext(
            actor = ActorContext(actorId, "kc-subject-1", "name", "who@example.test"),
            correlation = CorrelationContext("req-1", "corr-1"),
            userAgent = "agent/1.0",
            clientIp = "203.0.113.9",
        )

    private fun request(
        parameters: AuditSearchParameters = AuditSearchParameters(occurredFrom = FROM),
        supplied: Map<String, String> = mapOf("occurred_from" to FROM.toString()),
    ) = AuditExportRequest(organisationId, actorId, parameters, supplied, context)

    // ---- order: permission, then filter, then count --------------------------------------------

    @Test
    fun `the guard is asked for audit export in the caller's organisation before any read`() {
        service.prepareExport(request())

        assertEquals(
            listOf("guard:$actorId:$organisationId:audit.export", "count"),
            queries.calls.take(2),
        )
    }

    @Test
    fun `a refused caller gets the 403 even for a request that is also invalid`() {
        guard.deny = true

        assertFailsWith<ForbiddenOperationException> {
            service.prepareExport(request(AuditSearchParameters(outcome = "BOGUS")))
        }
        assertFailsWith<ForbiddenOperationException> {
            service.prepareExport(request(AuditSearchParameters()))
        }

        assertEquals(
            List(2) { "guard:$actorId:$organisationId:audit.export" },
            queries.calls,
        )
        assertTrue(repository.saved.isEmpty(), "a 403 writes no export row")
    }

    @Test
    fun `occurred_from is required and nothing is read or audited without it`() {
        val failure =
            assertFailsWith<InvalidPageRequestException> {
                service.prepareExport(request(AuditSearchParameters()))
            }

        assertEquals("occurred_from", failure.parameter)
        assertTrue("count" !in queries.calls)
        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `the window is bounded to thirty one days and may not run backwards`() {
        val tooLong =
            AuditSearchParameters(occurredFrom = NOW.minus(Duration.ofDays(31)).minusSeconds(1))
        val backwards =
            AuditSearchParameters(occurredFrom = FROM, occurredTo = FROM.minusSeconds(1))
        val future = AuditSearchParameters(occurredFrom = NOW.plusSeconds(1))
        val exactly = AuditSearchParameters(occurredFrom = NOW.minus(Duration.ofDays(31)))

        listOf(tooLong, backwards, future).forEach {
            val failure =
                assertFailsWith<InvalidPageRequestException> { service.prepareExport(request(it)) }
            assertEquals("occurred_from", failure.parameter)
        }
        service.prepareExport(request(exactly))
    }

    @Test
    fun `an invalid closed-set value or combination is a 400 naming it, before the count`() {
        listOf(
            AuditSearchParameters(occurredFrom = FROM, outcome = "BOGUS") to "outcome",
            AuditSearchParameters(occurredFrom = FROM, severity = "LOW", minSeverity = "HIGH") to
                "min_severity",
            AuditSearchParameters(occurredFrom = FROM, q = "ab") to "q",
            AuditSearchParameters(occurredFrom = FROM, sortDir = "SIDEWAYS") to "sort_dir",
        ).forEach { (parameters, field) ->
            val failure =
                assertFailsWith<InvalidPageRequestException> {
                    service.prepareExport(request(parameters))
                }
            assertEquals(field, failure.parameter)
        }
        assertTrue("count" !in queries.calls)
        assertTrue(repository.saved.isEmpty(), "a 400 for a bad filter writes no export row")
    }

    @Test
    fun `an absent occurred_to is pinned to the request instant and a given one is kept`() {
        service.prepareExport(request())
        assertEquals(NOW, queries.countedFilters.single().occurredTo)

        val to = NOW.minusSeconds(60)
        service.prepareExport(request(AuditSearchParameters(occurredFrom = FROM, occurredTo = to)))
        assertEquals(to, queries.countedFilters.last().occurredTo)
    }

    @Test
    fun `a future occurred_to is clamped to the request instant, for count and stream alike`() {
        queries.batchSizes = ArrayDeque(listOf(1))
        val future = AuditSearchParameters(occurredFrom = FROM, occurredTo = NOW.plusSeconds(3600))
        val export = service.prepareExport(request(future))

        export.stream(start = {}, batch = {})

        assertEquals(NOW, queries.countedFilters.single().occurredTo)
        assertEquals(NOW, queries.exportedFilters.single().occurredTo)
    }

    @Test
    fun `an occurred_to at the request instant is kept, and one before occurred_from is a 400`() {
        service.prepareExport(request(AuditSearchParameters(occurredFrom = FROM, occurredTo = NOW)))
        assertEquals(NOW, queries.countedFilters.single().occurredTo)

        val backwards =
            AuditSearchParameters(occurredFrom = FROM, occurredTo = FROM.minusSeconds(1))
        val failure =
            assertFailsWith<InvalidPageRequestException> {
                service.prepareExport(request(backwards))
            }
        assertEquals("occurred_from", failure.parameter)
    }

    @Test
    fun `the filter is tenant scoped, parsed as the search parses it and ordered by sort_dir`() {
        service.prepareExport(
            request(
                AuditSearchParameters(
                    occurredFrom = FROM,
                    outcome = "denied",
                    sortDir = "asc",
                    actionPrefix = "branch.",
                ),
            ),
        )

        val filter = queries.countedFilters.single()
        assertEquals(organisationId, filter.organisationId)
        assertEquals(AuditOutcome.DENIED, filter.outcome)
        assertTrue(filter.ascending)
        assertEquals("branch.", filter.actionPrefix)
    }

    // ---- the cap --------------------------------------------------------------------------------

    @Test
    fun `the production cap, batch size and permission are the decided values`() {
        assertEquals(10_000, AUDIT_EXPORT_ROW_CAP)
        assertEquals(1_000, AUDIT_EXPORT_BATCH_SIZE)
        assertEquals("audit.export", AUDIT_EXPORT_PERMISSION)
        assertEquals("audit.export_too_large", AUDIT_EXPORT_TOO_LARGE_CODE)
    }

    @Test
    fun `the count stops one past the cap, and exactly the cap is allowed`() {
        queries.matching = 10_000
        service.prepareExport(request())
        assertEquals(10_001, queries.countLimits.single())
        assertTrue(repository.saved.isEmpty(), "an allowed export writes its row after the stream")
    }

    @Test
    fun `more than the cap is refused with a zero-row failure row and no count in the message`() {
        queries.matching = 10_001

        val failure = assertFailsWith<InvalidRequestException> { service.prepareExport(request()) }

        assertEquals("audit.export_too_large", failure.code)
        assertTrue(!failure.message.orEmpty().contains(Regex("\\d")), "${failure.message}")
        assertTrue(failure.message.orEmpty().contains("Narrow"))
        val row = repository.saved.single()
        assertEquals("audit.export", row.action)
        assertEquals(AuditOutcome.FAILURE, row.outcome)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(0, row.metadata["row_count"])
        assertNull(row.metadata["error_class"])
        assertEquals(listOf("independent"), repository.writes)
    }

    @Test
    fun `a failing audit write for the refusal does not change the 400`() {
        queries.matching = 10_001
        repository.failWith = IllegalStateException("db-secret")

        val failure = assertFailsWith<InvalidRequestException> { service.prepareExport(request()) }

        assertEquals("audit.export_too_large", failure.code)
    }

    @Test
    fun `exactly the cap and no more completes in batches of at most one thousand as SUCCESS`() {
        queries.batchSizes = ArrayDeque(List(10) { 1_000 })
        val export = service.prepareExport(request())
        val batches = mutableListOf<Int>()

        export.stream(start = {}, batch = { batches += it.size })

        assertEquals(List(10) { 1_000 }, batches)
        assertEquals(10_000, queries.exportCalls.sumOf { it.rows })
        assertTrue(queries.exportCalls.all { it.limit <= 1_000 })
        assertEquals(1, queries.exportCalls.last().limit, "one probe row past the cap, found empty")
        val row = repository.saved.single()
        assertEquals(AuditOutcome.SUCCESS, row.outcome)
        assertEquals(10_000, row.metadata["row_count"])
    }

    @Test
    fun `a row still matching after the cap aborts the stream as FAILURE, never a short file`() {
        queries.endless = true
        val export = service.prepareExport(request())
        val batches = mutableListOf<Int>()

        val failure =
            assertFailsWith<AuditExportCapExceededException> {
                export.stream(start = {}, batch = { batches += it.size })
            }

        assertTrue(!failure.message.orEmpty().contains(Regex("\\d")), "${failure.message}")
        assertEquals(List(10) { 1_000 }, batches)
        assertEquals(1, queries.exportCalls.last().limit, "the probe reads one row, past the cap")
        val row = repository.saved.single()
        assertEquals(AuditOutcome.FAILURE, row.outcome)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(10_000, row.metadata["row_count"])
        assertEquals("AuditExportCapExceededException", row.metadata["error_class"])
    }

    @Test
    fun `the keyset cursor of each batch continues the one before`() {
        queries.batchSizes = ArrayDeque(listOf(1_000, 1_000, 250))
        val export = service.prepareExport(request())

        export.stream(start = {}, batch = {})

        assertEquals(
            listOf(null, UUID(0, 1), UUID(0, 2)),
            queries.exportCalls.map { it.after?.id },
        )
        assertEquals(1_000, queries.exportCalls.first().limit)
    }

    // ---- the one audit row ----------------------------------------------------------------------

    @Test
    fun `a successful stream writes one SUCCESS MEDIUM row about the request, not its contents`() {
        queries.batchSizes = ArrayDeque(listOf(7))
        val export =
            service.prepareExport(
                request(
                    AuditSearchParameters(occurredFrom = FROM, actionPrefix = "branch."),
                    mapOf(
                        "occurred_from" to FROM.toString(),
                        "action_prefix" to "branch.",
                        "q" to "someone@example.com",
                        "actor_subject" to "kc-subject-9",
                        "page" to "3",
                        "unknown" to "x",
                    ),
                ),
            )
        var started = 0

        export.stream(start = { started++ }, batch = {})

        assertEquals(1, started)
        val row = repository.saved.single()
        assertEquals("audit.export", row.action)
        assertEquals("AUDIT_EVENT", row.resourceType)
        assertNull(row.resourceId)
        assertEquals(AuditOutcome.SUCCESS, row.outcome)
        assertEquals(AuditSeverity.MEDIUM, row.severity)
        assertEquals(actorId.toString(), row.actorId)
        assertEquals(organisationId.toString(), row.tenantId)
        assertEquals(setOf("filters", "row_count"), row.metadata.keys)
        assertEquals(7, row.metadata["row_count"])
        assertEquals(
            mapOf(
                "action_prefix" to "branch.",
                "occurred_from" to FROM.toString(),
                "q" to "[redacted]",
                "actor_subject" to "[redacted]",
            ),
            row.metadata["filters"],
        )
        assertEquals(listOf("direct"), repository.writes)
    }

    @Test
    fun `the row carries the captured request id, address, agent and the actor's context`() {
        val export = service.prepareExport(request())

        export.stream(start = {}, batch = {})

        val row = repository.saved.single()
        assertEquals("req-1", row.requestId)
        assertEquals("203.0.113.9", row.sourceIp)
        assertEquals("agent/1.0", row.userAgent)
        assertEquals("kc-subject-1", repository.contextSubjectDuringSave.single())
        assertNull(RequestContexts.current(), "the snapshot is not left installed on the thread")
    }

    @Test
    fun `a long filter value is cut and every other supplied value is kept as sent`() {
        val export =
            service.prepareExport(
                request(
                    supplied =
                        mapOf(
                            "occurred_from" to FROM.toString(),
                            "entity_type" to "E".repeat(1_000),
                            "action" to "branch.update",
                        ),
                ),
            )

        export.stream(start = {}, batch = {})

        val filters = repository.saved.single().metadata["filters"] as Map<*, *>
        assertEquals(256, (filters["entity_type"] as String).length)
        assertEquals("branch.update", filters["action"])
    }

    @Test
    fun `an aborted stream writes one FAILURE HIGH row with the class name and the rows so far`() {
        queries.batchSizes = ArrayDeque(listOf(1_000, -1))
        val export = service.prepareExport(request())

        val failure =
            assertFailsWith<IllegalStateException> {
                export.stream(start = {}, batch = {})
            }

        assertEquals("scripted read failure secret", failure.message)
        val row = repository.saved.single()
        assertEquals(AuditOutcome.FAILURE, row.outcome)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(1_000, row.metadata["row_count"])
        assertEquals("IllegalStateException", row.metadata["error_class"])
        assertTrue(!row.metadata.toString().contains("secret"), "never the message")
    }

    @Test
    fun `a failure in the renderer, even before the first row, is audited with zero rows`() {
        val export = service.prepareExport(request())

        assertFailsWith<java.io.IOException> {
            export.stream(start = { throw java.io.IOException("client gone secret") }, batch = {})
        }

        val row = repository.saved.single()
        assertEquals(AuditOutcome.FAILURE, row.outcome)
        assertEquals(0, row.metadata["row_count"])
        assertEquals("IOException", row.metadata["error_class"])
    }

    @Test
    fun `a failure while rendering a batch counts only the batches already written`() {
        queries.batchSizes = ArrayDeque(listOf(1_000, 1_000))
        val export = service.prepareExport(request())
        var calls = 0

        assertFailsWith<java.io.IOException> {
            export.stream(
                start = {},
                batch = { if (++calls == 2) throw java.io.IOException("gone") },
            )
        }

        assertEquals(1_000, repository.saved.single().metadata["row_count"])
    }

    @Test
    fun `a failing audit write after the stream is a message-free warning and no exception`() {
        queries.batchSizes = ArrayDeque(listOf(3))
        repository.failWith = IllegalStateException("db-secret-message")
        val export = service.prepareExport(request())
        val appender = capture()

        export.stream(start = {}, batch = {})

        val warnings = appender.list.filter { it.level == Level.WARN }
        assertEquals(1, warnings.size)
        val message = warnings.single().formattedMessage
        assertTrue(message.contains("IllegalStateException"), message)
        assertTrue(!message.contains("db-secret-message"), message)
        assertNull(warnings.single().throwableProxy, "no throwable reaches the logger")
        assertEquals(1, repository.attempts, "no retry")
    }

    @Test
    fun `the request's string form names no filter value, subject, email or address`() {
        val text =
            request(
                supplied =
                    mapOf(
                        "occurred_from" to FROM.toString(),
                        "q" to "someone@example.com",
                        "actor_subject" to "kc-subject-9",
                    ),
            ).toString()

        assertTrue(!text.contains("someone@example.com"), text)
        assertTrue(!text.contains("kc-subject-9"), text)
        assertTrue(!text.contains("who@example.test"), text)
        assertTrue(!text.contains("203.0.113.9"), text)
        assertTrue(text.contains(actorId.toString()), text)
    }

    @Test
    fun `an interrupted stream thread still writes the failure row and keeps its flag`() {
        queries.batchSizes = ArrayDeque(listOf(3))
        val export = service.prepareExport(request())
        var flagAfter = false
        val thread =
            Thread {
                Thread.currentThread().interrupt()
                runCatching {
                    export.stream(start = {}, batch = { throw java.io.IOException("cancelled") })
                }
                flagAfter = Thread.interrupted()
            }

        thread.start()
        thread.join()

        assertEquals(listOf(false), repository.interruptedDuringSave, "cleared for the write")
        assertTrue(flagAfter, "restored afterwards, so the cancellation is not lost")
        val row = repository.saved.single()
        assertEquals(AuditOutcome.FAILURE, row.outcome)
        assertEquals("IOException", row.metadata["error_class"])
    }

    // ---- fakes ----------------------------------------------------------------------------------

    private fun capture(): ListAppender<ILoggingEvent> {
        val logger =
            LoggerFactory.getLogger("com.finaxis.platform.common.audit.AuditExport") as Logger
        return ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
    }

    private data class ExportCall(
        val after: AuditExportCursor?,
        val limit: Int,
        val rows: Int,
    )

    private class ScriptedQueries : AuditEventQueries {
        val calls = mutableListOf<String>()
        val countedFilters = mutableListOf<AuditEventFilter>()
        val exportedFilters = mutableListOf<AuditEventFilter>()
        val countLimits = mutableListOf<Int>()
        val exportCalls = mutableListOf<ExportCall>()
        var matching = 0
        var endless = false
        var batchSizes: ArrayDeque<Int> = ArrayDeque()
        private var next = 0

        override fun search(filter: AuditEventFilter) = AuditEventPage(emptyList(), 0)

        override fun findById(
            id: UUID,
            organisationId: UUID,
        ): AuditEventDetail? = null

        override fun countBounded(
            filter: AuditEventFilter,
            limit: Int,
        ): Int {
            calls += "count"
            countedFilters += filter
            countLimits += limit
            return minOf(matching, limit)
        }

        override fun exportBatch(
            filter: AuditEventFilter,
            after: AuditExportCursor?,
            limit: Int,
        ): AuditExportBatch {
            calls += "export"
            exportedFilters += filter
            val size =
                when {
                    endless -> limit
                    batchSizes.isEmpty() -> 0
                    else -> batchSizes.removeFirst()
                }
            check(size >= 0) { "scripted read failure secret" }
            exportCalls += ExportCall(after, limit, size)
            val rows = List(size) { row() }
            next++
            val cursor =
                if (rows.isEmpty()) {
                    null
                } else {
                    AuditExportCursor(NOW, UUID(0, next.toLong()))
                }
            return AuditExportBatch(rows, cursor)
        }

        private fun row() =
            AuditExportRow(NOW, "a.b", "SUCCESS", "INFO", "USER", null, null, null, "T", null, null)
    }

    private class RecordingGuard(
        private val queries: ScriptedQueries,
    ) : AuditPermissionGuard {
        var deny = false

        override fun requireTenantPermission(
            actorId: UUID,
            organisationId: UUID,
            permissionCode: String,
        ) {
            queries.calls += "guard:$actorId:$organisationId:$permissionCode"
            if (deny) throw ForbiddenOperationException()
        }
    }

    private class RecordingRepository : AuditEventRepository {
        val saved = mutableListOf<AuditEvent>()
        val writes = mutableListOf<String>()
        val contextSubjectDuringSave = mutableListOf<String?>()
        val interruptedDuringSave = mutableListOf<Boolean>()
        var failWith: RuntimeException? = null
        var attempts = 0

        override fun save(event: AuditEvent) {
            attempts++
            interruptedDuringSave += Thread.currentThread().isInterrupted
            failWith?.let { throw it }
            contextSubjectDuringSave += RequestContexts.current()?.actor?.externalSubject
            saved += event
            val independent =
                Thread.currentThread().stackTrace.any { it.methodName == "recordIndependently" }
            writes += if (independent) "independent" else "direct"
        }
    }
}
