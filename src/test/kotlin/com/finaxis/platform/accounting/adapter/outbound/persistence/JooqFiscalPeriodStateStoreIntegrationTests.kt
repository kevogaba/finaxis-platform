package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The date predicate `findCovering` rests on, executed against a database for the first time.
 *
 * Issue #35's stand-in ignored its `postingDate` parameter entirely and returned hardcoded bounds,
 * so every concurrency scenario passed without the period-selection half of the protocol ever
 * running. The lock scenarios live in [FiscalPeriodConcurrencyIntegrationTests]; this covers what
 * they could not.
 *
 * `posting_date` is the only column that selects a period (`INV-9`), and the bounds are inclusive
 * at both ends, so the boundary days are asserted rather than assumed.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JooqFiscalPeriodStateStoreIntegrationTests(
    private val periods: FiscalPeriodStateStore,
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
    transactionManager: PlatformTransactionManager,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val calendar = FiscalCalendarFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `a date inside the period resolves to it, and both boundary days count`() {
        val organisationId = tenants.createActiveOrganisation("covering", ACTOR_ID)
        val key = calendar.createPeriod(organisationId, FiscalPeriodStatus.OPEN)

        listOf(
            FiscalCalendarFixture.PERIOD_START,
            FiscalCalendarFixture.PERIOD_DAY,
            FiscalCalendarFixture.PERIOD_END,
        ).forEach { date ->
            assertEquals(
                key,
                periods.findCovering(organisationId, date)?.key,
                "the period is inclusive of $date",
            )
        }
    }

    @Test
    fun `a date outside every period resolves to nothing`() {
        // What makes accounting.fiscal_period_not_found reachable: periods are provisioned, never
        // auto-created by a posting, so a date the calendar does not cover has no period at all.
        //
        // Mutation-checked, because this asserts an absence: deleting the two date predicates from
        // findCovering - which is exactly what the Phase A stand-in did, ignoring its postingDate
        // and returning hardcoded bounds - fails this test.
        val organisationId = tenants.createActiveOrganisation("uncovered", ACTOR_ID)
        calendar.createPeriod(organisationId, FiscalPeriodStatus.OPEN)

        assertNull(
            periods.findCovering(organisationId, FiscalCalendarFixture.PERIOD_START.minusDays(1)),
            "the day before the period starts is not in it",
        )
        assertNull(
            periods.findCovering(organisationId, FiscalCalendarFixture.PERIOD_END.plusDays(1)),
            "the day after the period ends is not in it",
        )
    }

    @Test
    fun `the snapshot carries the period's own bounds and status`() {
        val organisationId = tenants.createActiveOrganisation("bounds", ACTOR_ID)
        calendar.createPeriod(organisationId, FiscalPeriodStatus.FUTURE)

        val snapshot =
            requireNotNull(periods.findCovering(organisationId, FiscalCalendarFixture.PERIOD_DAY))

        assertEquals(FiscalCalendarFixture.PERIOD_START, snapshot.startDate)
        assertEquals(FiscalCalendarFixture.PERIOD_END, snapshot.endDate)
        assertEquals(FiscalPeriodStatus.FUTURE, snapshot.status)
    }

    @Test
    fun `one tenant's calendar never resolves another tenant's period`() {
        // Both tenants hold a period over exactly the same dates, which the tenant-scoped
        // exclusion constraint permits and which is the ordinary case: every tenant has a January.
        val first = tenants.createActiveOrganisation("iso-first", ACTOR_ID)
        val second = tenants.createActiveOrganisation("iso-second", ACTOR_ID)
        val firstKey = calendar.createPeriod(first, FiscalPeriodStatus.OPEN)
        val secondKey = calendar.createPeriod(second, FiscalPeriodStatus.OPEN)

        assertEquals(
            firstKey,
            periods.findCovering(first, FiscalCalendarFixture.PERIOD_DAY)?.key,
        )
        assertEquals(
            secondKey,
            periods.findCovering(second, FiscalCalendarFixture.PERIOD_DAY)?.key,
        )
    }

    @Test
    fun `updateStatus records the actor on the row it moves`() {
        val organisationId = tenants.createActiveOrganisation("actor", ACTOR_ID)
        val key = calendar.createPeriod(organisationId, FiscalPeriodStatus.OPEN)

        val moved =
            transactions.execute {
                periods.lockForStateChange(key)
                periods.updateStatus(key, FiscalPeriodStatus.CLOSED, ACTOR_ID, null)
            }

        assertEquals(true, moved)
        assertEquals(
            FiscalPeriodStatus.CLOSED,
            requireNotNull(periods.findById(key)).status,
        )
        assertEquals(
            listOf(ACTOR_ID, 1L),
            dsl
                .fetchOne(
                    """
                    SELECT updated_by, row_version FROM accounting_fiscal_period WHERE id = ?
                    """.trimIndent(),
                    key.fiscalPeriodId,
                )!!
                .intoList(),
            "the row must say who moved it and its version must advance",
        )
    }

    @Test
    fun `updateStatus reports false for a period another tenant owns`() {
        val owner = tenants.createActiveOrganisation("update-owner", ACTOR_ID)
        val other = tenants.createActiveOrganisation("update-other", ACTOR_ID)
        val key = calendar.createPeriod(owner, FiscalPeriodStatus.OPEN)
        val forged = FiscalPeriodKey(other, key.fiscalPeriodId)

        val moved =
            transactions.execute {
                periods.updateStatus(forged, FiscalPeriodStatus.CLOSED, ACTOR_ID, null)
            }

        assertEquals(false, moved, "a forged tenant key must move nothing")
        assertEquals(
            FiscalPeriodStatus.OPEN,
            requireNotNull(periods.findById(key)).status,
            "the owner's period must be untouched",
        )
    }

    private companion object {
        /** The `V3` bootstrap administrator: `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
