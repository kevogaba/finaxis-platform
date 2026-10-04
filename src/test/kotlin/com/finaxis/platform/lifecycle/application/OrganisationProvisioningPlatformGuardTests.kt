package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The reserved `PLATFORM` organisation is never a tenant to be moved through the tenant lifecycle
 * (issue #205). Every [OrganisationProvisioningService] method that takes a tenant id and mutates
 * or transitions it refuses the platform organisation with a 409 and one explicit message, before
 * it locks, reads or transitions anything. Where the service itself checks the caller's platform
 * permission, that check comes first, so an unauthorised caller learns nothing, not even that this
 * organisation is special.
 *
 * Every collaborator is a strict mock: a refusal that had already locked, read or transitioned
 * would fail `verifyNoInteractions`, which is the point, because the lock is held to the end of
 * the transaction and the read answers for a row the caller must not learn about. The one thing a
 * refusal does write is a single `DENIED` audit row through `recordIndependently` (a new
 * transaction, so the 409's rollback cannot take it); an unauthorised caller, refused before the
 * guard, writes none.
 */
class OrganisationProvisioningPlatformGuardTests {
    private val clock = Clock.fixed(Instant.parse("2026-07-14T10:00:00Z"), ZoneOffset.UTC)
    private val lifecycleService = mock(FoundationLifecycleService::class.java)
    private val lifecycleStore = mock(OrganisationLifecycleProvisioningStore::class.java)
    private val bootstrapStore = mock(OrganisationBootstrapStore::class.java)
    private val accessStore = mock(OrganisationAccessStore::class.java)
    private val queryStore = mock(OrganisationQueryStore::class.java)
    private val auditService = mock(AuditService::class.java)
    private val adminBootstrapStore = mock(InitialAdministratorBootstrapStore::class.java)
    private val bootstrapService = mock(InitialAdministratorBootstrapService::class.java)
    private val permissionGuard = mock(PermissionGuard::class.java)
    private val organisations =
        OrganisationProvisioningService(
            lifecycleService,
            lifecycleStore,
            bootstrapStore,
            accessStore,
            queryStore,
            auditService,
            adminBootstrapStore,
            bootstrapService,
            permissionGuard,
            clock,
        )
    private val platformId = PlatformOrganisation.ID

    @Test
    fun `amend refuses the platform organisation`() {
        val actor = uuidV7()
        assertRefused("organisation.amend_draft", actor) {
            organisations.amendDraft(
                AmendOrganisationDraftCommand(
                    organisationId = platformId,
                    tenantCode = "renamed",
                    displayName = "Renamed",
                    legalName = null,
                    registrationNumber = null,
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    actorId = actor,
                    requestId = uuidV7(),
                    admin =
                        InitialAdministratorDraft(
                            email = "admin@test.com",
                            username = "admin",
                            displayName = "Admin",
                            phoneE164 = null,
                            sendApplicationInvite = false,
                        ),
                ),
            )
        }
    }

    @Test
    fun `submit refuses the platform organisation`() {
        val actor = uuidV7()
        assertRefused("organisation.submit_for_approval", actor) {
            organisations.submitForApproval(
                SubmitOrganisationForApprovalCommand(platformId, actorId = actor),
            )
        }
    }

    @Test
    fun `approve refuses the platform organisation`() {
        val actor = uuidV7()
        assertRefused("organisation.approve", actor) {
            organisations.approveProvisioning(
                ApproveOrganisationProvisioningCommand(platformId, actorId = actor),
            )
        }
    }

    @Test
    fun `reject refuses the platform organisation`() {
        val actor = uuidV7()
        assertRefused("organisation.reject", actor) {
            organisations.rejectProvisioning(
                RejectOrganisationProvisioningCommand(platformId, "No.", actor),
            )
        }
    }

    @Test
    fun `return refuses the platform organisation after the permission check`() {
        val actor = uuidV7()

        assertRefused("organisation.return_for_changes", actor) {
            organisations.returnForChanges(
                ReturnOrganisationForChangesCommand(platformId, "Typo.", actor),
            )
        }

        verify(permissionGuard).requirePlatformPermission(actor, "tenant.reject")
    }

    @Test
    fun `return answers a caller without the permission 403 before the platform refusal`() {
        val actor = uuidV7()
        doThrow(ForbiddenOperationException())
            .whenever(permissionGuard)
            .requirePlatformPermission(actor, "tenant.reject")

        assertFailsWith<ForbiddenOperationException> {
            organisations.returnForChanges(
                ReturnOrganisationForChangesCommand(platformId, "Typo.", actor),
            )
        }

        assertUntouched()
        verifyNoInteractions(auditService)
    }

    @Test
    fun `suspend refuses the platform organisation`() {
        assertRefused("organisation.suspend") {
            organisations.suspend(SuspendOrganisationCommand(platformId, "Maintenance."))
        }
    }

    @Test
    fun `reactivate refuses the platform organisation`() {
        assertRefused("organisation.reactivate") {
            organisations.reactivate(ReactivateOrganisationCommand(platformId))
        }
    }

    @Test
    fun `deprovision refuses the platform organisation before it revokes anything`() {
        assertRefused("organisation.deprovision") {
            organisations.deprovision(DeprovisionOrganisationCommand(platformId, "Wind down."))
        }
    }

    @Test
    fun `retry bootstrap refuses the platform organisation after the platform permission check`() {
        val actor = uuidV7()

        assertRefused("tenant.bootstrap_retry", actor) {
            organisations.retryBootstrap(
                RetryInitialAdministratorBootstrapCommand(
                    platformId,
                    PlatformCaller(actor, platformId),
                ),
            )
        }

        verify(permissionGuard).requirePlatformPermission(actor, "tenant.bootstrap_retry")
    }

    @Test
    fun `retry bootstrap checks the tenant permission before refusing a tenant caller`() {
        val actor = uuidV7()

        assertRefused("tenant.bootstrap_retry", actor) {
            organisations.retryBootstrap(
                RetryInitialAdministratorBootstrapCommand(
                    platformId,
                    TenantCaller(actor, platformId),
                ),
            )
        }

        verify(permissionGuard)
            .requireTenantPermission(actor, platformId, "tenant.bootstrap_retry")
    }

    @Test
    fun `retry bootstrap answers a caller without the permission 403 before the refusal`() {
        val actor = uuidV7()
        doThrow(ForbiddenOperationException())
            .whenever(permissionGuard)
            .requirePlatformPermission(actor, "tenant.bootstrap_retry")

        assertFailsWith<ForbiddenOperationException> {
            organisations.retryBootstrap(
                RetryInitialAdministratorBootstrapCommand(
                    platformId,
                    PlatformCaller(actor, platformId),
                ),
            )
        }

        assertUntouched()
        verifyNoInteractions(auditService)
    }

    @Test
    fun `an ordinary tenant is not refused by the guard`() {
        organisations.suspend(SuspendOrganisationCommand(uuidV7(), "Pause."))

        verify(lifecycleService).transition(any<OrganisationTransitionCommand>())
        verifyNoInteractions(auditService)
    }

    private fun assertRefused(
        action: String,
        actor: UUID? = null,
        call: () -> Unit,
    ) {
        val failure = assertFailsWith<ConflictException> { call() }

        // Literals, not the constants: this pins the public contract the client sees.
        assertEquals("lifecycle.platform_organisation_protected", failure.code)
        assertEquals(
            "The platform organisation cannot be suspended, deprovisioned or otherwise changed " +
                "through the tenant lifecycle.",
            failure.safeDetail,
        )
        assertDeniedAuditRecorded(action, actor)
        assertUntouched()
        verifyNoMoreInteractions(auditService)
    }

    /**
     * Exactly one `DENIED` audit, recorded through the independent (REQUIRES_NEW) path so it
     * survives the rollback the 409 causes, naming the actor, the attempted action and the
     * platform organisation as the entity.
     */
    private fun assertDeniedAuditRecorded(
        action: String,
        actor: UUID?,
    ) {
        val captor = argumentCaptor<AuditCommand>()
        verify(auditService, times(1)).recordIndependently(captor.capture())
        val audit = captor.firstValue
        assertEquals(AuditOutcome.DENIED, audit.outcome)
        assertEquals(action, audit.action)
        assertEquals("ORGANISATION", audit.resourceType)
        assertEquals(platformId.toString(), audit.resourceId)
        assertEquals(platformId.toString(), audit.tenantId)
        assertEquals("lifecycle.platform_organisation_protected", audit.reason)
        assertEquals(AuditSeverity.HIGH, audit.severity)
        if (actor != null) {
            assertEquals(actor.toString(), audit.actorId)
            assertEquals("USER", audit.actorType)
        }
    }

    /** No lock, read, transition or bootstrap happened: only the permission check ran. */
    private fun assertUntouched() {
        verifyNoInteractions(
            lifecycleService,
            lifecycleStore,
            bootstrapStore,
            accessStore,
            queryStore,
            adminBootstrapStore,
            bootstrapService,
        )
    }
}
