package com.finaxis.platform.common.audit

import com.finaxis.platform.common.web.api.InvalidPageRequestException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audit search filters of #183: every closed-set value is parsed case-insensitively, and
 * every value outside its set or its bounds is a 400 `invalid_parameter` naming the parameter,
 * whose message never repeats the rejected value.
 */
class AuditSearchParametersTests {
    private val organisationId = UUID.randomUUID()
    private val now = Instant.parse("2026-10-08T12:00:00Z")

    @Test
    fun `no parameters is the unfiltered newest-first page`() {
        val filter = AuditSearchParameters().toFilter(organisationId, page = 2, size = 10)

        assertEquals(AuditEventFilter(organisationId, page = 2, size = 10), filter)
        assertFalse(filter.ascending)
    }

    @Test
    fun `closed-set values are parsed case-insensitively`() {
        val filter =
            AuditSearchParameters(
                outcome = "denied",
                minSeverity = "High",
                actorType = "system",
                sortDir = "asc",
            ).toFilter(organisationId, page = 0, size = 25)

        assertEquals(AuditOutcome.DENIED, filter.outcome)
        assertEquals(AuditSeverity.HIGH, filter.minSeverity)
        assertNull(filter.severity)
        assertEquals(AuditActorType.SYSTEM, filter.actorType)
        assertTrue(filter.ascending)
    }

    @Test
    fun `free values are carried unchanged`() {
        val branchId = UUID.randomUUID()
        val from = now.minus(Duration.ofDays(1))

        val filter =
            AuditSearchParameters(
                severity = "CRITICAL",
                branchId = branchId,
                actionPrefix = "branch.",
                q = "Approved",
                actorSubject = "kc-subject",
                occurredFrom = from,
                sortDir = "DESC",
            ).toFilter(organisationId, page = 0, size = 25)

        assertEquals(AuditSeverity.CRITICAL, filter.severity)
        assertEquals(branchId, filter.branchId)
        assertEquals("branch.", filter.actionPrefix)
        assertEquals("Approved", filter.q)
        assertEquals("kc-subject", filter.actorSubject)
        assertEquals(from, filter.occurredFrom)
        assertFalse(filter.ascending)
    }

    @ParameterizedTest
    @ValueSource(strings = ["outcome", "severity", "min_severity", "actor_type", "sort_dir"])
    fun `a value outside its closed set is rejected naming the parameter`(parameter: String) {
        val bogus = "bogus<script>"
        val parameters =
            when (parameter) {
                "outcome" -> AuditSearchParameters(outcome = bogus)
                "severity" -> AuditSearchParameters(severity = bogus)
                "min_severity" -> AuditSearchParameters(minSeverity = bogus)
                "actor_type" -> AuditSearchParameters(actorType = bogus)
                else -> AuditSearchParameters(sortDir = bogus)
            }

        val failure =
            assertFailsWith<InvalidPageRequestException> {
                parameters.toFilter(organisationId, page = 0, size = 25)
            }

        assertEquals(parameter, failure.parameter)
        assertFalse(failure.message.orEmpty().contains(bogus))
    }

    @Test
    fun `an empty closed-set value is rejected rather than ignored`() {
        val failure =
            assertFailsWith<InvalidPageRequestException> {
                AuditSearchParameters(outcome = "").toFilter(organisationId, 0, 25)
            }

        assertEquals("outcome", failure.parameter)
    }

    @Test
    fun `actor type is USER or SYSTEM only`() {
        val failure =
            assertFailsWith<InvalidPageRequestException> {
                AuditSearchParameters(actorType = "SERVICE_ACCOUNT").toFilter(organisationId, 0, 25)
            }

        assertEquals("actor_type", failure.parameter)
    }

    @Test
    fun `a valid filter passes validation`() {
        AuditEventFilter(
            organisationId,
            outcome = AuditOutcome.FAILURE,
            minSeverity = AuditSeverity.MEDIUM,
            actionPrefix = "br",
            q = "abc",
            occurredFrom = now.minus(Duration.ofDays(31)),
            actorSubject = "s",
        ).requireValid(now)
    }

