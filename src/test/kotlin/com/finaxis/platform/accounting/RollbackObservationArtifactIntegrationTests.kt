package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Settles GitHub issue #12, which reported that a jOOQ write survived a failing `@Transactional`
 * method across three "independent" mechanisms.
 *
 * The original defect is not reproducible on current `main`: both code paths #12 named are gone -
 * `OrganisationSettingsService` was superseded by [TenantSettingsService], and
 * `@AuditedAction`/`AuditedActionAspect` were removed on 2026-08-02 (ADR 0007, amended). What can
 * be reproduced is the *observation*, and these three tests show it is an artifact of where the
 * assertion's connection sits rather than a durability failure.
 *
 * All three drive the identical failing operation. They differ in exactly one respect:
 *
 * - [rollback is durable when observed from outside the transaction] asserts after the transaction
 *   has rolled back, on a fresh pooled connection. The write is gone.
 * - [an uncommitted write is visible on the transaction's own connection] asserts from inside the
 *   still-open transaction, on the connection that holds it. The write is visible - and then
 *   vanishes once that transaction rolls back.
 * - [a transactional service really runs inside an active transaction] rules out the remaining
 *   explanation, that the harness was never proxied at all.
 *
 * That single confound explains why all three of the mechanisms in the original report failed
 * identically: they were not independent, they shared one ambient transaction and one connection.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RollbackObservationArtifactIntegrationTests(
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
    private val tenantSettingsService: TenantSettingsService,
    transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `rollback is durable when observed from outside the transaction`() {
        val organisationId = fixture.createActiveOrganisation("artifact-e1", ACTOR_ID)
        val probe = FoundationAtomicityProbes.organisationSettingRows(organisationId, SETTING_KEY)
        assertEquals(0L, probe.countRows(dsl), "no setting should exist before the operation")

        assertFailsWith<IllegalStateException> {
            transactions.execute {
                withRequestContext { updateSetting(organisationId, "USD") }
                error("simulated failure after a successful settings write")
            }
        }

        assertEquals(
            0L,
            probe.countRows(dsl),
            "the write must not survive: this is the assertion issue #12 reported failing",
        )
    }

    @Test
    fun `an uncommitted write is visible on the transaction's own connection`() {
        val organisationId = fixture.createActiveOrganisation("artifact-e2", ACTOR_ID)
        val probe = FoundationAtomicityProbes.organisationSettingRows(organisationId, SETTING_KEY)

        assertFailsWith<IllegalStateException> {
            transactions.execute {
                withRequestContext { updateSetting(organisationId, "USD") }

                // The failure the caller would see, swallowed here so the assertion below can run
                // while the transaction is still open and marked for rollback.
                val failure = runCatching { error("simulated failure after a successful write") }
                assertTrue(failure.isFailure)

                assertEquals(
                    1L,
                    probe.countRows(dsl),
                    "on the transaction's own connection the uncommitted write IS visible - " +
                        "this is precisely what issue #12 observed and read as a durable write",
                )
                error("propagate so the transaction rolls back")
            }
        }

        assertEquals(
            0L,
            probe.countRows(dsl),
            "and from a fresh connection after rollback it is gone - so the earlier reading was " +
                "an artifact of connection affinity, not a rollback defect",
        )
    }

    @Test
    fun `a transactional service really runs inside an active transaction`() {
        val organisationId = fixture.createActiveOrganisation("artifact-e3", ACTOR_ID)

        assertTrue(
            requireNotNull(
                transactions.execute {
                    withRequestContext { updateSetting(organisationId, "KES") }
                    TransactionSynchronizationManager.isActualTransactionActive()
                },
            ),
            "the service call must participate in a real transaction; if this were false the " +
                "harness was never proxied and #12 would be a proxying bug, not an artifact",
        )
    }

    private fun updateSetting(
        organisationId: UUID,
        value: String,
    ) {
        tenantSettingsService.createOrUpdate(
            CreateOrUpdateTenantSettingCommand(
                organisationId = organisationId,
                key = SETTING_KEY,
                value = value,
                actorId = ACTOR_ID,
            ),
        )
    }

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val SETTING_KEY = "base_currency"
    }
}
