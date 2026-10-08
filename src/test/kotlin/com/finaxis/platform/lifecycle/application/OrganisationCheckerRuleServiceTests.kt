package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionExecutor
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.LifecycleAggregate
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tenant checker rule (#221), the tenant mirror of the branch amender rule (ADR 0028,
 * decision 5): neither the maker (the requester of the draft or the submitter of the current
 * submission) nor anyone who amended the draft may approve or reject a pending tenant. Each
 * refusal is a named `403` and leaves a `DENIED` audit row that the refusal's rollback cannot take.
 */
class OrganisationCheckerRuleServiceTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
    private val lifecyclePersistence = BootstrapLifecycleFake()
    private val audits = BootstrapAuditCapture()
    private val transitionLogs = BootstrapTransitionLogCapture()
    private val store = BootstrapProvisioningFake(lifecyclePersistence)
    private val adminBootstrapStore = FakeInitialAdminBootstrapStore()
    private val organisations =
        OrganisationProvisioningService(
            FoundationLifecycleService(
                TransitionExecutor(clock, transitionLogs, BootstrapEventCapture()),
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
            mock(PermissionGuard::class.java),
            clock,
        )

    @Test
    fun `neither the requester nor the submitter can approve, and each refusal is audited`() {
        val maker = uuidV7()
        val submitter = uuidV7()
        val organisationId = pendingTenant(maker, submitter)

        listOf(maker, submitter).forEach { actor ->
            val refusal =
                assertFailsWith<ForbiddenOperationException> { approve(organisationId, actor) }

            assertEquals(LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER, refusal.code)
            assertEquals(LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER_DETAIL, refusal.safeDetail)
            assertDenied(
                organisationId,
                actor,
                APPROVE,
                LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER,
            )
        }
        assertStillPending(organisationId, submitter)
    }

    @Test
    fun `neither the requester nor the submitter can reject their own tenant`() {
        val maker = uuidV7()
        val submitter = uuidV7()
        val organisationId = pendingTenant(maker, submitter)

        listOf(maker, submitter).forEach { actor ->
            val refusal =
                assertFailsWith<ForbiddenOperationException> { reject(organisationId, actor) }

            assertEquals(LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER, refusal.code)
            assertDenied(
                organisationId,
                actor,
                REJECT,
                LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER,
            )
        }
        assertStillPending(organisationId, submitter)
        assertTrue(transitionLogs.logs.none { it.transition == "REJECT" })
    }

    @Test
    fun `the maker refused on return is named and audited like approve and reject`() {
        val maker = uuidV7()
        val submitter = uuidV7()
        val organisationId = pendingTenant(maker, submitter)

        val refusal =
            assertFailsWith<ForbiddenOperationException> { returnTenant(organisationId, maker) }

        assertEquals(LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER, refusal.code)
        assertDenied(organisationId, maker, RETURN, LifecycleErrorCodes.APPROVER_IS_TENANT_MAKER)
        assertStillPending(organisationId, submitter)
    }

    @Test
    fun `an amender can neither approve nor reject the resubmission`() {
        val maker = uuidV7()
        val returner = uuidV7()
        val amender = uuidV7()
        val resubmitter = uuidV7()
        val organisationId = returnedAndAmended(maker, returner, amender, resubmitter)

        val approveRefusal =
            assertFailsWith<ForbiddenOperationException> { approve(organisationId, amender) }
        val rejectRefusal =
            assertFailsWith<ForbiddenOperationException> { reject(organisationId, amender) }

        assertEquals(LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER, approveRefusal.code)
        assertEquals(
            LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER_DETAIL,
            approveRefusal.safeDetail,
        )
        assertEquals(LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER, rejectRefusal.code)
        assertDenied(
            organisationId,
            amender,
            APPROVE,
            LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER,
        )
        assertDenied(
            organisationId,
            amender,
            REJECT,
            LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER,
        )
        assertStillPending(organisationId, resubmitter)
    }

    @Test
    fun `the amender stays barred across a second return and resubmission`() {
        val maker = uuidV7()
        val amender = uuidV7()
        val organisationId = returnedAndAmended(maker, uuidV7(), amender, uuidV7())
        // A second loop in which someone else amends: the first amender is still an amender.
        returnTenant(organisationId, uuidV7())
        store.organisationStates[organisationId] = OrganisationLifecycleState.DRAFT
        organisations.amendDraft(amendCommand(organisationId, maker))
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = uuidV7()),
        )

        val refusal =
            assertFailsWith<ForbiddenOperationException> { approve(organisationId, amender) }

        assertEquals(LifecycleErrorCodes.APPROVER_IS_TENANT_MODIFIER, refusal.code)
    }

    @Test
    fun `a checker who neither made nor amended the tenant approves the resubmission`() {
        val returner = uuidV7()
        val organisationId = returnedAndAmended(uuidV7(), returner, uuidV7(), uuidV7())

        // The returner only returned it: not an amender, so may approve (as for a branch).
        approve(organisationId, returner)

        assertEquals(returner, adminBootstrapStore.records.getValue(organisationId).approvedBy)
        assertEquals(
            OrganisationLifecycleState.ACTIVE,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertTrue(audits.events.none { it.outcome == AuditOutcome.DENIED })
    }

    @Test
    fun `a checker who neither made nor amended the tenant rejects it`() {
        val checker = uuidV7()
        val organisationId = returnedAndAmended(uuidV7(), uuidV7(), uuidV7(), uuidV7())

        reject(organisationId, checker)

        assertEquals(
            OrganisationLifecycleState.REJECTED,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
        assertTrue(audits.events.none { it.outcome == AuditOutcome.DENIED })
    }

    @Test
    fun `an amender may still return the tenant, as a branch amender may`() {
        val amender = uuidV7()
        val organisationId = returnedAndAmended(uuidV7(), uuidV7(), amender, uuidV7())

        // Returning approves nothing; the branch rule bars an amender from approving only.
        returnTenant(organisationId, amender)

        assertEquals(
            OrganisationLifecycleState.DRAFT,
            lifecyclePersistence.organisations.getValue(organisationId).state,
        )
    }

    // -- helpers ---------------------------------------------------------------------------

    private fun approve(
        organisationId: UUID,
        actor: UUID,
    ) = organisations.approveProvisioning(
        ApproveOrganisationProvisioningCommand(organisationId, actorId = actor),
    )

    private fun reject(
        organisationId: UUID,
        actor: UUID,
    ) = organisations.rejectProvisioning(
        RejectOrganisationProvisioningCommand(
            organisationId,
            Reason.required("Documents incomplete."),
            actor,
        ),
    )

    private fun returnTenant(
        organisationId: UUID,
        actor: UUID,
    ) = organisations.returnForChanges(
        ReturnOrganisationForChangesCommand(
            organisationId,
            Reason.required("Registration number has a typo."),
            actor,
        ),
    )

    /** Pending, then returned by [returner], amended by [amender], resubmitted by [resubmitter]. */
    private fun returnedAndAmended(
        maker: UUID,
        returner: UUID,
        amender: UUID,
        resubmitter: UUID,
    ): UUID {
        val organisationId = pendingTenant(maker, uuidV7())
        returnTenant(organisationId, returner)
        store.organisationStates[organisationId] = OrganisationLifecycleState.DRAFT
        organisations.amendDraft(amendCommand(organisationId, amender))
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = resubmitter),
        )
        return organisationId
    }

    private fun assertDenied(
        organisationId: UUID,
        actor: UUID,
        action: String,
        code: String,
    ) {
        val denied =
            audits.events.single {
                it.outcome == AuditOutcome.DENIED &&
                    it.action == action &&
                    it.actorId == actor.toString()
            }
        assertEquals(organisationId.toString(), denied.tenantId)
        assertEquals(organisationId.toString(), denied.resourceId)
        assertEquals("ORGANISATION", denied.resourceType)
        assertEquals("USER", denied.actorType)
        assertEquals(AuditSeverity.HIGH, denied.severity)
        assertEquals(code, denied.reason)
    }

    private fun assertStillPending(
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
        assertEquals(null, record.approvedBy)
    }

    private fun pendingTenant(
        maker: UUID,
        submitter: UUID,
    ): UUID {
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
        adminBootstrapStore.createDraft(organisationId, administrator(), requestedBy = maker)
        organisations.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId, actorId = submitter),
        )
        return organisationId
    }

    private fun amendCommand(
        organisationId: UUID,
        actorId: UUID,
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
        admin = administrator(),
    )

    private fun administrator() =
        InitialAdministratorDraft(
            email = "admin@test.com",
            username = "admin",
            displayName = "Admin",
            phoneE164 = null,
            sendApplicationInvite = false,
        )

    private companion object {
        const val APPROVE = "organisation.approve"
        const val REJECT = "organisation.reject"
        const val RETURN = "organisation.return_for_changes"
    }
}
