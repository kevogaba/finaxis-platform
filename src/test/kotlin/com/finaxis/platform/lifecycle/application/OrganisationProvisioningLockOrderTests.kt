package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test

/**
 * Order of the organisation lock in every provisioning decision (issue #204). Each use case that
 * judges the bootstrap record or the organisation's state and then writes must take the
 * organisation lock before its first read of either, so that what it judged is what it writes
 * over. The interleaving itself is proved against Postgres in
 * `TenantApprovalReturnRaceIntegrationTests`; this pins the order for the paths that test cannot
 * hold still, return and reject among them.
 */
class OrganisationProvisioningLockOrderTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
    private val lifecyclePersistence = BootstrapLifecycleFake()
    private val transitionLogs = BootstrapTransitionLogCapture()
    private val store = spy(BootstrapProvisioningFake(lifecyclePersistence))
    private val adminBootstrapStore = spy(FakeInitialAdminBootstrapStore())
    private val organisations =
        OrganisationProvisioningService(
            FoundationLifecycleService(
                TransitionExecutor(clock, transitionLogs, BootstrapEventCapture()),
                lifecyclePersistence,
                lifecyclePersistence,
                lifecyclePersistence,
                AuditService(BootstrapAuditCapture(), clock),
            ),
            store,
            store,
            store,
            store,
            AuditService(BootstrapAuditCapture(), clock),
            adminBootstrapStore,
            mock(InitialAdministratorBootstrapService::class.java),
            mock(PermissionGuard::class.java),
            clock,
        )

    @Test
    fun `approve locks the organisation before it reads the bootstrap record`() {
        val organisationId = pendingTenant()

        organisations.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId, actorId = uuidV7()),
        )

        inOrder(store, adminBootstrapStore).apply {
            verify(store).lockOrganisation(organisationId)
            verify(adminBootstrapStore).find(organisationId)
        }
    }

    @Test
    fun `return locks the organisation before it reads the bootstrap record`() {
        val organisationId = pendingTenant()

        organisations.returnForChanges(
            ReturnOrganisationForChangesCommand(organisationId, "Typo.", uuidV7()),
        )

        inOrder(store, adminBootstrapStore).apply {
            verify(store).lockOrganisation(organisationId)
            verify(adminBootstrapStore).find(organisationId)
        }
    }

    @Test
    fun `reject locks the organisation before it rewrites the bootstrap record`() {
        val organisationId = pendingTenant()

        organisations.rejectProvisioning(
            RejectOrganisationProvisioningCommand(organisationId, "No.", actorId = uuidV7()),
        )

        inOrder(store, adminBootstrapStore).apply {
            verify(store).lockOrganisation(organisationId)
            verify(adminBootstrapStore).reject(organisationId)
        }
    }

    @Test
    fun `submit locks the organisation before it validates what it submits`() {
        val organisationId = draftTenant()
        val submitter = uuidV7()

        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = submitter),
        )

        inOrder(store, adminBootstrapStore).apply {
            verify(store).lockOrganisation(organisationId)
            verify(store).hasRequiredMetadata(organisationId)
            verify(adminBootstrapStore).find(organisationId)
            verify(adminBootstrapStore).submit(organisationId, submitter)
        }
    }

    @Test
    fun `amend locks the organisation before it reads the state it requires`() {
        val organisationId = draftTenant()

        organisations.amendDraft(
            AmendOrganisationDraftCommand(
                organisationId = organisationId,
                tenantCode = "amended-tenant",
                displayName = "Amended Tenant",
                legalName = null,
                registrationNumber = null,
                countryCode = "KE",
                baseCurrencyCode = "KES",
                timezone = "Africa/Nairobi",
                actorId = uuidV7(),
                requestId = uuidV7(),
                admin = administrator(),
            ),
        )

        inOrder(store).apply {
            verify(store).lockOrganisation(organisationId)
            verify(store).lifecycleState(organisationId)
            verify(store).amendDraft(any())
        }
    }

    private fun pendingTenant(): UUID =
        draftTenant().also { organisationId ->
            organisations.submitForApproval(
                SubmitOrganisationForApprovalCommand(organisationId, actorId = uuidV7()),
            )
            clearInvocations(store, adminBootstrapStore)
        }

    private fun draftTenant(): UUID {
        val organisationId = uuidV7()
        lifecyclePersistence.organisations[organisationId] =
            LifecycleAggregate(
                organisationId,
                OrganisationLifecycleState.DRAFT,
                "ORGANISATION",
                organisationId,
                0,
            )
        store.metadataComplete += organisationId
        store.businessDates[organisationId] = LocalDate.of(2026, 7, 14)
        adminBootstrapStore.createDraft(organisationId, administrator(), uuidV7())
        store.organisationStates[organisationId] = OrganisationLifecycleState.DRAFT
        clearInvocations(store, adminBootstrapStore)
        return organisationId
    }

    private fun administrator() =
        InitialAdministratorDraft(
            email = "admin@test.com",
            username = "admin",
            displayName = "Admin",
            phoneE164 = null,
            sendApplicationInvite = false,
        )
}
