package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.balances.BalanceKey
import com.finaxis.platform.accounting.application.balances.DailyBalanceProjectionService
import com.finaxis.platform.accounting.application.balances.DailyBalanceStore
import com.finaxis.platform.accounting.application.balances.DriftKind
import com.finaxis.platform.accounting.application.balances.ProveDailyBalancesQuery
import com.finaxis.platform.accounting.application.balances.RebuildDailyBalancesCommand
import com.finaxis.platform.accounting.application.reconciliation.LedgerBalanceQuery
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
 * What the daily-balance projection does, proved against PostgreSQL through the service.
 *
 * The journals are inserted directly rather than posted through the engine. That is deliberate:
 * these tests are about what the *projection* makes of a set of journal rows, and they need
 * business and posting dates that the engine would refuse to let them choose freely — a backdated
 * correction recorded three days after the day it lands on is the central case here, and it has to
 * be arranged rather than waited for.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DailyBalanceProjectionIntegrationTests(
    private val dsl: DSLContext,
    private val projection: DailyBalanceProjectionService,
    private val store: DailyBalanceStore,
    private val balances: LedgerBalanceQuery,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a build writes one row per moved series and none for a series that did not move`() {
        val tenant = fixture.createTenant("projection-build")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")

        projection.settleDay(tenant.organisationId, day)

        val rows = projectedRows(tenant.organisationId)
        // Two accounts moved - the debit and the credit side of one journal - and no others.
        assertEquals(2, rows.size)
        assertEquals(
            BigDecimal("100.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, day),
        )
        assertEquals(
            BigDecimal("-100.000000"),
            closingOf(tenant.organisationId, tenant.creditAccountId, tenant.branchId, day),
        )
    }

    @Test
    fun `a second day carries the first day's closing balance forward`() {
        val tenant = fixture.createTenant("projection-carry-forward")
        val first = LocalDate.of(2026, 8, 10)
        val second = LocalDate.of(2026, 8, 11)
        postJournal(tenant, businessDate = first, postingDate = first, amount = "100.000000")
        postJournal(tenant, businessDate = second, postingDate = second, amount = "40.000000")

        projection.settleDay(tenant.organisationId, first)
        projection.settleDay(tenant.organisationId, second)

        assertEquals(
            BigDecimal("140.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, second),
        )
    }

    @Test
    fun `a backdated posting rebuilds its series from the backdated day forward`() {
        val tenant = fixture.createTenant("projection-backdated")
        val first = LocalDate.of(2026, 8, 10)
        val second = LocalDate.of(2026, 8, 11)
        val recordedOn = LocalDate.of(2026, 8, 12)
        postJournal(tenant, businessDate = first, postingDate = first, amount = "100.000000")
        postJournal(tenant, businessDate = second, postingDate = second, amount = "40.000000")
        projection.settleDay(tenant.organisationId, first)
        projection.settleDay(tenant.organisationId, second)

        // Recorded on the 12th, landing on the 10th: the day the 10th and the 11th were both
        // already projected for. This is the case the whole build design exists to handle.
        postJournal(tenant, businessDate = recordedOn, postingDate = first, amount = "7.000000")
        projection.settleDay(tenant.organisationId, recordedOn)

        assertEquals(
            BigDecimal("107.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, first),
            "the backdated day itself is recomputed",
        )
        assertEquals(
            BigDecimal("147.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, second),
            "and so is every day after it, because the opening balance is carried forward",
        )
    }

    @Test
    fun `a reversal nets to zero with no special handling anywhere`() {
        val tenant = fixture.createTenant("projection-reversal")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        // The mirror image: the same accounts, the opposite directions. A REVERSAL journal writes
        // ordinary lines, so the sums cancel without the projection knowing what a reversal is.
        postJournal(
            tenant,
            businessDate = day,
            postingDate = day,
            amount = "100.000000",
            debitAccountId = tenant.creditAccountId,
            creditAccountId = tenant.debitAccountId,
        )

        projection.settleDay(tenant.organisationId, day)

        assertEquals(
            BigDecimal("0.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, day),
        )
    }

    @Test
    fun `the build is idempotent, so running it twice changes nothing`() {
        val tenant = fixture.createTenant("projection-idempotent")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")

        projection.settleDay(tenant.organisationId, day)
        val first = projectedRows(tenant.organisationId)
        projection.settleDay(tenant.organisationId, day)
        val second = projectedRows(tenant.organisationId)

        assertEquals(first, second)
    }

    @Test
    fun `the proof catches a corrupted row, and a rebuild repairs it`() {
        val tenant = fixture.createTenant("projection-drift")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        projection.settleDay(tenant.organisationId, day)

        // Corrupt one row the way a bug would: a number that no longer matches its journal lines.
        dsl
            .update(GL_ACCOUNT_DAILY_BALANCE)
            .set(GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL, BigDecimal("999.000000"))
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(tenant.organisationId))
            .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(tenant.debitAccountId))
            .execute()

        val drift = store.drift(tenant.organisationId, tenant.debitAccountId, day, day)
        assertEquals(1, drift.size)
        assertEquals(DriftKind.AMOUNTS_DISAGREE, drift.single().kind)

        projection.settleDay(tenant.organisationId, day)

        assertTrue(
            store.drift(tenant.organisationId, tenant.debitAccountId, day, day).isEmpty(),
            "a sound projection returns no drift",
        )
    }

    @Test
    fun `the proof sees a row the journal has no lines for, which an inner join would miss`() {
        val tenant = fixture.createTenant("projection-phantom")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        projection.settleDay(tenant.organisationId, day)

        // A phantom: a projected day the journal never had. Only a FULL OUTER JOIN sees it, and it
        // is the direction that would silently add money to a balance.
        dsl
            .update(GL_ACCOUNT_DAILY_BALANCE)
            .set(GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE, day.plusDays(1))
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(tenant.organisationId))
            .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(tenant.debitAccountId))
            .execute()

        val drift = store.drift(tenant.organisationId, tenant.debitAccountId, day, day.plusDays(1))
        assertEquals(
            setOf(DriftKind.ABSENT_FROM_JOURNAL, DriftKind.MISSING_FROM_PROJECTION),
            drift.map { it.kind }.toSet(),
        )
    }

    @Test
    fun `an as-of balance is exact for a day the projection has not been built for yet`() {
        val tenant = fixture.createTenant("projection-checkpoint-delta")
        val built = LocalDate.of(2026, 8, 10)
        val unbuilt = LocalDate.of(2026, 8, 11)
        postJournal(tenant, businessDate = built, postingDate = built, amount = "100.000000")
        projection.settleDay(tenant.organisationId, built)
        // Recorded after the build, on a day nothing has rolled over yet - the ordinary state of
        // affairs when a reconciliation runs for today.
        postJournal(tenant, businessDate = unbuilt, postingDate = unbuilt, amount = "25.000000")

        assertEquals(
            BigDecimal("125.000000"),
            balances.signedBalanceAsOf(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                unbuilt,
            ),
            "the checkpoint is the 10th and the journal supplies the 11th",
        )
    }

    @Test
    fun `an as-of balance sees a posting backdated onto a day already projected`() {
        val tenant = fixture.createTenant("projection-backdated-after-build")
        val landsOn = LocalDate.of(2026, 8, 10)
        val recordedOn = LocalDate.of(2026, 8, 12)
        postJournal(tenant, businessDate = landsOn, postingDate = landsOn, amount = "100.000000")
        projection.settleDay(tenant.organisationId, landsOn)
        // Recorded on the 12th, dated the 10th, and no build has run since. The projection's
        // latest row for this account is the 10th, so a checkpoint taken there is stale and a
        // delta bounded by "posting dates after the 10th" contains nothing. Both halves would
        // miss this line, and a control-account reconciliation would report the difference as a
        // break in the sub-ledger.
        postJournal(tenant, businessDate = recordedOn, postingDate = landsOn, amount = "7.000000")

        assertEquals(
            BigDecimal("107.000000"),
            balances.signedBalanceAsOf(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                landsOn,
            ),
            "the checkpoint retreats behind the earliest unprojected posting date",
        )
    }

    @Test
    fun `a tenant-wide as-of balance counts every branch and head office`() {
        val tenant = fixture.createTenant("projection-tenant-wide")
        val other = fixture.insertBranch(tenant.organisationId, "BR2")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        postJournal(tenant, day, day, amount = "30.000000", branchId = other)
        postJournal(tenant, day, day, amount = "5.000000", branchId = null)
        projection.settleDay(tenant.organisationId, day)

        // A null branch on this port means EVERY branch, not head office only. Reading it the other
        // way would answer 5 here and turn every default reconciliation run into a false BREAK.
        assertEquals(
            BigDecimal("135.000000"),
            balances.signedBalanceAsOf(tenant.organisationId, tenant.debitAccountId, null, day),
        )
        assertEquals(
            BigDecimal("100.000000"),
            balances.signedBalanceAsOf(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                day,
            ),
        )
    }

    @Test
    fun `writing journals moves nothing in the projection until a build runs`() {
        val tenant = fixture.createTenant("projection-not-in-posting-path")
        val day = LocalDate.of(2026, 8, 10)

        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")

        // The projection is built after the fact by the business-date rollover, never inside the
        // posting transaction (INV-12, ADR 0027). Writing it there would put a per-account write
        // hotspot on every posting - the precise reason a stored `gl_account_balance` is a table
        // the schema refuses to create. `FoundationAtomicityProbes.glAccountDailyBalanceRows`
        // exists so this stays asserted rather than assumed.
        assertEquals(0, projectedRows(tenant.organisationId).size)

        projection.settleDay(tenant.organisationId, day)

        assertEquals(2, projectedRows(tenant.organisationId).size)
    }

    @Test
    fun `a day whose build never ran is caught up by the next build`() {
        val tenant = fixture.createTenant("projection-catch-up")
        val first = LocalDate.of(2026, 8, 10)
        val missed = LocalDate.of(2026, 8, 11)
        val later = LocalDate.of(2026, 8, 13)
        postJournal(tenant, businessDate = first, postingDate = first, amount = "100.000000")
        projection.settleDay(tenant.organisationId, first)

        // The build for the 11th never runs - a lost enqueue, a process that died between the
        // advance committing and the callback firing. Two more days pass before the next one does.
        postJournal(tenant, businessDate = missed, postingDate = missed, amount = "40.000000")
        postJournal(tenant, businessDate = later, postingDate = later, amount = "7.000000")
        projection.settleDay(tenant.organisationId, later)

        // A window fixed at one trailing day reaches only the 12th, so the 11th stays unprojected
        // for ever while the watermark moves past it. Starting from the watermark reaches it.
        assertEquals(
            BigDecimal("140.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, missed),
            "the skipped day is built by the next build that runs",
        )
        assertEquals(
            BigDecimal("147.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, later),
        )
    }

    @Test
    fun `a series rebuilt from a date opens from the journal rather than from zero`() {
        val tenant = fixture.createTenant("projection-cold-open")
        val first = LocalDate.of(2026, 8, 10)
        val second = LocalDate.of(2026, 8, 11)
        val third = LocalDate.of(2026, 8, 12)
        postJournal(tenant, businessDate = first, postingDate = first, amount = "100.000000")
        postJournal(tenant, businessDate = second, postingDate = second, amount = "40.000000")
        postJournal(tenant, businessDate = third, postingDate = third, amount = "7.000000")
        val actor =
            actorWith(tenant.organisationId, "cold", AccountingPermissions.RECONCILIATION_RUN)

        // Nothing is projected, and the repair is asked to start at the third day. The two earlier
        // days are real history the projection has no row for; assuming zero would understate
        // every later balance by exactly their sum, consistently enough that neither the journal
        // comparison nor the chain proof would notice.
        projection.rebuild(
            RebuildDailyBalancesCommand(
                organisationId = tenant.organisationId,
                accountId = tenant.debitAccountId,
                fromDate = third,
                actorId = actor,
                reason = "operator repair under test",
            ),
        )

        assertEquals(
            BigDecimal("147.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, third),
            "the opening carries the history the projection never held",
        )
    }

    @Test
    fun `a journal recorded on the watermark's own business date still reaches the balance`() {
        val tenant = fixture.createTenant("projection-watermark-edge")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        projection.settleDay(tenant.organisationId, day)

        // A backdated posting takes no lock on business_date, so one that read the 10th can commit
        // after the build for the 10th has already queried it. The watermark says the 10th, and a
        // delta bounded by "recorded strictly after the 10th" would never see this line - while
        // the checkpoint at the 10th was taken before it existed.
        postJournal(tenant, businessDate = day, postingDate = day, amount = "25.000000")

        assertEquals(
            BigDecimal("125.000000"),
            balances.signedBalanceAsOf(
                tenant.organisationId,
                tenant.debitAccountId,
                tenant.branchId,
                day,
            ),
            "the straggler is in the delta, because the watermark's own day is not safely closed",
        )
    }

    @Test
    fun `an operator rebuild leaves an audit record naming the actor, the scope and the reason`() {
        val tenant = fixture.createTenant("projection-rebuild-audit")
        val day = LocalDate.of(2026, 8, 10)
        postJournal(tenant, businessDate = day, postingDate = day, amount = "100.000000")
        projection.settleDay(tenant.organisationId, day)
        val actor =
            actorWith(tenant.organisationId, "auditor", AccountingPermissions.RECONCILIATION_RUN)

        projection.rebuild(
            RebuildDailyBalancesCommand(
                organisationId = tenant.organisationId,
                accountId = tenant.debitAccountId,
                fromDate = day,
                actorId = actor,
                reason = "suspected drift after an incident",
            ),
        )

        // A permission check proves the actor was allowed to do this. Only the audit row records
        // that they did, over what range, and why - which is the question asked afterwards.
        val record =
            dsl
                .select(
                    AUDIT_EVENT.ACTOR_USER_ID,
                    AUDIT_EVENT.ENTITY_ID,
                    AUDIT_EVENT.REASON,
                    AUDIT_EVENT.SEVERITY,
                    AUDIT_EVENT.OUTCOME,
                ).from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant.organisationId))
                .and(AUDIT_EVENT.ACTION.eq(AccountingAuditActions.DAILY_BALANCE_REBUILD))
                .fetchOne()
        assertNotNull(record, "the rebuild must leave an audit trail")
        assertEquals(actor, record.get(AUDIT_EVENT.ACTOR_USER_ID))
        assertEquals(tenant.debitAccountId, record.get(AUDIT_EVENT.ENTITY_ID))
        assertEquals("suspected drift after an incident", record.get(AUDIT_EVENT.REASON))
        assertEquals("CRITICAL", record.get(AUDIT_EVENT.SEVERITY))
        assertEquals("SUCCESS", record.get(AUDIT_EVENT.OUTCOME))
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

    private fun projectedRows(organisationId: UUID) =
        dsl
            .select(
                GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID,
                GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID,
                GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE,
                GL_ACCOUNT_DAILY_BALANCE.OPENING_SIGNED_FUNCTIONAL,
                GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL,
                GL_ACCOUNT_DAILY_BALANCE.CREDIT_FUNCTIONAL,
                GL_ACCOUNT_DAILY_BALANCE.LINE_COUNT,
            ).from(GL_ACCOUNT_DAILY_BALANCE)
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(organisationId))
            .orderBy(
                GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID,
                GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE,
            ).fetch()
            .map { it.intoList() }

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
