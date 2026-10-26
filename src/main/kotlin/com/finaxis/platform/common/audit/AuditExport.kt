package com.finaxis.platform.common.audit

import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/** The most rows one audit export may hold; a larger match is refused, never truncated. */
const val AUDIT_EXPORT_ROW_CAP = 10_000

/** The rows one keyset read returns, so no more than this many are ever in memory at once. */
const val AUDIT_EXPORT_BATCH_SIZE = 1_000

/** The permission an audit export needs (with the views the catalogue pairs with it). */
const val AUDIT_EXPORT_PERMISSION = "audit.export"

/** The problem code of an export that matches more than [AUDIT_EXPORT_ROW_CAP] rows. */
const val AUDIT_EXPORT_TOO_LARGE_CODE = "audit.export_too_large"

/** The action of the one audit row an export request writes (ADR 0039, point 17). */
const val AUDIT_EXPORT_ACTION = "audit.export"

/** The value stored in the export row's `filters` for a filter that may hold personal data. */
const val AUDIT_EXPORT_REDACTED = "[redacted]"

private const val AUDIT_EXPORT_RESOURCE_TYPE = "AUDIT_EVENT"
private const val FILTER_VALUE_MAX_LENGTH = 256

/** Filters whose value is an email address or an identity subject: stored redacted. */
private val REDACTED_FILTERS = setOf("q", "actor_subject")

/**
 * Everything an export request carries from the request thread, as plain values captured before
 * the stream starts: the stream's thread reads no request-scoped state (ADR 0039, point 13).
 *
 * [suppliedFilters] holds the filter parameters the caller sent, by snake_case name and as sent.
 * [requestContext] is the request's immutable context snapshot (actor, request id, client
 * address, user agent), or null outside a request.
 */
data class AuditExportRequest(
    val organisationId: UUID,
    val actorId: UUID,
    val parameters: AuditSearchParameters,
    val suppliedFilters: Map<String, String>,
    val requestContext: RequestContext?,
) {
    /** Names no filter value, actor detail or address: `q` may be an email address. */
    override fun toString(): String =
        "AuditExportRequest(organisationId=$organisationId, actorId=$actorId, " +
            "filters=${suppliedFilters.keys})"
}

/**
 * An audit export that passed authorisation, validation and the size check and is ready to stream
 * (ADR 0039). [stream] reads the rows in bounded keyset batches, hands each batch to the caller
 * to render, and then writes the one `audit.export` audit row for the request: `SUCCESS` when the
 * last batch has been handed over, `FAILURE` when the stream fails after it began.
 *
 * It is a plain value, not a bean: it holds the request's captured values and the ports it reads
 * through, and nothing request-scoped.
 */
