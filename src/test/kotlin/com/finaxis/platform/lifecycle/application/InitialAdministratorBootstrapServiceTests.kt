package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

@Suppress("LongMethod")
class InitialAdministratorBootstrapServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
    private val adminBootstrapStore = mock<InitialAdministratorBootstrapStore>()
    private val bootstrapStore = mock<OrganisationBootstrapStore>()
    private val userProvisioningStore = mock<UserProvisioningStore>()
    private val userProvisioningService = mock<UserProvisioningService>()
    private val eventPublisher = mock<TransitionEventPublisher>()
    private val failureRecorder = mock<InitialAdministratorBootstrapFailureRecorder>()
    private val asynchronousApproval =
        UserApprovalResult(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UserLifecycleState.PROVISIONING_IDP,
            MembershipLifecycleState.PENDING_APPROVAL,
            keycloakProvisioningRequested = true,
            applicationInviteRequested = false,
        )
    private val service =
        InitialAdministratorBootstrapService(
            adminBootstrapStore,
            bootstrapStore,
            userProvisioningStore,
            userProvisioningService,
            eventPublisher,
            failureRecorder,
            clock,
        )

    private val orgId = UUID.randomUUID()
    private val requestedBy = UUID.randomUUID()
    private val approvedBy = UUID.randomUUID()

    init {
        whenever(userProvisioningService.approveUser(any())).thenReturn(asynchronousApproval)
    }

    @Test
    fun `throws when bootstrap record is not found`() {
        whenever(adminBootstrapStore.find(orgId)).thenReturn(null)

        assertThrows<IllegalArgumentException> {
            service.bootstrap(orgId)
        }
    }

    @Test
    fun `returns immediately when status is already COMPLETED`() {
        val record = createRecord(status = InitialAdministratorBootstrapStatus.COMPLETED)
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)

        service.bootstrap(orgId)

        verify(adminBootstrapStore).find(orgId)
        verifyNoMoreInteractions(adminBootstrapStore)
        verifyNoInteractions(bootstrapStore)
        verifyNoInteractions(userProvisioningStore)
        verifyNoInteractions(userProvisioningService)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `executes full bootstrap flow when local references are absent`() {
        val record = createRecord(status = InitialAdministratorBootstrapStatus.QUEUED)
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)

        val branchId = UUID.randomUUID()
        whenever(bootstrapStore.ensureHeadOfficeDraft(orgId))
            .thenReturn(HeadOfficeDraftResult(branchId, created = true))

        val roleId = UUID.randomUUID()
        whenever(userProvisioningStore.findRoleIdByCode(orgId, "TENANT_ADMIN")).thenReturn(roleId)

        val newUserId = UUID.randomUUID()
        val newMembershipId = UUID.randomUUID()
        whenever(userProvisioningService.inviteUser(any())).thenReturn(
            UserInvitationResult(
                newUserId,
                newMembershipId,
                UserLifecycleState.DRAFT,
                MembershipLifecycleState.PENDING_APPROVAL,
            ),
        )

        val inviteKey = "$orgId:$newUserId:KEYCLOAK_PROVISIONING"
        whenever(userProvisioningStore.dispatchStatus(inviteKey)).thenReturn(null)

        service.bootstrap(orgId)

        verify(adminBootstrapStore).updateStatus(
            eq(orgId),
            eq(InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY),
            isNull(),
            eq(true),
        )

        val inviteCaptor = argumentCaptor<InviteUserCommand>()
        verify(userProvisioningService).inviteUser(inviteCaptor.capture())
        val inviteCmd = inviteCaptor.firstValue
        assertEquals(orgId, inviteCmd.organisationId)
        assertEquals(record.adminEmail, inviteCmd.email)
        assertEquals(record.adminUsername, inviteCmd.username)
        assertEquals(record.adminDisplayName, inviteCmd.displayName)
        assertEquals(record.adminPhoneE164, inviteCmd.phoneE164)
        assertEquals(MembershipType.ADMIN, inviteCmd.membershipType)
        assertEquals(branchId, inviteCmd.primaryBranchId)
        assertEquals(1, inviteCmd.branchAssignments.size)
        assertEquals(branchId, inviteCmd.branchAssignments[0].branchId)
        assertEquals(BranchAssignmentType.HOME, inviteCmd.branchAssignments[0].assignmentType)
        assertEquals(1, inviteCmd.roleAssignments.size)
        assertEquals(roleId, inviteCmd.roleAssignments[0].roleId)
        assertEquals(RoleAssignmentScopeType.TENANT, inviteCmd.roleAssignments[0].scopeType)
        assertEquals(SystemActor.ID, inviteCmd.invitedBy)
        assertEquals(true, inviteCmd.sendKeycloakInvite)
        assertEquals(record.sendApplicationInvite, inviteCmd.sendApplicationInvite)
        assertEquals("BOOTSTRAP-$orgId", inviteCmd.requestId)
        assertEquals("BOOTSTRAP-$orgId", inviteCmd.bootstrapRequestId)
        assertEquals(1, inviteCmd.bootstrapAttempt)

        val approveCaptor = argumentCaptor<ApproveUserCommand>()
        verify(userProvisioningService).approveUser(approveCaptor.capture())
        val approveCmd = approveCaptor.firstValue
        assertEquals(orgId, approveCmd.organisationId)
        assertEquals(newMembershipId, approveCmd.membershipId)
        assertEquals(record.approvedBy, approveCmd.approvedBy)
        assertEquals("BOOTSTRAP-$orgId", approveCmd.requestId)
        assertEquals("BOOTSTRAP-$orgId", approveCmd.bootstrapRequestId)
        assertEquals(1, approveCmd.bootstrapAttempt)
    }

    @Test
    fun `resumes bootstrap and skips invite when local references are present but not approved`() {
        val existingUserId = UUID.randomUUID()
        val existingMembershipId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        val roleId = UUID.randomUUID()

        val record =
            createRecord(
                status = InitialAdministratorBootstrapStatus.QUEUED,
                userId = existingUserId,
                membershipId = existingMembershipId,
                headOfficeId = branchId,
                roleId = roleId,
            )
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)

        val inviteKey = "$orgId:$existingUserId:KEYCLOAK_PROVISIONING"
        whenever(userProvisioningStore.dispatchStatus(inviteKey)).thenReturn(null)

        service.bootstrap(orgId)

        verify(userProvisioningService, never()).inviteUser(any())

        val approveCaptor = argumentCaptor<ApproveUserCommand>()
        verify(userProvisioningService).approveUser(approveCaptor.capture())
        val approveCmd = approveCaptor.firstValue
        assertEquals(orgId, approveCmd.organisationId)
        assertEquals(existingMembershipId, approveCmd.membershipId)
        assertEquals(record.approvedBy, approveCmd.approvedBy)
    }

    @Test
    fun `republishes event when identity dispatch exists but is not succeeded`() {
        val existingUserId = UUID.randomUUID()
        val existingMembershipId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        val roleId = UUID.randomUUID()

        val record =
            createRecord(
                status = InitialAdministratorBootstrapStatus.FAILED,
                userId = existingUserId,
                membershipId = existingMembershipId,
                headOfficeId = branchId,
                roleId = roleId,
            )
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)

        val inviteKey = "$orgId:$existingUserId:KEYCLOAK_PROVISIONING"
        whenever(userProvisioningStore.dispatchStatus(inviteKey)).thenReturn("FAILED")

        service.bootstrap(orgId)

        verify(adminBootstrapStore).find(orgId)
        verifyNoInteractions(userProvisioningService)

        val eventCaptor = argumentCaptor<ExternalizedTransitionEvent>()
        verify(eventPublisher).publish(eventCaptor.capture())
        val event = eventCaptor.firstValue

        assertEquals("finaxis.lifecycle.user.keycloak-provisioning-requested", event.target)
        assertEquals("USER_ACCOUNT", event.aggregateType)
        assertEquals(existingUserId.toString(), event.aggregateId)
        assertEquals("KEYCLOAK_PROVISIONING_REQUESTED", event.transition)
        assertEquals("PROVISIONING_IDP", event.fromState)
        assertEquals("PROVISIONING_IDP", event.toState)
        assertEquals("USER", event.actor.type)
        assertEquals(record.approvedBy.toString(), event.actor.id)
        assertEquals(orgId.toString(), event.metadata["organisationId"])
        assertEquals(existingMembershipId.toString(), event.metadata["membershipId"])
        assertEquals(existingUserId.toString(), event.metadata["userId"])
        assertEquals(record.adminEmail, event.metadata["email"])
        assertEquals(record.adminUsername, event.metadata["username"])
        assertEquals(record.adminDisplayName, event.metadata["displayName"])
        assertEquals("true", event.metadata["sendKeycloakInvite"])
        assertEquals(inviteKey, event.metadata["dispatchKey"])
        assertEquals("BOOTSTRAP-$orgId", event.metadata["bootstrapRequestId"])
        assertEquals(1, event.metadata["bootstrapAttempt"])
    }

    @Test
    fun `completes bootstrap when approval needs no Keycloak dispatch`() {
        val userId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val record =
            createRecord(
                status = InitialAdministratorBootstrapStatus.QUEUED,
                userId = userId,
                membershipId = membershipId,
                headOfficeId = UUID.randomUUID(),
                roleId = UUID.randomUUID(),
            )
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)
        whenever(userProvisioningStore.dispatchStatus("$orgId:$userId:KEYCLOAK_PROVISIONING"))
            .thenReturn(null)
        whenever(userProvisioningService.approveUser(any())).thenReturn(
            UserApprovalResult(
                userId,
                membershipId,
                UserLifecycleState.ACTIVE,
                MembershipLifecycleState.ACTIVE,
                keycloakProvisioningRequested = false,
                applicationInviteRequested = false,
            ),
        )

        service.bootstrap(orgId)

        verify(adminBootstrapStore).updateStatus(
            eq(orgId),
            eq(InitialAdministratorBootstrapStatus.COMPLETED),
            isNull(),
            eq(false),
        )
    }

    @Test
    fun `completes bootstrap when identity dispatch already succeeded`() {
        val userId = UUID.randomUUID()
        val record =
            createRecord(
                status = InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY,
                userId = userId,
                membershipId = UUID.randomUUID(),
                headOfficeId = UUID.randomUUID(),
                roleId = UUID.randomUUID(),
            )
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)
        whenever(userProvisioningStore.dispatchStatus("$orgId:$userId:KEYCLOAK_PROVISIONING"))
            .thenReturn("SUCCEEDED")

        service.bootstrap(orgId)

        verify(adminBootstrapStore).updateStatus(
            eq(orgId),
            eq(InitialAdministratorBootstrapStatus.COMPLETED),
            isNull(),
            eq(false),
        )
        verifyNoInteractions(userProvisioningService)
    }

    @Test
    fun `records failure independently on exceptions`() {
        val record = createRecord(status = InitialAdministratorBootstrapStatus.QUEUED)
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)

        val errorMsg = "A".repeat(120)
        whenever(bootstrapStore.ensureHeadOfficeDraft(orgId))
            .thenThrow(RuntimeException(errorMsg))

        val exception =
            assertThrows<RuntimeException> {
                service.bootstrap(orgId)
            }
        assertEquals(errorMsg, exception.message)

        val failureCaptor = argumentCaptor<Throwable>()
        verify(failureRecorder).recordFailure(eq(orgId), failureCaptor.capture())
        assertEquals("A".repeat(120), failureCaptor.firstValue.message)
    }

    @Test
    fun `completeBootstrapIfCorrelated updates status to COMPLETED on matching details`() {
        val userId = UUID.randomUUID()
        val record =
            createRecord(
                status = InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY,
                userId = userId,
            )
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)

        service.completeBootstrapIfCorrelated(orgId, userId)

        verify(adminBootstrapStore).updateStatus(
            eq(orgId),
            eq(InitialAdministratorBootstrapStatus.COMPLETED),
            isNull(),
            eq(false),
        )
    }

    @Test
    fun `completeBootstrapIfCorrelated does nothing when userId does not match`() {
        val userId = UUID.randomUUID()
        val differentUserId = UUID.randomUUID()
        val record =
            createRecord(
                status = InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY,
                userId = userId,
            )
        whenever(adminBootstrapStore.find(orgId)).thenReturn(record)

        service.completeBootstrapIfCorrelated(orgId, differentUserId)

        verify(adminBootstrapStore).find(orgId)
        verifyNoMoreInteractions(adminBootstrapStore)
        verifyNoInteractions(userProvisioningService)
        verifyNoInteractions(eventPublisher)
    }

    private fun createRecord(
        status: InitialAdministratorBootstrapStatus,
        userId: UUID? = null,
        membershipId: UUID? = null,
        headOfficeId: UUID? = null,
        roleId: UUID? = null,
    ) = InitialAdministratorBootstrapRecord(
        organisationId = orgId,
        adminEmail = "admin@example.test",
        adminUsername = "admin",
        adminDisplayName = "Admin User",
        adminPhoneE164 = "+254700000000",
        sendApplicationInvite = false,
        status = status,
        attempts = 0,
        requestedBy = requestedBy,
        submittedBy = requestedBy,
        approvedBy = approvedBy,
        userId = userId,
        membershipId = membershipId,
        headOfficeId = headOfficeId,
        roleId = roleId,
        lastFailureCode = null,
        createdAt = clock.instant(),
        submittedAt = clock.instant(),
        approvedAt = clock.instant(),
        updatedAt = clock.instant(),
        rowVersion = 1L,
    )
}
