package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.InternalTransitionEvent
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rules of returning a pending tenant to draft (ADR 0029, 3c), against the shared provisioning
 * fakes: who may return, the order of the checks, what the bootstrap record becomes, and that
 * maker-checker and the initial-administrator refusal keep holding across the
 * return, amend, resubmit loop.
 */
class OrganisationReturnServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
    private val lifecyclePersistence = BootstrapLifecycleFake()
    private val events = BootstrapEventCapture()
    private val audits = BootstrapAuditCapture()
    private val transitionLogs = BootstrapTransitionLogCapture()
    private val store = BootstrapProvisioningFake(lifecyclePersistence)
    private val adminBootstrapStore = FakeInitialAdminBootstrapStore()
    private val permissionGuard = mock(PermissionGuard::class.java)
    private val organisations =
        OrganisationProvisioningService(
            FoundationLifecycleService(
                TransitionExecutor(clock, transitionLogs, events),
                lifecyclePersistence,
                lifecyclePersistence,
                lifecyclePersistence,
                AuditService(audits, clock),
            ),
            store,
            store,
            store,
            store,
            AuditService(audits, clock),
            adminBootstrapStore,
            mock(InitialAdministratorBootstrapService::class.java),
            permissionGuard,
            clock,
        )

    @Test
    fun `a checker returns a pending tenant to draft and the bootstrap record becomes a draft`() {
        val maker = uuidV7()
        val submitter = uuidV7()
        val checker = uuidV7()
        val organisationId = pendingTenant(maker, submitter)
        val before = adminBootstrapStore.records.getValue(organisationId)

        organisations.returnForChanges(
            ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, checker),
        )

        verify(permissionGuard).requirePlatformPermission(checker, "tenant.reject")
        val aggregate = lifecyclePersistence.organisations.getValue(organisationId)
        assertEquals(OrganisationLifecycleState.DRAFT, aggregate.state)
        assertEquals(RETURN_REASON, aggregate.transitionReason)
        val record = adminBootstrapStore.records.getValue(organisationId)
        assertEquals(InitialAdministratorBootstrapStatus.DRAFT, record.status)
        assertNull(record.submittedBy)
        assertNull(record.submittedAt)
        assertNull(record.approvedBy)
        assertNull(record.approvedAt)
        // The maker and the administrator block are the draft's own and stay untouched.
        assertEquals(maker, record.requestedBy)
        assertEquals(before.adminEmail, record.adminEmail)
        assertEquals(before.adminUsername, record.adminUsername)
        assertEquals(before.adminDisplayName, record.adminDisplayName)
        assertEquals(before.adminPhoneE164, record.adminPhoneE164)
        assertEquals(before.sendApplicationInvite, record.sendApplicationInvite)
        // The FSM writes the transition row and the audit row with the reason.
        val log = transitionLogs.logs.single { it.transition == "RETURN_FOR_CHANGES" }
        assertEquals("PENDING_APPROVAL", log.fromState)
        assertEquals("DRAFT", log.toState)
        assertEquals(RETURN_REASON, log.reason)
        val audit = audits.events.single { it.action == "organisation.return_for_changes" }
        assertEquals(RETURN_REASON, audit.reason)
        assertEquals(organisationId.toString(), audit.resourceId)
        // An internal event only: nothing for the outbox to externalize.
        val event = events.events.single { it.transition == "RETURN_FOR_CHANGES" }
        assertIs<InternalTransitionEvent>(event)
    }

    @Test
    fun `the requester cannot return their own pending tenant`() {
        val maker = uuidV7()
        val submitter = uuidV7()
        val organisationId = pendingTenant(maker, submitter)

        assertFailsWith<ForbiddenOperationException> {
            organisations.returnForChanges(
                ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, maker),
            )
        }

        assertUntouchedPending(organisationId, submitter)
    }

    @Test
    fun `the submitter cannot return the tenant they submitted`() {
        val submitter = uuidV7()
        val organisationId = pendingTenant(uuidV7(), submitter)

        assertFailsWith<ForbiddenOperationException> {
            organisations.returnForChanges(
                ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, submitter),
            )
        }

        assertUntouchedPending(organisationId, submitter)
    }

    @Test
    fun `the system actor cannot return a tenant`() {
        val submitter = uuidV7()
        val organisationId = pendingTenant(uuidV7(), submitter)

        // Both sentinels: the runtime audit actor and the bootstrap nil id.
        listOf(SystemActor.ID, UUID(0L, 0L)).forEach { system ->
            assertFailsWith<InvalidOperationException> {
                organisations.returnForChanges(
                    ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, system),
                )
            }
        }

        assertUntouchedPending(organisationId, submitter)
    }

    @Test
    fun `a missing tenant permission is refused before any existence signal`() {
        val organisationId = pendingTenant(uuidV7(), uuidV7())
        val caller = uuidV7()
        doThrow(ForbiddenOperationException())
            .whenever(permissionGuard)
            .requirePlatformPermission(caller, "tenant.reject")

        // A real tenant and an unknown id answer alike: 403, never 404.
        listOf(organisationId, uuidV7()).forEach { target ->
            assertFailsWith<ForbiddenOperationException> {
                organisations.returnForChanges(
                    ReturnOrganisationForChangesCommand(target, RETURN_REASON, caller),
                )
            }
        }
        assertEquals(
            OrganisationLifecycleState.PENDING_APPROVAL,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
    }

    @Test
    fun `an unknown tenant and the platform organisation are not found`() {
        listOf(uuidV7(), PlatformOrganisation.ID).forEach { target ->
            assertFailsWith<ResourceNotFoundException> {
                organisations.returnForChanges(
                    ReturnOrganisationForChangesCommand(target, RETURN_REASON, uuidV7()),
                )
            }
        }
    }

    @Test
    fun `only a pending tenant can be returned and the record is left alone otherwise`() {
        OrganisationLifecycleState.entries
            .filter { it != OrganisationLifecycleState.PENDING_APPROVAL }
            .forEach { state ->
                val submitter = uuidV7()
                val organisationId = pendingTenant(uuidV7(), submitter)
                lifecyclePersistence.organisations.getValue(organisationId).state = state

                assertFailsWith<ConflictException>(state.name) {
                    organisations.returnForChanges(
                        ReturnOrganisationForChangesCommand(
                            organisationId,
                            RETURN_REASON,
                            uuidV7(),
                        ),
                    )
                }

                assertEquals(
                    state,
                    lifecyclePersistence.organisations.getValue(organisationId).state,
                )
                val record = adminBootstrapStore.records.getValue(organisationId)
                assertEquals(submitter, record.submittedBy, state.name)
                assertEquals(
                    InitialAdministratorBootstrapStatus.PENDING_ACTIVATION,
                    record.status,
                    state.name,
                )
                assertTrue(
                    audits.events.none {
                        it.action == "organisation.return_for_changes" &&
                            it.resourceId == organisationId.toString() &&
                            it.outcome == AuditOutcome.SUCCESS
                    },
                )
            }
    }

    @Test
    fun `a returned tenant cannot be approved until it is resubmitted`() {
        val organisationId = pendingTenant(uuidV7(), uuidV7())
        organisations.returnForChanges(
            ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, uuidV7()),
        )

        assertFailsWith<ConflictException> {
            organisations.approveProvisioning(
                ApproveOrganisationProvisioningCommand(organisationId, actorId = uuidV7()),
            )
        }

        assertEquals(
            OrganisationLifecycleState.DRAFT,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
    }

    @Test
    fun `maker checker holds across the return amend resubmit loop`() {
        val maker = uuidV7()
        val firstSubmitter = uuidV7()
        val returner = uuidV7()
        val resubmitter = uuidV7()
        val organisationId = pendingTenant(maker, firstSubmitter)
        organisations.returnForChanges(
            ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, returner),
        )
        // The maker's amendment replaces the administrator block of the returned draft.
        store.organisationStates[organisationId] = OrganisationLifecycleState.DRAFT
        organisations.amendDraft(amendCommand(organisationId, maker, "amended@test.com"))
        assertEquals(
            "amended@test.com",
            adminBootstrapStore.records.getValue(organisationId).adminEmail,
        )
        assertEquals(
            InitialAdministratorBootstrapStatus.DRAFT,
            adminBootstrapStore.records.getValue(organisationId).status,
        )

        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = resubmitter),
        )

        // The submitter is the actor of the current submission, and the requester is unchanged.
        val resubmitted = adminBootstrapStore.records.getValue(organisationId)
        assertEquals(resubmitter, resubmitted.submittedBy)
        assertEquals(maker, resubmitted.requestedBy)
        assertEquals(InitialAdministratorBootstrapStatus.PENDING_ACTIVATION, resubmitted.status)
        listOf(maker, resubmitter).forEach { actor ->
            assertFailsWith<ForbiddenOperationException> {
                organisations.approveProvisioning(
                    ApproveOrganisationProvisioningCommand(organisationId, actorId = actor),
                )
            }
        }
        // The first submitter is no longer barred: it is not the submitter of the current
        // request, so it may approve a resubmission by someone else (ADR 0029 M4, an accepted
        // consequence of reading the rule from the record at approval).
        organisations.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId, actorId = firstSubmitter),
        )
        val approved = adminBootstrapStore.records.getValue(organisationId)
        assertEquals(InitialAdministratorBootstrapStatus.QUEUED, approved.status)
        assertEquals(firstSubmitter, approved.approvedBy)
        assertEquals(
            OrganisationLifecycleState.ACTIVE,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
    }

    @Test
    fun `the checker who returned a tenant may approve its resubmission`() {
        val returner = uuidV7()
        val organisationId = pendingTenant(uuidV7(), uuidV7())
        organisations.returnForChanges(
            ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, returner),
        )
        store.organisationStates[organisationId] = OrganisationLifecycleState.DRAFT
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = uuidV7()),
        )

        // Returners and amenders are not makers (ADR 0029, 3c): two other people still stand
        // behind the approval, the requester and the new submitter.
        organisations.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId, actorId = returner),
        )

        assertEquals(
            returner,
            adminBootstrapStore.records.getValue(organisationId).approvedBy,
        )
    }

    @Test
    fun `an administrator amended after a return is read at approval not cached`() {
        val maker = uuidV7()
        val returner = uuidV7()
        val organisationId = pendingTenant(maker, uuidV7())
        organisations.returnForChanges(
            ReturnOrganisationForChangesCommand(organisationId, RETURN_REASON, returner),
        )
        store.organisationStates[organisationId] = OrganisationLifecycleState.DRAFT
        organisations.amendDraft(amendCommand(organisationId, maker, "returner@test.com"))
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = uuidV7()),
        )
        // The amended administrator is the account of the checker who returned it.
        adminBootstrapStore.existingAdministrators[organisationId] = returner

        val refusal =
            assertFailsWith<ForbiddenOperationException> {
                organisations.approveProvisioning(
                    ApproveOrganisationProvisioningCommand(organisationId, actorId = returner),
                )
            }

        assertEquals(LifecycleErrorCodes.APPROVER_IS_INITIAL_ADMINISTRATOR, refusal.code)
        assertEquals(
            OrganisationLifecycleState.PENDING_APPROVAL,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
    }

    /** A tenant draft made by [maker] and submitted by [submitter], so it is pending approval. */
    private fun pendingTenant(
        maker: UUID,
        submitter: UUID,
    ): UUID {
        val organisationId = draftBy(maker)
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = submitter),
        )
        return organisationId
    }

    private fun assertUntouchedPending(
        organisationId: UUID,
        submitter: UUID,
    ) {
        assertEquals(
            OrganisationLifecycleState.PENDING_APPROVAL,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        val record = adminBootstrapStore.records.getValue(organisationId)
        assertEquals(submitter, record.submittedBy)
        assertEquals(InitialAdministratorBootstrapStatus.PENDING_ACTIVATION, record.status)
        assertTrue(transitionLogs.logs.none { it.transition == "RETURN_FOR_CHANGES" })
    }

    private fun amendCommand(
        organisationId: UUID,
        actorId: UUID,
        adminEmail: String,
    ) = AmendOrganisationDraftCommand(
        organisationId = organisationId,
        tenantCode = "amended-tenant",
        displayName = "Amended Tenant",
        legalName = null,
        registrationNumber = null,
        countryCode = "KE",
        baseCurrencyCode = "KES",
        timezone = "Africa/Nairobi",
        actorId = actorId,
        requestId = uuidV7(),
        admin =
            InitialAdministratorDraft(
                email = adminEmail,
                username = "amendedadmin",
                displayName = "Amended Admin",
                phoneE164 = null,
                sendApplicationInvite = false,
            ),
    )

    private fun draftBy(maker: UUID): UUID {
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
        adminBootstrapStore.createDraft(
            organisationId = organisationId,
            admin =
                InitialAdministratorDraft(
                    email = "admin@test.com",
                    username = "admin",
                    displayName = "Admin",
                    phoneE164 = null,
                    sendApplicationInvite = false,
                ),
            requestedBy = maker,
        )
        return organisationId
    }
}

private const val RETURN_REASON = "Registration number has a typo."
