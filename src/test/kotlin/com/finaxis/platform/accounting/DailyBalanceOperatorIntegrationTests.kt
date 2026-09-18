package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.balances.BalanceKey
import com.finaxis.platform.accounting.application.balances.DailyBalanceProjectionService
import com.finaxis.platform.accounting.application.balances.DailyBalanceStore
import com.finaxis.platform.accounting.application.balances.DriftKind
import com.finaxis.platform.accounting.application.balances.ProveDailyBalancesQuery
import com.finaxis.platform.accounting.application.balances.RebuildDailyBalancesCommand
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_DAILY_BALANCE
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The operator's repair-and-prove surface over the daily-balance projection.
 *
 * Split from `DailyBalanceProjectionIntegrationTests`, which proves what the *build* does. These
 * prove what a person with `reconciliation.run` or `reconciliation.view` can do to it by hand, and
 * the two read differently: nothing here is about a rollover, and everything here is about an
 * authority being exercised, bounded and recorded.
 *
 * The journals are inserted directly rather than posted through the engine, for the same reason the
 * sibling class does it: these tests need business and posting dates the engine would refuse to let
 * them choose.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DailyBalanceOperatorIntegrationTests(
    private val dsl: DSLContext,
    private val projection: DailyBalanceProjectionService,
    private val store: DailyBalanceStore,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `an operator rebuild repairs a corrupted series through the same code path`() {
        val tenant = fixture.createTenant("projection-operator-rebuild")
        val day = LocalDate.of(2026, 8, 10)
        val actor =
            actorWith(tenant.organisationId, "rebuild", AccountingPermissions.RECONCILIATION_RUN)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        projection.settleDay(tenant.organisationId, day)
        corrupt(tenant.organisationId, tenant.debitAccountId)

        val series =
            projection.rebuild(
                RebuildDailyBalancesCommand(
                    organisationId = tenant.organisationId,
                    accountId = tenant.debitAccountId,
                    fromDate = day,
                    actorId = actor,
                    reason = "operator repair under test",
                ),
            )

        assertEquals(1, series, "one series was projected for this account")
        assertTrue(
            store.drift(tenant.organisationId, tenant.debitAccountId, day, day).isEmpty(),
            "the operator rebuild is the same recompute the routine build runs",
        )
    }

    @Test
    fun `a rebuild and a proof each refuse an actor without the reconciliation permission`() {
        val tenant = fixture.createTenant("projection-permissions")
        val day = LocalDate.of(2026, 8, 10)
        val stranger = actorWith(tenant.organisationId, "stranger", permissionCode = null)

        assertFailsWith<ForbiddenOperationException> {
            projection.rebuild(
                RebuildDailyBalancesCommand(
                    organisationId = tenant.organisationId,
                    accountId = tenant.debitAccountId,
                    fromDate = day,
                    actorId = stranger,
                    reason = "operator repair under test",
                ),
            )
        }
        assertFailsWith<ForbiddenOperationException> {
            projection.proveAccount(
                ProveDailyBalancesQuery(
                    organisationId = tenant.organisationId,
                    accountId = tenant.debitAccountId,
                    fromDate = day,
                    toDate = day,
                    actorId = stranger,
                ),
            )
        }
    }

    @Test
    fun `the operator proof reports drift, and refuses a range that ends before it starts`() {
        val tenant = fixture.createTenant("projection-operator-proof")
        val day = LocalDate.of(2026, 8, 10)
        val actor =
            actorWith(tenant.organisationId, "prover", AccountingPermissions.RECONCILIATION_VIEW)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        projection.settleDay(tenant.organisationId, day)

        assertTrue(
            projection
                .proveAccount(query(tenant.organisationId, tenant.debitAccountId, day, day, actor))
                .isEmpty(),
            "a sound projection proves clean",
        )

        corrupt(tenant.organisationId, tenant.debitAccountId)

        assertEquals(
            1,
            projection
                .proveAccount(query(tenant.organisationId, tenant.debitAccountId, day, day, actor))
                .size,
        )
        assertFailsWith<ConflictException> {
            projection.proveAccount(
                query(tenant.organisationId, tenant.debitAccountId, day, day.minusDays(1), actor),
            )
        }
    }

    @Test
    fun `an operator repair never raises the tenant watermark`() {
        val tenant = fixture.createTenant("projection-repair-watermark")
        val built = LocalDate.of(2026, 8, 10)
        val later = LocalDate.of(2026, 8, 20)
        postJournal(tenant, businessDate = built, postingDate = built, amount = "100.000000")
        projection.settleDay(tenant.organisationId, built)
        postJournal(tenant, businessDate = later, postingDate = later, amount = "40.000000")
        val actor =
            actorWith(tenant.organisationId, "repair", AccountingPermissions.RECONCILIATION_RUN)

        projection.rebuild(
            RebuildDailyBalancesCommand(
                organisationId = tenant.organisationId,
                accountId = tenant.debitAccountId,
                fromDate = later,
                actorId = actor,
                reason = "operator repair under test",
            ),
        )

        // fromDate is a posting date an operator chose. Stamping it would assert tenant-wide that
        // journals recorded up to the 20th are projected, which no build has established - and
        // every other account's as-of balance would then silently drop what that claim covers.
        assertEquals(
            built,
            store.projectionWatermark(tenant.organisationId),
            "repairing one account tells the reader nothing new about the rest",
        )
    }

    @Test
    fun `repairing an account with nothing projected rebuilds every branch it moved in`() {
        val tenant = fixture.createTenant("projection-repair-branches")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        postJournal(
            tenant,
            businessDate = day,
            postingDate = day,
            amount = "40.000000",
            branchId = null,
        )
        val actor =
            actorWith(tenant.organisationId, "branches", AccountingPermissions.RECONCILIATION_RUN)

        // A null branch is a wildcard to the repair and head office to a projection key. Turning
        // one into the other rebuilds head office alone and reports success.
        val rebuilt =
            projection.rebuild(
                RebuildDailyBalancesCommand(
                    organisationId = tenant.organisationId,
                    accountId = tenant.debitAccountId,
                    fromDate = day,
                    actorId = actor,
                    reason = "operator repair under test",
                ),
            )

        assertEquals(2, rebuilt, "both the branch series and the head-office series are repaired")
        assertEquals(
            BigDecimal("100.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, day),
        )
        assertEquals(
            BigDecimal("40.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, null, day),
        )
    }

    @Test
    fun `the opening chain proof catches an offset the journal comparison cannot see`() {
        val tenant = fixture.createTenant("projection-chain")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        projection.settleDay(tenant.organisationId, day)
        val actor =
            actorWith(tenant.organisationId, "chain", AccountingPermissions.RECONCILIATION_VIEW)

        // Displace the opening balance only. Debit, credit and line count still match the journal
        // exactly, so the day-at-a-time proof has nothing to report - while every closing balance
        // this series will ever answer is now out by five.
        dsl
            .update(GL_ACCOUNT_DAILY_BALANCE)
            .set(
                GL_ACCOUNT_DAILY_BALANCE.OPENING_SIGNED_FUNCTIONAL,
                BigDecimal("5.000000"),
            ).where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(tenant.organisationId))
            .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(tenant.debitAccountId))
            .execute()

        assertTrue(
            projection
                .proveAccount(query(tenant.organisationId, tenant.debitAccountId, day, day, actor))
                .isEmpty(),
            "the journal comparison sees nothing wrong, which is why this proof exists",
        )
        val breaks =
            projection.proveOpeningChain(
                query(tenant.organisationId, tenant.debitAccountId, day, day, actor),
            )
        assertEquals(1, breaks.size, "the first row of a series must open at zero")
        assertEquals(BigDecimal("5.000000"), breaks.single().recordedOpening)
        assertEquals(BigDecimal("0.000000"), breaks.single().expectedOpening)
    }

    private fun query(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
        actorId: UUID,
    ) = ProveDailyBalancesQuery(organisationId, accountId, fromDate, toDate, actorId)

    /** Makes a row disagree with the journal lines it aggregates, the way a defect would. */

    private fun corrupt(
        organisationId: UUID,
        accountId: UUID,
    ) {
        dsl
            .update(GL_ACCOUNT_DAILY_BALANCE)
            .set(GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL, BigDecimal("999.000000"))
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(accountId))
            .execute()
    }

    /**
     * An active member of the tenant, holding [permissionCode] when one is named.
     *
     * Written directly rather than through provisioning because these tests care about one thing -
     * whether the guard admits this actor - and a full tenant-admin fixture would make that the
     * least visible part of the setup.
     */

    private fun actorWith(
        organisationId: UUID,
        label: String,
        permissionCode: String?,
    ): UUID {
        val now = OffsetDateTime.now()
        val userId =
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.USERNAME, "balances.$label.${UUID.randomUUID()}")
                .set(USER_ACCOUNT.EMAIL, "balances.$label.${UUID.randomUUID()}@finaxis.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Balances $label")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .returning(USER_ACCOUNT.ID)
                .fetchOne()!!
                .id!!
        val membershipId =
            dsl
                .insertInto(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
                .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
                .set(USER_ORGANISATION_MEMBERSHIP.JOINED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
                .returning(USER_ORGANISATION_MEMBERSHIP.ID)
                .fetchOne()!!
                .id!!
        if (permissionCode != null) {
            val permissionId =
                dsl
                    .select(PERMISSION.ID)
                    .from(PERMISSION)
                    .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                    .fetchOne(PERMISSION.ID)
                    ?: error("permission $permissionCode is not seeded")
            dsl
                .insertInto(MEMBERSHIP_PERMISSION)
                .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
                .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
                .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
                .set(MEMBERSHIP_PERMISSION.EFFECT, "ALLOW")
                .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
                .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
                .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute()
        }
        return userId
    }

    @Suppress("LongParameterList")
    private fun postJournal(
        tenant: JournalSchemaFixture.Tenant,
        businessDate: LocalDate,
        postingDate: LocalDate,
        amount: String,
        debitAccountId: UUID = tenant.debitAccountId,
        creditAccountId: UUID = tenant.creditAccountId,
        branchId: UUID? = tenant.branchId,
    ) {
        val money = BigDecimal(amount)
        val journalId =
            fixture.insertJournalEntry(
                tenant,
                totalDebit = money,
                totalCredit = money,
                branchId = branchId,
                businessDate = businessDate,
                postingDate = postingDate,
            )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 1,
            glAccountId = debitAccountId,
            direction = "DEBIT",
            amount = money,
            postingDate = postingDate,
            branchId = branchId,
        )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 2,
            glAccountId = creditAccountId,
            direction = "CREDIT",
            amount = money,
            postingDate = postingDate,
            branchId = branchId,
        )
    }

    private fun closingOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        asOfDate: LocalDate,
    ): BigDecimal =
        store
            .latestRowAsOf(BalanceKey(organisationId, accountId, branchId, "KES"), asOfDate)
            ?.closingSignedFunctional ?: BigDecimal.ZERO
}
