package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.assertViolates
import com.finaxis.platform.jooq.tables.references.CONTROL_ACCOUNT_RECONCILIATION_RUN
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
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
 * What PostgreSQL enforces about control accounts and reconciliation runs, proved against
 * PostgreSQL. Every rule is stated in `docs/database/accounting-erd.md` under the issue #46 column
 * definitions; each rejection asserts the named constraint.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ControlAccountSchemaIntegrationTests(
    private val dsl: DSLContext,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a control account is postable, single-kind and closed to manual posting`() {
        val tenant = fixture.createTenant("control-schema")
        fixture.insertControlAccount(
            tenant.organisationId,
            "2100",
            "LIABILITY",
            ControlSubledgerKind.SAVINGS_DEPOSITS,
        )

        assertViolates("chk_gl_account_control_kind_present") {
            insertAccount(tenant.organisationId, "2101", control = true, kind = null)
        }
        assertViolates("chk_gl_account_control_kind_present") {
            insertAccount(tenant.organisationId, "2102", control = false, kind = "SAVINGS_DEPOSITS")
        }
        assertViolates("chk_gl_account_control_kind") {
            insertAccount(tenant.organisationId, "2103", control = true, kind = "PETTY_CASH")
        }
        assertViolates("chk_gl_account_control_postable") {
            insertAccount(
                tenant.organisationId,
                "2104",
                control = true,
                kind = "SAVINGS_DEPOSITS",
                usage = "HEADER",
            )
        }
        assertViolates("chk_gl_account_control_no_manual_posting") {
            insertAccount(
                tenant.organisationId,
                "2105",
                control = true,
                kind = "SAVINGS_DEPOSITS",
                manual = true,
            )
        }
    }

    @Test
    fun `a reconciliation run is consistent evidence`() {
        val tenant = fixture.createTenant("recon-schema")
        val control =
            fixture.insertControlAccount(
                tenant.organisationId,
                "2100",
                "LIABILITY",
                ControlSubledgerKind.SAVINGS_DEPOSITS,
            )

        val matched =
            insertRun(tenant.organisationId, control, gl = "-100", sub = "-100", status = "MATCHED")
        assertEquals(
            BigDecimal.ZERO.setScale(6),
            dsl
                .select(CONTROL_ACCOUNT_RECONCILIATION_RUN.DIFFERENCE)
                .from(CONTROL_ACCOUNT_RECONCILIATION_RUN)
                .where(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID.eq(matched))
                .fetchOne(CONTROL_ACCOUNT_RECONCILIATION_RUN.DIFFERENCE),
            "difference is generated from the two balances",
        )

        assertViolates("chk_control_account_reconciliation_run_matched") {
            insertRun(tenant.organisationId, control, gl = "-100", sub = "-90", status = "MATCHED")
        }
        insertRun(
            tenant.organisationId,
            control,
            gl = "-100",
            sub = "-90",
            status = "MATCHED",
            tolerance = "10",
        )
    }

    @Test
    fun `a reconciliation run is bounded in status, tolerance and tenant`() {
        val tenant = fixture.createTenant("recon-schema-bounds")
        val other = fixture.createTenant("recon-schema-bounds-other")
        val control =
            fixture.insertControlAccount(
                tenant.organisationId,
                "2100",
                "LIABILITY",
                ControlSubledgerKind.SAVINGS_DEPOSITS,
            )

        // A verdict must agree with its numbers in both directions: an equal-balance BREAK is
        // evidence that contradicts itself.
        assertViolates("chk_control_account_reconciliation_run_break") {
            insertRun(tenant.organisationId, control, gl = "-100", sub = "-100", status = "BREAK")
        }
        assertViolates("chk_control_account_reconciliation_run_resolved") {
            insertRun(tenant.organisationId, control, gl = "-100", sub = "-90", status = "RESOLVED")
        }
        assertViolates("chk_control_account_reconciliation_run_status") {
            insertRun(tenant.organisationId, control, gl = "-100", sub = "-90", status = "OPEN")
        }
        assertViolates("chk_control_account_reconciliation_run_tolerance") {
            insertRun(
                tenant.organisationId,
                control,
                gl = "-100",
                sub = "-100",
                status = "BREAK",
                tolerance = "-1",
            )
        }
        assertViolates("fk_control_account_reconciliation_run_account") {
            insertRun(other.organisationId, control, gl = "0", sub = "0", status = "MATCHED")
        }
        assertViolates("fk_control_account_reconciliation_run_branch") {
            insertRun(
                tenant.organisationId,
                control,
                gl = "0",
                sub = "0",
                status = "MATCHED",
                branchId = other.branchId,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun insertAccount(
        organisationId: UUID,
        code: String,
        control: Boolean,
        kind: String?,
        usage: String = "POSTABLE",
        manual: Boolean = false,
    ) {
        dsl
            .insertInto(GL_ACCOUNT)
            .set(GL_ACCOUNT.ORGANISATION_ID, organisationId)
            .set(GL_ACCOUNT.ACCOUNT_CODE, code)
            .set(GL_ACCOUNT.ACCOUNT_NAME, "Account $code")
            .set(GL_ACCOUNT.ACCOUNT_CLASS, "LIABILITY")
            .set(GL_ACCOUNT.ACCOUNT_USAGE, usage)
            .set(GL_ACCOUNT.MANUAL_POSTING_ALLOWED, manual)
            .set(GL_ACCOUNT.IS_CONTROL_ACCOUNT, control)
            .set(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND, kind)
            .set(GL_ACCOUNT.STATUS, "ACTIVE")
            .set(GL_ACCOUNT.CREATED_AT, OffsetDateTime.now())
            .set(GL_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }

    @Suppress("LongParameterList")
    private fun insertRun(
        organisationId: UUID,
        accountId: UUID,
        gl: String,
        sub: String,
        status: String,
        tolerance: String = "0",
        branchId: UUID? = null,
    ): UUID =
        dsl
            .insertInto(CONTROL_ACCOUNT_RECONCILIATION_RUN)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID, organisationId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_ACCOUNT_ID, accountId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.BRANCH_ID, branchId)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CONTROL_SUBLEDGER_KIND, "SAVINGS_DEPOSITS")
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.AS_OF_DATE, LocalDate.of(2026, 8, 31))
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CURRENCY_CODE, "KES")
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.GL_BALANCE, BigDecimal(gl))
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.SUBLEDGER_BALANCE, BigDecimal(sub))
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.TOLERANCE, BigDecimal(tolerance))
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.STATUS, status)
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.PROVIDER, "test")
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.CREATED_AT, OffsetDateTime.now())
            .set(CONTROL_ACCOUNT_RECONCILIATION_RUN.UPDATED_AT, OffsetDateTime.now())
            .returning(CONTROL_ACCOUNT_RECONCILIATION_RUN.ID)
            .fetchOne()!!
            .id!!
}
