package com.finaxis.platform.accounting.schema

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.PERIOD_DAY
import com.finaxis.platform.accounting.schema.JournalSchemaFixture.Companion.assertViolates
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_DAILY_BALANCE
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
 * What PostgreSQL enforces about the daily-balance projection, proved against PostgreSQL.
 *
 * Every rule is stated in `docs/database/accounting-erd.md` under the issue #47 column definitions;
 * each rejection asserts the **named** constraint rather than merely the exception type, so a test
 * cannot pass because a different constraint happened to fire first.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GlAccountDailyBalanceSchemaIntegrationTests(
    private val dsl: DSLContext,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a row records a day's movement and generates its movement and closing balances`() {
        val tenant = fixture.createTenant("daily-balance-schema")

        insertRow(
            tenant.organisationId,
            tenant.debitAccountId,
            tenant.branchId,
            opening = "40.000000",
        )

        val stored =
            dsl
                .select(
                    GL_ACCOUNT_DAILY_BALANCE.MOVEMENT_SIGNED_FUNCTIONAL,
                    GL_ACCOUNT_DAILY_BALANCE.CLOSING_SIGNED_FUNCTIONAL,
                ).from(GL_ACCOUNT_DAILY_BALANCE)
                .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(tenant.organisationId))
                .fetchOne()!!

        // 100 debit, 30 credit: the movement is +70, and the closing is the 40 brought forward
        // plus that movement.
        assertEquals(
            BigDecimal("70.000000"),
            stored[GL_ACCOUNT_DAILY_BALANCE.MOVEMENT_SIGNED_FUNCTIONAL],
        )
        assertEquals(
            BigDecimal("110.000000"),
            stored[GL_ACCOUNT_DAILY_BALANCE.CLOSING_SIGNED_FUNCTIONAL],
        )
    }

    @Test
    fun `two head-office rows for one account and day collide rather than coexist`() {
        val tenant = fixture.createTenant("daily-balance-null-branch")

        insertRow(tenant.organisationId, tenant.debitAccountId, branchId = null)

        // The case NULLS NOT DISTINCT exists for. Under the default NULLS DISTINCT this second row
        // would be accepted, and the projection would hold two contradictory balances for one key
        // with neither of them violating the constraint.
        assertViolates("uq_gl_account_daily_balance_key") {
            insertRow(
                tenant.organisationId,
                tenant.debitAccountId,
                branchId = null,
                debit = "5.000000",
            )
        }
    }

    @Test
    fun `a branch row and a head-office row for one account and day are different keys`() {
        val tenant = fixture.createTenant("daily-balance-branch-split")

        insertRow(tenant.organisationId, tenant.debitAccountId, tenant.branchId)
        insertRow(tenant.organisationId, tenant.debitAccountId, branchId = null)

        assertEquals(
            2,
            dsl
                .fetchCount(
                    GL_ACCOUNT_DAILY_BALANCE,
                    GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(tenant.organisationId),
                ),
        )
    }

    @Test
    fun `a zero-movement row is refused, from both sides`() {
        val tenant = fixture.createTenant("daily-balance-sparse")

        // Sparseness is not a convention the build is trusted to honour. A zero row would answer
        // an as-of read for a day the account did not move, which is exactly what the "latest row
        // wins" reasoning assumes cannot happen.
        assertViolates("chk_gl_account_daily_balance_movement") {
            insertRow(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                debit = "0.000000",
                credit = "0.000000",
            )
        }
        assertViolates("chk_gl_account_daily_balance_line_count") {
            insertRow(tenant.organisationId, tenant.debitAccountId, tenant.branchId, lineCount = 0)
        }
    }

    @Test
    fun `a negative side total, a bad currency and a foreign account are refused`() {
        val tenant = fixture.createTenant("daily-balance-checks")
        val other = fixture.createTenant("daily-balance-checks-other")

        assertViolates("chk_gl_account_daily_balance_debit") {
            insertRow(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                debit = "-1.000000",
            )
        }
        assertViolates("chk_gl_account_daily_balance_credit") {
            insertRow(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                credit = "-1.000000",
            )
        }
        assertViolates("chk_gl_account_daily_balance_currency") {
            insertRow(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                currency = "ke1",
            )
        }
        assertViolates("fk_gl_account_daily_balance_account") {
            insertRow(tenant.organisationId, other.debitAccountId, tenant.branchId)
        }
        assertViolates("fk_gl_account_daily_balance_branch") {
            insertRow(tenant.organisationId, tenant.debitAccountId, other.branchId)
        }
    }

    @Suppress("LongParameterList")
    private fun insertRow(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID? = null,
        postingDate: LocalDate = PERIOD_DAY,
        opening: String = "0.000000",
        debit: String = "100.000000",
        credit: String = "30.000000",
        lineCount: Int = 2,
        currency: String = "KES",
    ) {
        dsl
            .insertInto(GL_ACCOUNT_DAILY_BALANCE)
            .set(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID, organisationId)
            .set(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID, accountId)
            .set(GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID, branchId)
            .set(GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE, currency)
            .set(GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE, postingDate)
            .set(GL_ACCOUNT_DAILY_BALANCE.OPENING_SIGNED_FUNCTIONAL, BigDecimal(opening))
            .set(GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL, BigDecimal(debit))
            .set(GL_ACCOUNT_DAILY_BALANCE.CREDIT_FUNCTIONAL, BigDecimal(credit))
            .set(GL_ACCOUNT_DAILY_BALANCE.LINE_COUNT, lineCount)
            .set(GL_ACCOUNT_DAILY_BALANCE.BUILT_AT, OffsetDateTime.now())
            .set(GL_ACCOUNT_DAILY_BALANCE.BUILT_FOR_BUSINESS_DATE, postingDate)
            .execute()
    }
}
