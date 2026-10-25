package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditExportRow
import java.time.Instant

/**
 * The CSV form of the audit export (ADR 0039, points 10, 14 and 15): RFC 4180, records separated
 * by CRLF, no byte-order mark, the eleven columns in a fixed order.
 *
 * Every cell goes through [cell], in this order: (a) a cell whose first character is `=`, `+`, `-`,
 * `@`, TAB or CR is prefixed with a single quote, so a spreadsheet never runs it as a formula
 * (the prefix is a lossy change a programmatic consumer must know); (b) a cell holding a comma,
 * a double quote, CR or LF is wrapped in double quotes with each inner quote doubled; (c) a null
 * is an empty cell.
 */
internal object AuditEventCsv {
    /** The exported columns, in order, with the JSON API's snake_case names. */
    val COLUMNS: List<String> =
        listOf(
            "occurred_at",
            "action",
            "outcome",
            "severity",
            "actor_type",
            "actor_user_id",
            "branch_id",
            "subject_user_id",
            "entity_type",
            "entity_id",
            "request_id",
        )

    private const val RECORD_END = "\r\n"
    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t', '\r')
    private val NEEDS_QUOTES = setOf(',', '"', '\r', '\n')

    /** The header record, including its line end. */
    fun header(): String = COLUMNS.joinToString(",") + RECORD_END

    /**
     * The record of [row], including its line end. [instantText] renders `occurred_at`; the web
     * layer passes the JSON API's own serializer, so the two representations cannot differ.
     */
    fun record(
        row: AuditExportRow,
        instantText: (Instant) -> String,
    ): String =
        listOf(
            instantText(row.occurredAt),
            row.action,
            row.outcome,
            row.severity,
            row.actorType,
            row.actorUserId?.toString(),
            row.branchId?.toString(),
            row.subjectUserId?.toString(),
            row.entityType,
            row.entityId?.toString(),
            row.requestId,
        ).joinToString(",") { cell(it) } + RECORD_END

    /** One cell of a record, formula-guarded and RFC 4180 quoted as the class comment says. */
    fun cell(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val guarded = if (value.first() in FORMULA_STARTS) "'$value" else value
        return if (guarded.any { it in NEEDS_QUOTES }) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else {
            guarded
        }
    }
}
