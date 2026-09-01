package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Creates fiscal years and the periods that divide them.
 *
 * This is the **only** path that creates a calendar row, and that is what makes two invariants
 * enforceable at all. `ex_accounting_fiscal_period_no_overlap` stops two periods of one tenant
 * covering the same date, but no row-local constraint can say that a period lies inside its year,
 * or that a year's periods leave no gap — both are properties of a set of rows.
 *
 * The design record deferred them here and an earlier revision of this issue shipped only the
 * lifecycle transitions, so nothing created a calendar and nothing enforced either rule. A period
 * outside its year is not a harmless oddity: a year's closure is defined as *"every one of its
 * periods is closed"*, so a stray period makes that question unanswerable, and a posting dated
 * inside the stray period resolves to a year that was reported as closed.
 */
@Service
class FiscalCalendarService(
    private val calendar: FiscalCalendarStore,
    private val permissions: AccountingPermissionGuard,
) {
    /**
     * Creates a fiscal year and materialises its periods as one contiguous, gapless set.
     *
     * Periods are generated rather than supplied, which is what makes "no gaps" true by
     * construction instead of by validation. The caller chooses the year's bounds and how many
     * periods divide it; the last period absorbs any remainder, so twelve monthly periods and
     * thirteen four-weekly ones both end exactly on the year's last day.
     */
    @Transactional
    fun materialiseYear(command: MaterialiseFiscalYearCommand): MaterialisedFiscalYear {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.FISCAL_PERIOD_OPEN,
        )
        requireOrderedBounds(command.startDate, command.endDate)
        require(command.periodCount in 1..MAX_PERIODS_PER_YEAR) {
            "A fiscal year is divided into between 1 and $MAX_PERIODS_PER_YEAR periods."
        }

        val yearId =
            calendar.createYear(
                NewFiscalYear(
                    organisationId = command.organisationId,
                    code = command.code,
                    name = command.name,
                    startDate = command.startDate,
                    endDate = command.endDate,
                    actorId = command.actorId,
                ),
            )

        val periods =
            boundariesOf(command).mapIndexed { index, bounds ->
                calendar.createPeriod(
                    NewFiscalPeriod(
                        organisationId = command.organisationId,
                        fiscalYearId = yearId,
                        number = index + 1,
                        name = "${command.code} P${index + 1}",
                        startDate = bounds.first,
                        endDate = bounds.second,
                        status = FiscalPeriodStatus.FUTURE,
                        actorId = command.actorId,
                    ),
                )
            }
        return MaterialisedFiscalYear(yearId, periods)
    }

    /**
     * Adds one period to an existing year, refusing anything outside the year's bounds.
     *
     * The rule [materialiseYear] gets for free, stated explicitly for the path that does not.
     */
    @Transactional
    fun addPeriod(command: AddFiscalPeriodCommand): FiscalPeriodSnapshot {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.FISCAL_PERIOD_OPEN,
        )
        requireOrderedBounds(command.startDate, command.endDate)

        val year =
            calendar.findYear(command.organisationId, command.fiscalYearId)
                ?: throw ConflictException(
                    code = PostingErrorCodes.PERIOD_NOT_FOUND,
                    safeDetail = "The fiscal year does not exist.",
                )
        requireWithinYear(command.startDate, command.endDate, year)

        return calendar.createPeriod(
            NewFiscalPeriod(
                organisationId = command.organisationId,
                fiscalYearId = year.id,
                number = command.number,
                name = command.name,
                startDate = command.startDate,
                endDate = command.endDate,
                status = FiscalPeriodStatus.FUTURE,
                actorId = command.actorId,
            ),
        )
    }

    private fun boundariesOf(
        command: MaterialiseFiscalYearCommand,
    ): List<Pair<LocalDate, LocalDate>> {
        val span = ChronoUnit.DAYS.between(command.startDate, command.endDate)
        val lengths = evenLengths(span + 1, command.periodCount)

        var cursor = command.startDate
        return lengths.map { length ->
            val end = cursor.plusDays(length - 1)
            val bounds = cursor to end
            cursor = end.plusDays(1)
            bounds
        }
    }

    /**
     * Splits [days] into [count] contiguous lengths, the remainder falling to the last.
     *
     * Contiguity is the point: each period starts the day after the previous one ends, so the set
     * has no gap and no overlap by construction rather than by a check that could be forgotten.
     */
    private fun evenLengths(
        days: Long,
        count: Int,
    ): List<Long> {
        require(days >= count) {
            "A fiscal year of $days days cannot be divided into $count periods of at least a day."
        }
        val base = days / count
        return (1..count).map { index -> if (index == count) days - base * (count - 1) else base }
    }

    private fun requireOrderedBounds(
        start: LocalDate,
        end: LocalDate,
    ) {
        if (end < start) {
            throw InvalidOperationException(
                code = CALENDAR_BOUNDS,
                safeDetail = "A fiscal year or period cannot end before it starts.",
            )
        }
    }

    private fun requireWithinYear(
        start: LocalDate,
        end: LocalDate,
        year: FiscalYearSnapshot,
    ) {
        if (start < year.startDate || end > year.endDate) {
            throw InvalidOperationException(
                code = PERIOD_OUTSIDE_YEAR,
                safeDetail = "A fiscal period must lie inside its fiscal year.",
            )
        }
    }

    private companion object {
        /** A period must be at least a day, and no calendar needs finer division than weekly. */
        const val MAX_PERIODS_PER_YEAR = 53

        const val CALENDAR_BOUNDS = "accounting.fiscal_calendar_bounds_invalid"
        const val PERIOD_OUTSIDE_YEAR = "accounting.fiscal_period_outside_year"
    }
}

/** A newly created fiscal year and the contiguous periods that divide it. */
data class MaterialisedFiscalYear(
    val fiscalYearId: UUID,
    val periods: List<FiscalPeriodSnapshot>,
)

/** Request to create a fiscal year and divide it into contiguous periods. */
data class MaterialiseFiscalYearCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val code: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val periodCount: Int,
)

/** Request to add one period to an existing fiscal year. */
data class AddFiscalPeriodCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val fiscalYearId: UUID,
    val number: Int,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/** A fiscal year as the calendar service reads it. */
data class FiscalYearSnapshot(
    val id: UUID,
    val organisationId: UUID,
    val code: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/** A fiscal year before the database has given it an identity. */
data class NewFiscalYear(
    val organisationId: UUID,
    val code: String,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val actorId: UUID,
)

/** A fiscal period before the database has given it an identity. */
data class NewFiscalPeriod(
    val organisationId: UUID,
    val fiscalYearId: UUID,
    val number: Int,
    val name: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: FiscalPeriodStatus,
    val actorId: UUID,
)

/**
 * Write port for the fiscal calendar.
 *
 * Reads of a single period by key stay on `FiscalPeriodStateStore`, which is the one that locks.
 */
interface FiscalCalendarStore {
    /** Creates a fiscal year and returns its generated identifier. */
    fun createYear(year: NewFiscalYear): UUID

    /** Creates a fiscal period and returns it as stored. */
    fun createPeriod(period: NewFiscalPeriod): FiscalPeriodSnapshot

    /** Finds a year within a tenant, or null. */
    fun findYear(
        organisationId: UUID,
        fiscalYearId: UUID,
    ): FiscalYearSnapshot?

    /** Lists a year's periods in ordinal order. Bounded by the year, so never unbounded. */
    fun periodsOf(
        organisationId: UUID,
        fiscalYearId: UUID,
    ): List<FiscalPeriodSnapshot>
}
