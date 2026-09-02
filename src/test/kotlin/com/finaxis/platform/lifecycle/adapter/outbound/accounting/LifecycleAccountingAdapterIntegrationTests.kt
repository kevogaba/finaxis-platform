package com.finaxis.platform.lifecycle.adapter.outbound.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.StartCobCommand
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

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
