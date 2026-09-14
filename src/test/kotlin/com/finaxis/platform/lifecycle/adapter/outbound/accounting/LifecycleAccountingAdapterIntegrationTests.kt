package com.finaxis.platform.lifecycle.adapter.outbound.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.StartCobCommand
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real lifecycle services through the accounting ports.
 *
 * The business-date adapter compares against a duplicated `"OPEN"` literal, because the lifecycle
 * service keeps that constant private. A unit test with a fake store could not catch the two
 * drifting apart, so this test moves a real business date out of OPEN and asserts the adapter
 * follows it.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LifecycleAccountingAdapterIntegrationTests(
    private val dsl: DSLContext,
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val businessDateService: BusinessDateService,
    private val businessDateLookup: AccountingBusinessDateLookup,
    private val tenantLookup: AccountingTenantLookup,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `an open business date permits posting and a closing one does not`() {
        val organisationId = fixture.createActiveOrganisation("accounting-port", ACTOR_ID)

        val whenOpen = assertNotNull(businessDateLookup.currentBusinessDate(organisationId))
        assertTrue(
            whenOpen.postingAllowed,
            "a freshly provisioned organisation has an OPEN business date, so posting is allowed",
        )

        withRequestContext {
            businessDateService.startCob(
                StartCobCommand(organisationId = organisationId, actorId = ACTOR_ID),
            )
        }

        val whenClosing = assertNotNull(businessDateLookup.currentBusinessDate(organisationId))
        assertFalse(
            whenClosing.postingAllowed,
            "close-of-business has started, so the adapter must deny posting - if this fails the " +
                "adapter's duplicated OPEN literal has drifted from the lifecycle service",
        )
    }

    @Test
    fun `an unknown organisation has no business date`() {
        assertNull(businessDateLookup.currentBusinessDate(uuidV7()))
    }

    @Test
    fun `an active organisation is postable and an unknown one is not`() {
        val organisationId = fixture.createActiveOrganisation("accounting-tenant", ACTOR_ID)

        assertTrue(tenantLookup.isOrganisationPostable(organisationId))
        assertFalse(tenantLookup.isOrganisationPostable(uuidV7()))
    }

    @Test
    fun `the functional currency is the organisation's base currency column`() {
        val organisationId = fixture.createActiveOrganisation("accounting-currency", ACTOR_ID)

        assertEquals("KES", tenantLookup.functionalCurrencyOf(organisationId))
        assertNull(tenantLookup.functionalCurrencyOf(uuidV7()))
    }

    @Test
    fun `an unknown branch is not postable`() {
        val organisationId = fixture.createActiveOrganisation("accounting-branch", ACTOR_ID)

        assertFalse(tenantLookup.isBranchPostable(organisationId, uuidV7()))
    }

    /**
     * The distinction issue #91 added `branchBelongsTo` for: membership is not postability.
     *
     * A reconciliation proves a date that has already happened, so a branch closed since then is a
     * legitimate subject of one. If this method ever collapses back onto the ACTIVE check, a
     * historical proof of a closed branch starts being refused for a reason that is not true.
     */
    @Test
    fun `a closed branch still belongs to its organisation even though it cannot be posted to`() {
        val organisationId = fixture.createActiveOrganisation("accounting-branch-scope", ACTOR_ID)
        val branchId =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_CODE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            )

        assertTrue(tenantLookup.isBranchPostable(organisationId, branchId))
        assertTrue(tenantLookup.branchBelongsTo(organisationId, branchId))

        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, BranchLifecycleState.CLOSED.name)
            .where(BRANCH.ID.eq(branchId))
            .execute()

        assertFalse(tenantLookup.isBranchPostable(organisationId, branchId))
        assertTrue(
            tenantLookup.branchBelongsTo(organisationId, branchId),
            "a closed branch is still a branch of this organisation, and a proof of a date it " +
                "was open is still a legitimate question",
        )

        // Membership is tenant-scoped both ways: another tenant's branch, and no branch at all.
        val elsewhere = fixture.createActiveOrganisation("accounting-branch-other", ACTOR_ID)
        assertFalse(tenantLookup.branchBelongsTo(elsewhere, branchId))
        assertFalse(tenantLookup.branchBelongsTo(organisationId, uuidV7()))
    }

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
