package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The self-access guard of #341 (ADR 0043): acting on someone else passes untouched; acting on your
 * own membership or account records one durable `DENIED`, `HIGH` row and is refused with its code.
 */
class SelfAccessGuardTests {
    private val clock = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC)
    private val audits = AuditCapture()
    private val guard = SelfAccessGuard(AuditService(audits, clock))
    private val organisationId = uuidV7()
    private val actor = uuidV7()
    private val membershipId = uuidV7()

    @Test
    fun `acting on someone else records nothing`() {
        guard.requireNotOwnMembership(attempt("membership.suspend"), membershipId, uuidV7())
        guard.requireNotOwnAccount(attempt("user_account.suspend"), uuidV7())

        assertTrue(audits.events.isEmpty())
    }

    @Test
    fun `acting on your own membership records one denied high row and throws`() {
        val refusal =
            assertFailsWith<ForbiddenOperationException> {
                guard.requireNotOwnMembership(
                    attempt("membership.revoke", "req-self"),
                    membershipId,
                    actor,
                )
            }

        assertEquals(LifecycleErrorCodes.SELF_MEMBERSHIP_CHANGE, refusal.code)
        assertEquals(
            "A member cannot suspend or revoke their own membership.",
            refusal.safeDetail,
        )
        val row = audits.events.single()
        assertEquals("membership.revoke", row.action)
        assertEquals(AuditOutcome.DENIED, row.outcome)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(LifecycleErrorCodes.SELF_MEMBERSHIP_CHANGE, row.reason)
        assertEquals("MEMBERSHIP", row.resourceType)
        assertEquals(membershipId.toString(), row.resourceId)
        assertEquals(actor.toString(), row.actorId)
        assertEquals(actor.toString(), row.subjectUserId)
        assertEquals(organisationId.toString(), row.tenantId)
        assertEquals("req-self", row.requestId)
    }

    @Test
    fun `acting on your own account records one denied high row and throws`() {
        val refusal =
            assertFailsWith<ForbiddenOperationException> {
                guard.requireNotOwnAccount(
                    attempt("user_account.start_deactivation", "req-account"),
                    actor,
                )
            }

        assertEquals(LifecycleErrorCodes.SELF_ACCOUNT_CHANGE, refusal.code)
        assertEquals("A user cannot suspend or deactivate their own account.", refusal.safeDetail)
        val row = audits.events.single()
        assertEquals("user_account.start_deactivation", row.action)
        assertEquals(AuditOutcome.DENIED, row.outcome)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(LifecycleErrorCodes.SELF_ACCOUNT_CHANGE, row.reason)
        assertEquals("USER_ACCOUNT", row.resourceType)
        assertEquals(actor.toString(), row.resourceId)
        assertEquals(actor.toString(), row.subjectUserId)
        assertEquals(organisationId.toString(), row.tenantId)
    }

    private fun attempt(
        action: String,
        requestId: String? = null,
    ) = SelfAccessAttempt(actor, organisationId, action, requestId)

    private class AuditCapture : AuditEventRepository {
        val events = mutableListOf<AuditEvent>()

        override fun save(event: AuditEvent) {
            events += event
        }
    }
}
