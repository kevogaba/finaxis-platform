package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.AddFiscalPeriodCommand
import com.finaxis.platform.accounting.application.FiscalCalendarService
import com.finaxis.platform.accounting.application.FiscalCalendarStore
import com.finaxis.platform.accounting.application.MaterialiseFiscalYearCommand
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Fiscal-calendar creation, and the two invariants no row-local constraint can express.
 *
 * `ex_accounting_fiscal_period_no_overlap` stops two periods of one tenant covering the same date.
 * It cannot say that a period lies **inside its year**, nor that a year's periods leave **no gap**;
 * both are properties of a set of rows. The design record deferred them to this service, and an
 * earlier revision of issue #39 shipped only the lifecycle transitions — so nothing created a
 * calendar and neither rule was enforced anywhere.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FiscalCalendarIntegrationTests(
    private val calendar: FiscalCalendarService,
    private val store: FiscalCalendarStore,
    dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `a materialised year is contiguous, gapless and ends exactly on the year's last day`() {
        // Gaplessness is by construction rather than by validation: each period begins the day
        // after the previous ends. A gap would leave posting dates that resolve to no period at
        // all, which is only visible when someone tries to post into one.
        val organisationId = organisation("materialise")

        val periods =
            withRequestContext { calendar.materialiseYear(year(organisationId, 12)) }.periods

        assertEquals(12, periods.size)
        assertEquals(LocalDate.of(2027, 1, 1), periods.first().startDate)
        assertEquals(LocalDate.of(2027, 12, 31), periods.last().endDate)
        periods.zipWithNext { earlier, later ->
            assertEquals(
                earlier.endDate.plusDays(1),
                later.startDate,
                "periods must be contiguous, so no posting date falls between two of them",
            )
        }
        assertTrue(periods.all { it.status == FiscalPeriodStatus.FUTURE })
    }

    @Test
    fun `an odd division still ends on the year's last day`() {
        // The remainder falls to the last period rather than being dropped, so 365 days across 13
        // periods still covers the year exactly. Dropping it would leave a gap at the year end -
        // the busiest days in an accounting calendar.
        val organisationId = organisation("odd-division")

        val periods =
            withRequestContext { calendar.materialiseYear(year(organisationId, 13)) }.periods

        assertEquals(13, periods.size)
        assertEquals(LocalDate.of(2027, 12, 31), periods.last().endDate)
    }

    @Test
    fun `a period outside its year is refused`() {
        // The invariant that has no database counterpart. fk_accounting_fiscal_period_year only
        // says the year belongs to the same tenant; a 2028 period under a 2027 year satisfies it,
        // and would then make "the year is closed when all its periods are" unanswerable.
        val organisationId = organisation("containment")
        val fiscalYearId =
            withRequestContext { calendar.materialiseYear(year(organisationId, 1)) }.fiscalYearId

        listOf(
            LocalDate.of(2026, 12, 25) to LocalDate.of(2026, 12, 31),
            LocalDate.of(2028, 1, 1) to LocalDate.of(2028, 1, 31),
        ).forEach { (start, end) ->
            val failure =
                assertFailsWith<ApplicationException> {
                    withRequestContext {
                        calendar.addPeriod(
                            AddFiscalPeriodCommand(
                                organisationId = organisationId,
                                actorId = ACTOR_ID,
                                fiscalYearId = fiscalYearId,
                                number = 99,
                                name = "Stray",
                                startDate = start,
                                endDate = end,
                            ),
                        )
                    }
                }
            assertEquals("accounting.fiscal_period_outside_year", failure.code)
        }
        assertEquals(1, store.periodsOf(organisationId, fiscalYearId).size)
    }

    @Test
    fun `a year that ends before it starts is refused`() {
        val organisationId = organisation("bounds")

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext {
                    calendar.materialiseYear(
                        year(organisationId, 1).copy(
                            startDate = LocalDate.of(2027, 12, 31),
                            endDate = LocalDate.of(2027, 1, 1),
                        ),
                    )
                }
            }

        assertEquals("accounting.fiscal_calendar_bounds_invalid", failure.code)
    }

    @Test
    fun `materialising a year is permission gated`() {
        val organisationId = organisation("permission")

        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                calendar.materialiseYear(year(organisationId, 12).copy(actorId = STRANGER))
            }
        }
    }

    private fun organisation(label: String): UUID =
        tenants.createActiveOrganisation("calendar-$label", ACTOR_ID)

    private fun year(
        organisationId: UUID,
        periodCount: Int,
    ) = MaterialiseFiscalYearCommand(
        organisationId = organisationId,
        actorId = ACTOR_ID,
        code = "FY2027",
        name = "Financial year 2027",
        startDate = LocalDate.of(2027, 1, 1),
        endDate = LocalDate.of(2027, 12, 31),
        periodCount = periodCount,
    )

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
