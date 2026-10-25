package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.audit.AuditExportRow
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The CSV encoding of the audit export (ADR 0039, points 14 and 15), without a database. */
class AuditEventCsvTests {
    @Test
    fun `the header is the eleven columns in order, ended by CRLF`() {
        assertEquals(
            "occurred_at,action,outcome,severity,actor_type,actor_user_id,branch_id," +
                "subject_user_id,entity_type,entity_id,request_id\r\n",
            AuditEventCsv.header(),
        )
        assertEquals(11, AuditEventCsv.COLUMNS.size)
    }

    @Test
    fun `none of the excluded columns is in the list`() {
        val excluded =
            listOf(
                "ip_address",
                "user_agent",
                "actor_external_subject",
                "before_json",
                "after_json",
                "metadata_json",
                "reason",
                "correlation_id",
                "id",
                "event_id",
                "organisation_id",
            )

        assertTrue(AuditEventCsv.COLUMNS.none { it in excluded }, "${AuditEventCsv.COLUMNS}")
    }

    @Test
    fun `a null or empty cell is empty`() {
        assertEquals("", AuditEventCsv.cell(null))
        assertEquals("", AuditEventCsv.cell(""))
    }

    @Test
    fun `a cell starting with a formula character gets a leading single quote`() {
        listOf("=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx", "-").forEach {
            val cells = CsvTestParser.parse(AuditEventCsv.cell(it) + "\r\n").single()

            assertEquals(listOf("'$it"), cells, "cell $it")
        }
        assertEquals("'-", AuditEventCsv.cell("-"))
        assertEquals("'=SUM(1)", AuditEventCsv.cell("=SUM(1)"))
    }

    @Test
    fun `a formula character that is not first is left alone`() {
        listOf("a=b", "1-1", "x@y", " =1", "'=1").forEach {
            assertEquals(it, AuditEventCsv.cell(it))
        }
    }

    @Test
    fun `a cell with a comma, quote, CR or LF is quoted with its quotes doubled`() {
        assertEquals("\"a,b\"", AuditEventCsv.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", AuditEventCsv.cell("say \"hi\""))
        assertEquals("\"l1\nl2\"", AuditEventCsv.cell("l1\nl2"))
        assertEquals("\"l1\r\nl2\"", AuditEventCsv.cell("l1\r\nl2"))
        assertEquals("plain text", AuditEventCsv.cell("plain text"))
    }

    @Test
    fun `the guard comes before the quoting, so a quoted formula keeps its quote inside`() {
        // The leading CR is guarded and, being a CR, then quoted too.
        assertEquals("\"'\rx\"", AuditEventCsv.cell("\rx"))
        assertEquals("\"'=a,b\"", AuditEventCsv.cell("=a,b"))
    }

    @Test
    fun `every encoded cell round-trips through a strict RFC 4180 reader`() {
        val samples =
            listOf(
                "a,b",
                "say \"hi\"",
                "line1\nline2",
                "x\r\ny",
                "\"",
                ",",
                "\"\"",
                "trailing,",
                " spaced ",
                "ünï©ødé ✓",
                "plain",
                "'already",
            )
        val line = samples.joinToString(",") { AuditEventCsv.cell(it) } + "\r\n"

        assertEquals(listOf(samples), CsvTestParser.parse(line))
    }

    @Test
    fun `a record is eleven cells with empty cells for the missing ids`() {
        val actor = UUID.fromString("00000000-0000-7000-8000-000000000001")
        val row =
            AuditExportRow(
                occurredAt = Instant.parse("2026-07-25T08:12:00Z"),
                action = "branch.update",
                outcome = "SUCCESS",
                severity = "INFO",
                actorType = "USER",
                actorUserId = actor,
                branchId = null,
                subjectUserId = null,
                entityType = "BRANCH",
                entityId = null,
                requestId = null,
            )

        val record = AuditEventCsv.record(row) { it.toString() }

        assertEquals(
            "2026-07-25T08:12:00Z,branch.update,SUCCESS,INFO,USER,$actor,,,BRANCH,,\r\n",
            record,
        )
        assertEquals(11, CsvTestParser.parse(record).single().size)
    }

    @Test
    fun `the test parser itself refuses malformed csv`() {
        assertFailsWith<IllegalStateException> { CsvTestParser.parse("a\nb\r\n") }
        assertFailsWith<IllegalStateException> { CsvTestParser.parse("\"open") }
        assertFailsWith<IllegalStateException> { CsvTestParser.parse("ab\"c\r\n") }
        assertEquals(listOf(listOf("a", ""), listOf("", "b")), CsvTestParser.parse("a,\r\n,b\r\n"))
    }
}
