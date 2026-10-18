package com.finaxis.platform.iam.application.query

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The derived invitation status (#274): a view over the membership and its dispatch row. */
class InvitationStatusTests {
    private val at = Instant.parse("2026-07-17T10:15:30Z")

    @Test
    fun `no dispatch is awaiting approval when pending and not required otherwise`() {
        assertEquals(
            InvitationStatus("AWAITING_APPROVAL", 0, null, null),
            deriveInvitationStatus("PENDING_APPROVAL", null),
        )
        assertEquals(
            InvitationStatus("NOT_REQUIRED", 0, null, null),
            deriveInvitationStatus("ACTIVE", null),
        )
    }

    @Test
    fun `a dispatch maps to in progress, failed or completed`() {
        assertEquals(
            "IN_PROGRESS",
            deriveInvitationStatus("PENDING_APPROVAL", dispatch("PENDING")).status,
        )
        assertEquals("COMPLETED", deriveInvitationStatus("ACTIVE", dispatch("SUCCEEDED")).status)
        assertEquals(
            "FAILED",
            deriveInvitationStatus("PENDING_APPROVAL", dispatch("FAILED")).status,
        )
    }

    @Test
    fun `a failure reports its closed code and never the text after it`() {
        val failed =
            deriveInvitationStatus(
                "PENDING_APPROVAL",
                dispatch("FAILED", "IDENTITY_PROVIDER_FAILED:IdentityProvisioningException"),
            )

        assertEquals("IDENTITY_PROVIDER_FAILED", failed.lastErrorCode)
        assertEquals(2, failed.attempts)
        assertEquals(at, failed.updatedAt)
    }

    @Test
    fun `a failure recorded before codes existed is unknown, and a raw message never leaks`() {
        val legacy =
            deriveInvitationStatus(
                "PENDING_APPROVAL",
                dispatch("FAILED", "Keycloak admin failed for ann@example.test"),
            )

        assertEquals("UNKNOWN", legacy.lastErrorCode)
    }

    @Test
    fun `only a declared code is reported, any other prefix is unknown`() {
        listOf("ERROR:boom", "SQLSTATE:23505", "OTHER_CODE:X").forEach { stored ->
            assertEquals(
                "UNKNOWN",
                deriveInvitationStatus("PENDING_APPROVAL", dispatch("FAILED", stored))
                    .lastErrorCode,
                stored,
            )
        }
        assertEquals(
            "CANCELLED",
            deriveInvitationStatus("REVOKED", dispatch("FAILED", "CANCELLED:MembershipRevoked"))
                .lastErrorCode,
        )
    }

    @Test
    fun `a failed dispatch behind an active or suspended member is completed, not failed`() {
        listOf("ACTIVE", "SUSPENDED").forEach { membership ->
            val derived =
                deriveInvitationStatus(membership, dispatch("FAILED", "INVALID_STATE:X"))
            assertEquals("COMPLETED", derived.status, membership)
            assertNull(derived.lastErrorCode, membership)
        }
        assertEquals(
            "FAILED",
            deriveInvitationStatus("REVOKED", dispatch("FAILED", "CANCELLED:X")).status,
        )
    }

    @Test
    fun `a code is reported only while the status is failed`() {
        assertNull(
            deriveInvitationStatus("ACTIVE", dispatch("SUCCEEDED", "CONFLICT:Old")).lastErrorCode,
        )
        assertNull(
            deriveInvitationStatus("PENDING_APPROVAL", dispatch("PENDING", "CONFLICT:X"))
                .lastErrorCode,
        )
    }

    private fun dispatch(
        status: String,
        lastError: String? = null,
    ) = InvitationDispatch(status, attempts = 2, lastError = lastError, updatedAt = at)
}
