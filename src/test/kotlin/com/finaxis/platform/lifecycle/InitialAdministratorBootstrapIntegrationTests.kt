package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapJobRequest
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapJobRequestHandler
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.application.KeycloakUserProvisioningJobRequest
import com.finaxis.platform.lifecycle.application.KeycloakUserProvisioningJobRequestHandler
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InitialAdministratorBootstrapIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val userProvisioningStore: UserProvisioningStore,
    private val bootstrapJobHandler: InitialAdministratorBootstrapJobRequestHandler,
    private val keycloakJobHandler: KeycloakUserProvisioningJobRequestHandler,
    private val outboxRecords: OutboxRecordRepository,
    private val dsl: DSLContext,
) {
    @MockitoBean
    private lateinit var identityProvisioningGateway: IdentityProvisioningGateway

    @Test
    @Suppress("LongMethod")
    fun `full initial administrator bootstrap integrates end to end`() {
        // Stub the identity provisioning gateway to simulate a successful Keycloak response
        val fakeKeycloakSubject = UUID.randomUUID().toString()
        whenever(identityProvisioningGateway.findOrCreateUser(any()))
            .thenReturn(KeycloakUserRef(subject = fakeKeycloakSubject, created = true))

        val makerId = uuidV7()
        val checkerId = uuidV7()
        val tenantCode = "bootstrap-${uuidV7()}"

        // Seed actor rows directly so that audit event FK constraints are satisfied
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, makerId)
            .set(USER_ACCOUNT.USERNAME, "maker-$tenantCode")
            .set(USER_ACCOUNT.EMAIL, "maker@$tenantCode.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Maker")
            .set(USER_ACCOUNT.STATUS, "DRAFT")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, checkerId)
            .set(USER_ACCOUNT.USERNAME, "checker-$tenantCode")
            .set(USER_ACCOUNT.EMAIL, "checker@$tenantCode.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Checker")
            .set(USER_ACCOUNT.STATUS, "DRAFT")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()

        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = tenantCode,
                        displayName = "Bootstrap Organisation",
                        legalName = "Bootstrap Organisation Limited",
                        registrationNumber = "BT-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = makerId,
                        admin =
                            InitialAdministratorDraft(
                                email = "admin@$tenantCode.test",
                                username = "admin-$tenantCode",
                                displayName = "Initial Admin",
                                phoneE164 = "+254700000000",
                                sendApplicationInvite = true,
                            ),
                    ),
                ).organisationId

        organisationProvisioningService.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId),
        )

        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(
                organisationId = organisationId,
                actorId = checkerId,
                reason = "Approve initial setup",
            ),
        )

        // 1. Verify status is QUEUED initially
        var record = adminBootstrapStore.find(organisationId)
        assertNotNull(record)
        assertEquals(InitialAdministratorBootstrapStatus.QUEUED, record.status)
        assertEquals(makerId, record.requestedBy)
        assertEquals(checkerId, record.approvedBy)

        // 2. Trigger the scheduled bootstrap JobRunr handler manually
        bootstrapJobHandler.run(InitialAdministratorBootstrapJobRequest(organisationId))

        // 3. Verify status transitioned to PROVISIONING_IDENTITY and references linked
        record = adminBootstrapStore.find(organisationId)!!
        assertEquals(InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY, record.status)
        assertNotNull(record.userId)
        assertNotNull(record.membershipId)
        assertNotNull(record.headOfficeId)
        assertNotNull(record.roleId)

        // 4. Verify local user invitation and approval are created
        // in PENDING_APPROVAL / PROVISIONING_IDP
        val userId = record.userId
        val membershipId = record.membershipId

        assertEquals(UserLifecycleState.PROVISIONING_IDP, userProvisioningStore.userStatus(userId))
        val membership = userProvisioningStore.membershipSnapshot(organisationId, membershipId)
        assertNotNull(membership)
        assertEquals(MembershipLifecycleState.PENDING_APPROVAL, membership.status)

        // 5. Trigger the Keycloak user provisioning JobRunr handler manually
        val dispatchKey = "$organisationId:$userId:KEYCLOAK_PROVISIONING"
        keycloakJobHandler.run(
            KeycloakUserProvisioningJobRequest(
                organisationId = organisationId,
                membershipId = membershipId,
                userId = userId,
                email = record.adminEmail,
                username = record.adminUsername,
                displayName = record.adminDisplayName,
                sendKeycloakInvite = true,
                dispatchKey = dispatchKey,
                actorId = checkerId,
            ),
        )

        // 6. Verify local FSM user transitions complete and bootstrap metadata completes
        assertEquals(UserLifecycleState.INVITED, userProvisioningStore.userStatus(userId))
        val updatedMembership =
            userProvisioningStore.membershipSnapshot(
                organisationId,
                membershipId,
            )
        assertNotNull(updatedMembership)
        assertEquals(MembershipLifecycleState.ACTIVE, updatedMembership.status)

        record = adminBootstrapStore.find(organisationId)!!
        assertEquals(InitialAdministratorBootstrapStatus.COMPLETED, record.status)

        // 7. Verify role assignments and branch assignments exist
        assertTrue(userProvisioningStore.hasActiveRoleAssignment(organisationId, userId))
        assertTrue(userProvisioningStore.hasActiveBranchAssignment(organisationId, userId))
    }
}