    @Test
    fun `severity and min_severity together are rejected`() {
        assertRejected(
            "min_severity",
            AuditEventFilter(
                organisationId,
                severity = AuditSeverity.HIGH,
                minSeverity = AuditSeverity.HIGH,
            ),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 65, 200])
    fun `an action prefix outside 2 to 64 characters is rejected`(length: Int) {
        assertRejected(
            "action_prefix",
            AuditEventFilter(organisationId, actionPrefix = "a".repeat(length)),
        )
    }

    @Test
    fun `an action prefix of 2 and of 64 characters is accepted`() {
        AuditEventFilter(organisationId, actionPrefix = "ab").requireValid(now)
        AuditEventFilter(organisationId, actionPrefix = "a".repeat(64)).requireValid(now)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 65, 500])
    fun `a search text outside 3 to 64 characters is rejected`(length: Int) {
        assertRejected(
            "q",
            AuditEventFilter(
                organisationId,
                q = "q".repeat(length),
                occurredFrom = now.minus(Duration.ofDays(1)),
            ),
        )
    }

    @Test
    fun `a search text of 3 and of 64 characters is accepted`() {
        val from = now.minus(Duration.ofDays(1))
        AuditEventFilter(organisationId, q = "abc", occurredFrom = from).requireValid(now)
        AuditEventFilter(organisationId, q = "a".repeat(64), occurredFrom = from)
            .requireValid(now)
    }

    @Test
    fun `a search text needs occurred_from`() {
        assertRejected(
            "occurred_from",
            AuditEventFilter(organisationId, q = "abc", occurredTo = now),
        )
    }

    @Test
    fun `a search text window wider than 31 days is rejected`() {
        val to = Instant.parse("2026-03-01T00:00:00Z")
        assertRejected(
            "occurred_from",
            AuditEventFilter(
                organisationId,
                q = "abc",
                occurredFrom = to.minus(Duration.ofDays(31)).minusSeconds(1),
                occurredTo = to,
            ),
        )
        AuditEventFilter(
            organisationId,
            q = "abc",
            occurredFrom = to.minus(Duration.ofDays(31)),
            occurredTo = to,
        ).requireValid(now)
    }

    @Test
    fun `a search text window without occurred_to is measured to now`() {
        assertRejected(
            "occurred_from",
            AuditEventFilter(
                organisationId,
                q = "abc",
                occurredFrom = now.minus(Duration.ofDays(32)),
            ),
        )
    }

    @Test
    fun `a search text window that starts after its end is rejected`() {
        val to = Instant.parse("2026-03-01T00:00:00Z")
        assertRejected(
            "occurred_from",
            AuditEventFilter(
                organisationId,
                q = "abc",
                occurredFrom = to.plusSeconds(1),
                occurredTo = to,
            ),
        )
        // Without occurred_to the end is now: a future occurred_from is not a short window.
        assertRejected(
            "occurred_from",
            AuditEventFilter(
                organisationId,
                q = "abc",
                occurredFrom = now.plus(Duration.ofDays(1)),
            ),
        )
        AuditEventFilter(organisationId, q = "abc", occurredFrom = to, occurredTo = to)
            .requireValid(now)
    }

    @Test
    fun `occurred_from without a search text has no window limit`() {
        AuditEventFilter(organisationId, occurredFrom = Instant.EPOCH).requireValid(now)
    }

    @Test
    fun `a blank or over-long actor subject is rejected`() {
        assertRejected("actor_subject", AuditEventFilter(organisationId, actorSubject = " "))
        assertRejected(
            "actor_subject",
            AuditEventFilter(organisationId, actorSubject = "s".repeat(256)),
        )
        AuditEventFilter(organisationId, actorSubject = "s".repeat(255)).requireValid(now)
    }

    private fun assertRejected(
        parameter: String,
        filter: AuditEventFilter,
    ) {
        val failure = assertFailsWith<InvalidPageRequestException> { filter.requireValid(now) }
        assertEquals(parameter, failure.parameter)
    }
}
