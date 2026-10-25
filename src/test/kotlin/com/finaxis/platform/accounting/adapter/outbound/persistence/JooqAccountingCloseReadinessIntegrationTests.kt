package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.AccountingCloseReadiness
import com.finaxis.platform.accounting.CloseReadinessSnapshot
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.CONTROL_ACCOUNT_RECONCILIATION_RUN
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The three numbers close-of-business readiness reads from accounting (#168), proved against
 * PostgreSQL: pending-approval and draft manual journals of exactly the asked tenant, and the
 * control accounts whose **latest** reconciliation run is a `BREAK`, so a break a later run
 * superseded never counts.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JooqAccountingCloseReadinessIntegrationTests(
    private val readiness: AccountingCloseReadiness,
    private val dsl: DSLContext,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a tenant with no accounting data reports three zeros`() {
        val tenant = fixture.createTenant("close-empty")

        assertEquals(CloseReadinessSnapshot(0L, 0L, 0L), readiness.snapshot(tenant.organisationId))
    }

    @Test
    fun `manual journals are counted by status for the asked tenant only`() {
        val tenant = fixture.createTenant("close-journals")
        val other = fixture.createTenant("close-journals-other")
        repeat(3) { insertManualJournal(tenant.organisationId, "PENDING_APPROVAL") }
        repeat(2) { insertManualJournal(tenant.organisationId, "DRAFT") }
        insertManualJournal(tenant.organisationId, "CANCELLED")
        insertManualJournal(other.organisationId, "PENDING_APPROVAL")
        insertManualJournal(other.organisationId, "DRAFT")

        assertEquals(CloseReadinessSnapshot(3L, 2L, 0L), readiness.snapshot(tenant.organisationId))
        assertEquals(CloseReadinessSnapshot(1L, 1L, 0L), readiness.snapshot(other.organisationId))
    }

    @Test
    fun `only the latest run of each control account can be a break`() {
        val tenant = fixture.createTenant("close-breaks")
        val other = fixture.createTenant("close-breaks-other")
        val superseded =
            control(tenant.organisationId, "2100", ControlSubledgerKind.SAVINGS_DEPOSITS)
        val broken = control(tenant.organisationId, "2200", ControlSubledgerKind.SHARE_CAPITAL)
        val resolved = control(tenant.organisationId, "2300", ControlSubledgerKind.LOAN_PRINCIPAL)
        val foreign = control(other.organisationId, "2100", ControlSubledgerKind.SAVINGS_DEPOSITS)

        run(tenant.organisationId, superseded, 1, "BREAK")
        run(tenant.organisationId, superseded, 2, "MATCHED")
        run(tenant.organisationId, broken, 1, "MATCHED")
        run(tenant.organisationId, broken, 2, "BREAK")
        // A break marked RESOLVED is no longer a BREAK run at all.
        val resolvedRun = run(tenant.organisationId, resolved, 1, "BREAK")
        dsl
            .update(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.STATUS, "RESOLVED")
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.RESOLUTION_REASON, "reversed")
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.RESOLVED_AT, OffsetDateTime.now())
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.RESOLVED_BY, uuidV7())
            .where(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID.eq(resolvedRun))
            .execute()
        run(other.organisationId, foreign, 1, "BREAK")

        assertEquals(1L, readiness.snapshot(tenant.organisationId).latestBreakRuns)
        assertEquals(1L, readiness.snapshot(other.organisationId).latestBreakRuns)
    }

    private fun control(
        organisationId: UUID,
        code: String,
        kind: ControlSubledgerKind,
    ): UUID = fixture.insertControlAccount(organisationId, code, "LIABILITY", kind)

    private fun insertManualJournal(
        organisationId: UUID,
        status: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(MANUAL_JOURNAL)
            .set(MANUAL_JOURNAL.ORGANISATION_ID, organisationId)
            .set(MANUAL_JOURNAL.TITLE, "Adjustment")
            .set(MANUAL_JOURNAL.NARRATIVE, "Close readiness")
            .set(MANUAL_JOURNAL.STATUS, status)
            .set(MANUAL_JOURNAL.CREATED_AT, now)
            .set(MANUAL_JOURNAL.UPDATED_AT, now)
            .execute()
    }

    private fun run(
        organisationId: UUID,
        accountId: UUID,
        day: Int,
        status: String,
    ): UUID {
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID, organisationId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_ACCOUNT_ID, accountId)
            .set(
                CONTROL_ACCOUNT_RECONCILIATION_RUN.CONTROL_SUBLEDGER_KIND,
                dsl
                    .fetchOne(
                        "SELECT control_subledger_kind FROM gl_account WHERE id = ?",
                        accountId,
                    )!!
                    .get(0, String::class.java),
            ).set(CONTROL_ACCOUNT_RECONCILIATION_RUN.AS_OF_DATE, LocalDate.of(2026, 8, day))
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CURRENCY_CODE, "KES")
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_BALANCE, BigDecimal("-100"))
            .set(
                CONTROL_ACCOUNT_RECONCILIATION_RUN.SUBLEDGER_BALANCE,
                BigDecimal(if (status == "MATCHED") "-100" else "-90"),
            ).set(CONTROL_ACCOUNT_RECONCILIATION_RUN.STATUS, status)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.PROVIDER, "test")
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CREATED_AT, now)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.UPDATED_AT, now)
            .returning(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID)
            .fetchOne()!!
            .id!!
    }
}