class AuditExport internal constructor(
    /** The instant the request was served at; the export file is named for its UTC date. */
    val requestedAt: Instant,
    private val filter: AuditEventFilter,
    private val request: AuditExportRequest,
    private val queries: AuditEventQueries,
    private val auditService: AuditService,
) {
    /**
     * Streams the export: [start] runs once before any row (the header), then [batch] runs once per
     * non-empty batch, in order, and flushes. The rows written never exceed [AUDIT_EXPORT_ROW_CAP]
     * (at the cap one further row is probed and discarded), and a batch never holds more than
     * [AUDIT_EXPORT_BATCH_SIZE]. An exception from either callback or from the read is audited as
     * a `FAILURE` row and rethrown; the audit row never changes the outcome, and a failure to
     * write it is a message-free warning. When the cap is
     * reached and one more row still matches, the stream fails with
     * [AuditExportCapExceededException] rather than end looking complete.
     */
    fun stream(
        start: () -> Unit,
        batch: (List<AuditExportRow>) -> Unit,
    ) {
        var written = 0
        var cursor: AuditExportCursor? = null
        try {
            start()
            while (written < AUDIT_EXPORT_ROW_CAP) {
                val limit = minOf(AUDIT_EXPORT_BATCH_SIZE, AUDIT_EXPORT_ROW_CAP - written)
                val result = queries.exportBatch(filter, cursor, limit)
                if (result.rows.isNotEmpty()) {
                    batch(result.rows)
                    // Counted only once the batch was handed over: a failure reports the rows of
                    // the batches written so far.
                    written += result.rows.size
                }
                cursor = result.next
                if (result.rows.size < limit || cursor == null) break
            }
            // The cap is reached: one more row means the file would end cleanly but short (a row
            // committed after the up-front count and still inside the window), so abort instead.
            if (written == AUDIT_EXPORT_ROW_CAP && cursor != null &&
                queries.exportBatch(filter, cursor, 1).rows.isNotEmpty()
            ) {
                throw AuditExportCapExceededException()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            recordOutcome(AuditOutcome.FAILURE, written, failure.errorClass())
            throw failure
        }
        recordOutcome(AuditOutcome.SUCCESS, written, null)
    }

    private fun recordOutcome(
        outcome: AuditOutcome,
        rowCount: Int,
        errorClass: String?,
    ) {
        writeExportAudit(
            auditService,
            request,
            outcome,
            rowCount,
            errorClass,
            independently = false,
        )
    }

    private fun Exception.errorClass(): String = javaClass.simpleName.ifBlank { javaClass.name }
}

/**
 * Thrown by a stream that wrote [AUDIT_EXPORT_ROW_CAP] rows while another matching row remained, so
 * the file is never left complete-looking and short (ADR 0039, point 12). Message-free on purpose.
 */
class AuditExportCapExceededException : IllegalStateException("Audit export cap exceeded.")

/**
 * Writes the one audit row of an export request. The row is about the request, not about the
 * events in it (ADR 0031): resource type `AUDIT_EVENT`, no resource id, and metadata of exactly
 * `filters`, `row_count` and, for an aborted stream, `error_class` (a simple class name, never a
 * message). A `SUCCESS` is `MEDIUM`: a bulk extraction is not informational. A `FAILURE` is `HIGH`.
 *
 * The request thread's captured context is installed for the write, so the row carries the actor's
 * external subject and correlation id like every other row, even when this runs on the stream's
 * own thread. A failed write is logged as one message-free warning and never thrown: the export is
 * not undone and nothing is retried.
 */
internal fun writeExportAudit(
    auditService: AuditService,
    request: AuditExportRequest,
    outcome: AuditOutcome,
    rowCount: Int,
    errorClass: String?,
    independently: Boolean,
) {
    val context = request.requestContext
    val command =
        AuditCommand(
            actorType = "USER",
            actorId = request.actorId.toString(),
            tenantId = request.organisationId.toString(),
            action = AUDIT_EXPORT_ACTION,
            resourceType = AUDIT_EXPORT_RESOURCE_TYPE,
            resourceId = null,
            outcome = outcome,
            severity =
                if (outcome == AuditOutcome.SUCCESS) AuditSeverity.MEDIUM else AuditSeverity.HIGH,
            requestId = context?.correlation?.requestId,
            sourceIp = context?.clientIp,
            userAgent = context?.userAgent,
            metadata =
                buildMap {
                    put("filters", auditedFilters(request.suppliedFilters))
                    put("row_count", rowCount)
                    errorClass?.let { put("error_class", it) }
                },
        )
    val write = {
        try {
            if (independently) {
                auditService.recordIndependently(command)
            } else {
                auditService.record(command)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            // Inside the installed context, so the warning carries the request id in its MDC.
            exportLogger.warn(
                "audit export row not written outcome={} rowCount={}: {}",
                outcome,
                rowCount,
                failure.toMessageFreeStackTraceOrUnavailable(),
            )
        }
    }
    // An async timeout cancels the stream thread with an interrupt, and the audit row must still be
    // written: the flag is cleared for the write and restored afterwards.
    val interrupted = Thread.interrupted()
    try {
        if (context == null) write() else RequestContexts.with(context, write)
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/**
 * The filters the caller sent, in the parameters' own order and as sent (a long value is cut), with
 * `q` (which may be an email address) and `actor_subject` (an identity subject) stored as
 * [AUDIT_EXPORT_REDACTED]: audit metadata holds no personal data.
 */
private fun auditedFilters(supplied: Map<String, String>): Map<String, String> =
    buildMap {
        AuditSearchParameters.NAMES.forEach { name ->
            supplied[name]?.let {
                put(
                    name,
                    if (name in REDACTED_FILTERS) {
                        AUDIT_EXPORT_REDACTED
                    } else {
                        it.take(FILTER_VALUE_MAX_LENGTH)
                    },
                )
            }
        }
    }

private val exportLogger = LoggerFactory.getLogger("com.finaxis.platform.common.audit.AuditExport")
