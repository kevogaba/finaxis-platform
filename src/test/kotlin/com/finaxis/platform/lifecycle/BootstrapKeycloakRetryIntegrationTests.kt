package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.deterministicJobId
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.DecisionRemark
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RetryInitialAdministratorBootstrapCommand
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserProvisioningRequest
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.jobs.states.StateName
import org.jobrunr.jobs.states.SucceededState
import org.jobrunr.storage.JobNotFoundException
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A retry of a failed initial-administrator bootstrap re-runs the administrator's Keycloak job
 * under its own id (#162). The administrator's first attempt fails once, which is terminal here
 * (retries are off), so the bootstrap records FAILED. The platform retry then requeues that same
 * job, which succeeds and completes the bootstrap.
 *
 * It closes its context with the class, so its JobRunr server does not outlive the test (fix
 * round 2).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.dashboard.enabled=false",
        "jobrunr.jobs.default-number-of-retries=0",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BootstrapKeycloakRetryIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val storageProvider: StorageProvider,
    private val dsl: DSLContext,
) {
    @MockitoBean
    private lateinit var identityProvisioningGateway: IdentityProvisioningGateway

    @Test
    fun `retrying a bootstrap whose administrator job failed runs that same job again`() {
        val makerId = uuidV7()
        val checkerId = uuidV7()
        val tenantCode = "bootstrap-retry-${uuidV7()}"
        val adminEmail = "admin@$tenantCode.test"
        seedActor(makerId, "maker-$tenantCode", "maker@$tenantCode.test", "Maker")
        seedActor(checkerId, "checker-$tenantCode", "checker@$tenantCode.test", "Checker")
        val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        fixture.grantPlatformSuperAdmin(makerId)
        fixture.grantPlatformSuperAdmin(checkerId)
        stubGateway(adminEmail)
        val organisationId = queueBootstrap(tenantCode, adminEmail, makerId, checkerId)

        await()
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(500))
            .until { bootstrapStatus(organisationId) == InitialAdministratorBootstrapStatus.FAILED }
        val record = assertNotNull(adminBootstrapStore.find(organisationId))
        val jobId = deterministicJobId("$organisationId:${record.userId}:KEYCLOAK_PROVISIONING")
        // The bootstrap is recorded FAILED before JobRunr saves the job FAILED, so await the job.
        awaitJobState(jobId, StateName.FAILED)

        withRequestContext {
            organisationProvisioningService.retryBootstrap(
                RetryInitialAdministratorBootstrapCommand(
                    organisationId,
                    PlatformCaller(checkerId, PlatformOrganisation.ID),
                ),
            )
        }

        awaitJobState(jobId, StateName.SUCCEEDED)
        await()
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(500))
            .until {
                bootstrapStatus(organisationId) == InitialAdministratorBootstrapStatus.COMPLETED
            }
        val job = storageProvider.getJobById(jobId)
        assertEquals(1, job.jobStates.count { it is FailedState }, "the first attempt failed")
        assertEquals(1, job.jobStates.count { it is SucceededState }, "the retry succeeded")
        verify(identityProvisioningGateway, times(2)).findOrCreateUser(requestFor(adminEmail))
    }

    /** Creates, submits and approves an organisation whose bootstrap is queued for [adminEmail]. */
    private fun queueBootstrap(
        tenantCode: String,
        adminEmail: String,
        makerId: UUID,
        checkerId: UUID,
    ): UUID {
        val organisationId =
            withRequestContext {
                organisationProvisioningService
                    .createDraft(
                        CreateOrganisationDraftCommand(
                            tenantCode = tenantCode,
                            displayName = "Bootstrap Retry Organisation",
                            legalName = "Bootstrap Retry Organisation Limited",
                            registrationNumber = "BR-${uuidV7()}",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            requestedBy = makerId,
                            admin =
                                InitialAdministratorDraft(
                                    email = adminEmail,
                                    username = "admin-$tenantCode",
                                    displayName = "Initial Admin",
                                    phoneE164 = "+254700000000",
                                ),
                        ),
                    ).organisationId
            }
        withRequestContext {
            organisationProvisioningService.submitForApproval(
                SubmitOrganisationForApprovalCommand(organisationId, actorId = makerId),
            )
            organisationProvisioningService.approveProvisioning(
                ApproveOrganisationProvisioningCommand(
                    organisationId = organisationId,
                    actorId = checkerId,
                    reason = DecisionRemark.optional("Approve initial setup"),
                ),
            )
        }
        return organisationId
    }

    /** Every identity but [adminEmail] succeeds; the administrator's first attempt fails once. */
    private fun stubGateway(adminEmail: String) {
        whenever(identityProvisioningGateway.findOrCreateUser(any()))
            .thenReturn(KeycloakUserRef(subject = UUID.randomUUID().toString(), created = true))
        whenever(identityProvisioningGateway.findOrCreateUser(requestFor(adminEmail)))
            .thenThrow(IdentityProvisioningException("Keycloak is unreachable"))
            .thenReturn(KeycloakUserRef(subject = UUID.randomUUID().toString(), created = true))
    }

    private fun bootstrapStatus(organisationId: UUID): InitialAdministratorBootstrapStatus? =
        adminBootstrapStore.find(organisationId)?.status

    private fun requestFor(email: String): KeycloakUserProvisioningRequest =
        argThat { this.email == email }

    private fun awaitJobState(
        jobId: UUID,
        state: StateName,
    ) {
        await()
            .ignoreException(JobNotFoundException::class.java)
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(500))
            .until { storageProvider.getJobById(jobId).state == state }
    }

    private fun seedActor(
        id: UUID,
        username: String,
        email: String,
        displayName: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
            .set(USER_ACCOUNT.STATUS, "DRAFT")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
    }
}
