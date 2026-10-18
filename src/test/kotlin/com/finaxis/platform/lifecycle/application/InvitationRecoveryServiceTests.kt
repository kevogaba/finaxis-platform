package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.JobRequeueOutcome
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.port.outbound.MembershipProvisioningSnapshot
import com.finaxis.platform.lifecycle.application.port.outbound.ResendTouch
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Retry and resend of an invitation (#274): permission first, then state, then one audit row. */
class InvitationRecoveryServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-17T10:15:30Z"), ZoneOffset.UTC)
    private val store = mock<UserProvisioningStore>()
    private val provisioning = mock<UserProvisioningService>()
    private val guard = mock<PermissionGuard>()
    private val published = mutableListOf<TransitionEvent>()
    private val audits = mutableListOf<AuditEvent>()
    private val service =
        InvitationRecoveryService(
            store,
            provisioning,
            guard,
            TransitionEventPublisher { published += it },
            AuditService(AuditEventRepository { audits += it }, clock),
            clock,
            Duration.ofMinutes(5),
        )
    private val tenant = uuidV7()
    private val membership = uuidV7()
    private val actor = uuidV7()

    @Test
    fun `a retry runs the failed job again and audits what happened to it`() {
        stubSnapshot(MembershipLifecycleState.PENDING_APPROVAL, UserLifecycleState.PROVISIONING_IDP)
        whenever(provisioning.requeueFailedProvisioning(any(), any()))
            .doReturn(JobRequeueOutcome.REQUEUED)

        service.retry(RetryInvitationCommand(tenant, membership, actor))

        val row = audits.single()
        assertEquals("user.invitation_retry", row.action)
        assertEquals("REQUEUED", row.metadata["job"])
        assertEquals(snapshot.userId.toString(), row.subjectUserId)
        verify(store).lockOrganisation(tenant)
    }

    @Test
    fun `a retry needs the permission first and an unapproved membership still provisioning`() {
        whenever(guard.requireTenantPermission(actor, tenant, "user.approve"))
            .doThrow(MissingPermissionException("user.approve"))
        assertFailsWith<MissingPermissionException> {
            service.retry(RetryInvitationCommand(tenant, membership, actor))
        }
        verifyNoInteractions(store)

        org.mockito.Mockito.reset(guard)
        stubSnapshot(MembershipLifecycleState.ACTIVE, UserLifecycleState.INVITED)
        assertFailsWith<ConflictException> {
            service.retry(RetryInvitationCommand(tenant, membership, actor))
        }
        stubSnapshot(MembershipLifecycleState.REVOKED, UserLifecycleState.PROVISIONING_IDP)
        assertFailsWith<ConflictException> {
            service.retry(RetryInvitationCommand(tenant, membership, actor))
        }
        verify(provisioning, never()).requeueFailedProvisioning(any(), any())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a resend queues one email job and audits the attempt`() {
        stubSnapshot(MembershipLifecycleState.ACTIVE, UserLifecycleState.INVITED)
        whenever(store.touchDispatchForResend(snapshot.userId, Duration.ofMinutes(5)))
            .doReturn(ResendTouch.Touched(dispatchKey(), 3))

        service.resend(ResendInvitationCommand(tenant, membership, actor))

        val event = assertIs<ExternalizedTransitionEvent>(published.single())
        assertEquals("finaxis.lifecycle.user.invitation-resend-requested", event.target)
        assertEquals("3", event.metadata["attempt"])
        assertEquals(dispatchKey(), event.metadata["dispatchKey"])
        assertEquals(snapshot.userId.toString(), event.metadata["userId"])
        assertTrue("email" !in event.metadata, "no personal data rides the event")
        assertEquals("user.invitation_resend", audits.single().action)
    }

    @Test
    fun `a resend inside the cooldown or to an account that is not invited is refused`() {
        stubSnapshot(MembershipLifecycleState.ACTIVE, UserLifecycleState.INVITED)
        whenever(store.touchDispatchForResend(any(), any())).doReturn(ResendTouch.TooSoon)
        val tooSoon =
            assertFailsWith<ConflictException> {
                service.resend(ResendInvitationCommand(tenant, membership, actor))
            }
        assertEquals(LifecycleErrorCodes.INVITATION_RESEND_TOO_SOON, tooSoon.code)

        stubSnapshot(MembershipLifecycleState.ACTIVE, UserLifecycleState.ACTIVE)
        val notInvited =
            assertFailsWith<ConflictException> {
                service.resend(ResendInvitationCommand(tenant, membership, actor))
            }
        assertEquals(LifecycleErrorCodes.INVITATION_RESEND_NOT_APPLICABLE, notInvited.code)
        assertTrue(published.isEmpty())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a resend to a revoked member or a dispatch that did not succeed is not applicable`() {
        listOf(MembershipLifecycleState.REVOKED, MembershipLifecycleState.SUSPENDED).forEach {
            stubSnapshot(it, UserLifecycleState.INVITED)
            val refused =
                assertFailsWith<ConflictException> {
                    service.resend(ResendInvitationCommand(tenant, membership, actor))
                }
            assertEquals(LifecycleErrorCodes.INVITATION_RESEND_NOT_APPLICABLE, refused.code)
        }
        verify(store, never()).touchDispatchForResend(any(), any())

        stubSnapshot(MembershipLifecycleState.ACTIVE, UserLifecycleState.INVITED)
        whenever(store.touchDispatchForResend(any(), any())).doReturn(ResendTouch.NoDispatch)
        val refused =
            assertFailsWith<ConflictException> {
                service.resend(ResendInvitationCommand(tenant, membership, actor))
            }
        assertEquals(LifecycleErrorCodes.INVITATION_RESEND_NOT_APPLICABLE, refused.code)
        assertTrue(published.isEmpty())
        assertTrue(audits.isEmpty())
    }

    @Test
    fun `a resend needs user invite first and an unknown membership is not found`() {
        whenever(store.membershipSnapshot(tenant, membership)).doReturn(null)
        assertFailsWith<ResourceNotFoundException> {
            service.resend(ResendInvitationCommand(tenant, membership, actor))
        }
        whenever(guard.requireTenantPermission(actor, tenant, "user.invite"))
            .doThrow(MissingPermissionException("user.invite"))
        assertFailsWith<MissingPermissionException> {
            service.resend(ResendInvitationCommand(tenant, membership, actor))
        }
    }

    private val snapshot =
        MembershipProvisioningSnapshot(
            id = membership,
            userId = uuidV7(),
            status = MembershipLifecycleState.ACTIVE,
            type = MembershipType.STAFF,
            email = "invitee@example.test",
            username = "invitee",
            displayName = "Invitee",
            userStatus = UserLifecycleState.INVITED,
            sendKeycloakInvite = true,
            sendApplicationInvite = false,
        )

    private fun stubSnapshot(
        membershipStatus: MembershipLifecycleState,
        userStatus: UserLifecycleState,
    ) {
        whenever(store.membershipSnapshot(tenant, membership))
            .doReturn(snapshot.copy(status = membershipStatus, userStatus = userStatus))
    }

    private fun dispatchKey() = keycloakDispatchKey(tenant, snapshot.userId)
}
