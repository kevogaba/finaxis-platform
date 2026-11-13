package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE_HISTORY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.AutoAdvanceCandidate
import com.finaxis.platform.lifecycle.application.AutoAdvanceCandidateStore
import com.finaxis.platform.lifecycle.application.BusinessDateAutoAdvance
import com.finaxis.platform.lifecycle.application.BusinessDateAutoAdvanceProperties
import com.finaxis.platform.lifecycle.application.BusinessDateAutoAdvanceProperties.Mode
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationSettingsStore
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import com.finaxis.platform.lifecycle.domain.TenantSettingValueType
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scheduled business-date advance (#277, ADR 0041) against PostgreSQL: an opted-in tenant's
 * open date moves one day per transaction to its local date, as the system actor, with the same
 * history and audit a manual advance writes; a tenant that has not opted in, a date that is not
 * open, the per-run cap and the OFF and DRY_RUN modes all hold it back.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BusinessDateAutoAdvanceIntegrationTests(
    private val dsl: DSLContext,
    private val candidates: AutoAdvanceCandidateStore,
    private val businessDates: BusinessDateService,
    private val settings: OrganisationSettingsStore,
    private val transactions: TransactionTemplate,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `an opted-in tenant is advanced a day at a time to its local date as the system`() {
        val organisationId = tenantOn(START, optedIn = true)

        autoAdvance(Mode.ON).run()

        assertEquals(LOCAL_TODAY, businessDate(organisationId))
        val history =
            dsl
                .select(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE, BUSINESS_DATE_HISTORY.ACTOR_ID)
                .from(BUSINESS_DATE_HISTORY)
                .where(BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId))
                .and(BUSINESS_DATE_HISTORY.EVENT_TYPE.eq("ADVANCED"))
                .orderBy(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE)
                .fetch { it.value1() to it.value2() }
        assertEquals(
            listOf(START.plusDays(1), START.plusDays(2), LOCAL_TODAY).map { it to SystemActor.ID },
            history,
        )
        assertEquals(
            3,
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ORGANISATION_ID
                    .eq(organisationId)
                    .and(AUDIT_EVENT.ACTION.eq("business_date.advance"))
                    .and(AUDIT_EVENT.ACTOR_TYPE.eq("SYSTEM")),
            ),
        )

        // The date has reached the local date: the next run has nothing to do.
        autoAdvance(Mode.ON).run()
        assertEquals(LOCAL_TODAY, businessDate(organisationId))
    }

    @Test
    fun `the per-run cap stops a long gap part way and the next run carries on`() {
        val organisationId = tenantOn(START, optedIn = true)

        autoAdvance(Mode.ON, maxSteps = 2).run()
        assertEquals(START.plusDays(2), businessDate(organisationId))

        autoAdvance(Mode.ON, maxSteps = 2).run()
        assertEquals(LOCAL_TODAY, businessDate(organisationId))
    }

    @Test
    fun `a tenant that has not opted in or whose date is not open is left alone`() {
        val notOptedIn = tenantOn(START, optedIn = false)
        val switchedOff = tenantOn(START, optedIn = true)
        setFlag(switchedOff, "false")
        val closing = tenantOn(START, optedIn = true)
        dsl
            .update(BUSINESS_DATE)
            .set(BUSINESS_DATE.STATUS, "CLOSING")
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(closing))
            .execute()

        autoAdvance(Mode.ON).run()

        listOf(notOptedIn, switchedOff, closing).forEach {
            assertEquals(START, businessDate(it))
        }
    }

    @Test
    fun `an opt-out committed after the run read its candidates stops the next day`() {
        val organisationId = tenantOn(START, optedIn = true)
        // The run read the tenant while it was opted in; it opts out before the first day moves.
        val stale =
            object : AutoAdvanceCandidateStore by candidates {
                override fun countAutoAdvanceCandidates() = 1

                override fun autoAdvanceCandidates(
                    limit: Int,
                    offset: Int,
                ) = listOf(AutoAdvanceCandidate(organisationId, "Africa/Nairobi", START))
            }
        setFlag(organisationId, "false")

        val run = autoAdvance(Mode.ON, store = stale).run()

        assertEquals(START, businessDate(organisationId))
        assertEquals(mapOf("NOT_OPTED_IN" to 1), run.skipped)
    }

    @Test
    fun `candidates are read in organisation-id windows that cover every tenant`() {
        val opted = List(3) { tenantOn(START, optedIn = true) }
        val total = candidates.countAutoAdvanceCandidates()

        val paged = (0 until total).flatMap { candidates.autoAdvanceCandidates(1, it) }

        val ids = paged.map { it.organisationId }
        assertEquals(total, ids.distinct().size, "each window holds different tenants")
        assertEquals(ids.sorted(), ids, "in organisation-id order")
        assertTrue(ids.containsAll(opted))
    }

    @Test
    fun `off and dry run advance nothing`() {
        val organisationId = tenantOn(START, optedIn = true)

        val off = autoAdvance(Mode.OFF).run()
        val dryRun = autoAdvance(Mode.DRY_RUN).run()

        assertEquals(0, off.advanced)
        assertEquals(Mode.DRY_RUN, dryRun.mode)
        assertEquals(START, businessDate(organisationId))
    }

    private fun autoAdvance(
        mode: Mode,
        maxSteps: Int = 7,
        store: AutoAdvanceCandidateStore = candidates,
    ) = BusinessDateAutoAdvance(
        store,
        businessDates,
        BusinessDateAutoAdvanceProperties(mode = mode, maxStepsPerRun = maxSteps),
        CLOCK,
    )

    /** An ACTIVE tenant in Nairobi whose OPEN business date is [date]. */
    private fun tenantOn(
        date: LocalDate,
        optedIn: Boolean,
    ): UUID {
        val organisationId = fixture.createActiveOrganisation("auto-advance", seedUser())
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .where(ORGANISATION.ID.eq(organisationId))
            .execute()
        dsl
            .update(BUSINESS_DATE)
            .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, date)
            .set(BUSINESS_DATE.STATUS, "OPEN")
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
            .execute()
        if (optedIn) setFlag(organisationId, "true")
        return organisationId
    }

    private fun setFlag(
        organisationId: UUID,
        value: String,
    ) {
        transactions.executeWithoutResult {
            settings.upsertSetting(
                organisationId,
                TenantSettingCatalog.BUSINESS_DATE_AUTO_ADVANCE_KEY,
                value,
                TenantSettingValueType.BOOLEAN.name,
                false,
                SystemActor.ID,
            )
        }
    }

    private fun businessDate(organisationId: UUID): LocalDate? =
        dsl
            .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
            .from(BUSINESS_DATE)
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
            .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE)

    private fun seedUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "auto-advance-$id")
            .set(USER_ACCOUNT.EMAIL, "auto-advance-$id@auto-advance.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Auto Advance Admin")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        return id
    }

    private companion object {
        val START: LocalDate = LocalDate.parse("2026-11-01")

        /** 22:30 UTC is 01:30 the next day in Nairobi: the local date, not the UTC one, counts. */
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-03T22:30:00Z"), ZoneOffset.UTC)
        val LOCAL_TODAY: LocalDate = LocalDate.parse("2026-11-04")
    }
}
