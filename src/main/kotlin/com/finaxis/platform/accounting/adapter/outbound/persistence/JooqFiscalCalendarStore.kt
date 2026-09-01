package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.FiscalCalendarStore
import com.finaxis.platform.accounting.application.FiscalYearSnapshot
import com.finaxis.platform.accounting.application.NewFiscalPeriod
import com.finaxis.platform.accounting.application.NewFiscalYear
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Writes the fiscal calendar.
 *
 * Separate from [JooqFiscalPeriodStateStore] because the two answer different questions and have
 * different concurrency needs: this creates rows, that one locks and moves an existing one. Nothing
 * here takes a lock, because a row that does not exist yet has nothing to lock — the constraints
 * `V6` created are what make a concurrent double-materialisation fail rather than duplicate.
 */
@Component
class JooqFiscalCalendarStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : FiscalCalendarStore {
    override fun createYear(year: NewFiscalYear): UUID {
        val now = OffsetDateTime.now(clock)
        return dsl
            .insertInto(ACCOUNTING_FISCAL_YEAR)
            .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, year.organisationId)
            .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, year.code)
            .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, year.name)
            .set(ACCOUNTING_FISCAL_YEAR.START_DATE, year.startDate)
            .set(ACCOUNTING_FISCAL_YEAR.END_DATE, year.endDate)
            .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now)
            .set(ACCOUNTING_FISCAL_YEAR.CREATED_BY, year.actorId)
            .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now)
            .set(ACCOUNTING_FISCAL_YEAR.UPDATED_BY, year.actorId)
            .returning(ACCOUNTING_FISCAL_YEAR.ID)
            .fetchOne()
            ?.id
            ?: error("accounting_fiscal_year insert returned no identifier")
    }

    override fun createPeriod(period: NewFiscalPeriod): FiscalPeriodSnapshot {
        val now = OffsetDateTime.now(clock)
        val id =
            dsl
                .insertInto(ACCOUNTING_FISCAL_PERIOD)
                .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, period.organisationId)
                .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, period.fiscalYearId)
                .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, period.number)
                .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, period.name)
                .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, period.startDate)
                .set(ACCOUNTING_FISCAL_PERIOD.END_DATE, period.endDate)
                .set(ACCOUNTING_FISCAL_PERIOD.STATUS, period.status.name)
                .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now)
                .set(ACCOUNTING_FISCAL_PERIOD.CREATED_BY, period.actorId)
                .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now)
                .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_BY, period.actorId)
                .returning(ACCOUNTING_FISCAL_PERIOD.ID)
                .fetchOne()
                ?.id
                ?: error("accounting_fiscal_period insert returned no identifier")

        return FiscalPeriodSnapshot(
            key = FiscalPeriodKey(period.organisationId, id),
            startDate = period.startDate,
            endDate = period.endDate,
            status = period.status,
        )
    }

    override fun findYear(
        organisationId: UUID,
        fiscalYearId: UUID,
    ): FiscalYearSnapshot? =
        dsl
            .select(
                ACCOUNTING_FISCAL_YEAR.ID,
                ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID,
                ACCOUNTING_FISCAL_YEAR.YEAR_CODE,
                ACCOUNTING_FISCAL_YEAR.START_DATE,
                ACCOUNTING_FISCAL_YEAR.END_DATE,
            ).from(ACCOUNTING_FISCAL_YEAR)
            .where(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID.eq(organisationId))
            .and(ACCOUNTING_FISCAL_YEAR.ID.eq(fiscalYearId))
            .fetchOne()
            ?.let {
                FiscalYearSnapshot(
                    id = it.value1()!!,
                    organisationId = it.value2()!!,
                    code = it.value3()!!,
                    startDate = it.value4()!!,
                    endDate = it.value5()!!,
                )
            }

    override fun periodsOf(
        organisationId: UUID,
        fiscalYearId: UUID,
    ): List<FiscalPeriodSnapshot> =
        dsl
            .select(
                ACCOUNTING_FISCAL_PERIOD.ID,
                ACCOUNTING_FISCAL_PERIOD.START_DATE,
                ACCOUNTING_FISCAL_PERIOD.END_DATE,
                ACCOUNTING_FISCAL_PERIOD.STATUS,
            ).from(ACCOUNTING_FISCAL_PERIOD)
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(organisationId))
            .and(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID.eq(fiscalYearId))
            .orderBy(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER.asc())
            .fetch()
            .map {
                FiscalPeriodSnapshot(
                    key = FiscalPeriodKey(organisationId, it.value1()!!),
                    startDate = it.value2()!!,
                    endDate = it.value3()!!,
                    status = FiscalPeriodStatus.valueOf(it.value4()!!),
                )
            }
}
