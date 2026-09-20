package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.adapter.outbound.persistence.PostgresDailyBalanceProjectionLock
import com.finaxis.platform.accounting.application.balances.BalanceKey
import com.finaxis.platform.accounting.application.balances.DailyBalanceProjectionLock
import com.finaxis.platform.accounting.application.balances.DailyBalanceProjectionService
import com.finaxis.platform.accounting.application.balances.DailyBalanceStore
import com.finaxis.platform.accounting.application.balances.LedgerMovementSource
import com.finaxis.platform.accounting.config.AccountingProperties
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.audit.AuditService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * What [AccountingProperties.dailyBalanceTrailingDays] is actually for, proved by varying it.
 *
 * Split from `DailyBalanceProjectionIntegrationTests` rather than added to it, purely to keep that
 * file under detekt's `LargeClass` line budget - the scenario belongs beside its sibling tests in
 * every other sense, and reads as a continuation of them.
 *
 * A single missed build does not exercise this property: the build window always reaches back to
 * the watermark itself, inclusive, so a posting recorded one build late is caught whether the
 * trailing count is zero or the default one - `DailyBalanceProjectionIntegrationTests` already
 * covers that case (`a backdated posting rebuilds its series from the backdated day forward`).
 * What the trailing count changes is a posting that straddles *two* consecutive builds: committed
 * only after the watermark has already advanced past the business date it belongs to, and then
 * past it again. These tests hold every other collaborator fixed and vary only the property, by
 * constructing a second [DailyBalanceProjectionService] directly rather than through a second
 * Spring context - the same reason a unit test constructs its subject with fakes, applied here to
 * one property of an otherwise-real, container-backed service.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class DailyBalanceTrailingDaysIntegrationTests(
    private val dsl: DSLContext,
    private val store: DailyBalanceStore,
    private val journal: LedgerMovementSource,
    private val lock: DailyBalanceProjectionLock,
    private val tenants: AccountingTenantLookup,
    private val permissions: AccountingPermissionGuard,
    private val clock: Clock,
    private val auditService: AuditService,
    transactionManager: PlatformTransactionManager,
) {
    private val fixture = JournalSchemaFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)

    /**
     * A [DailyBalanceProjectionService] wired exactly like the Spring-managed one, except for
     * [trailingDays].
     *
     * Every other collaborator is the real adapter this test class itself was autowired with; only
     * [AccountingProperties.dailyBalanceTrailingDays] varies, which is the one thing this suite
     * needs to vary and the one thing no Spring context in this repository parameterizes per test.
     *
     * A plain constructor call, deliberately not through Spring's bean factory, which is what
     * makes [DailyBalanceProjectionService.settleDay]'s own `@Transactional` annotation inert here:
     * that annotation only does anything for a call through a Spring-managed proxy, and this
     * instance is never one. [settleDayInTransaction] is how a caller of this method supplies the
     * transaction that annotation would otherwise have opened -
     * [PostgresDailyBalanceProjectionLock] takes a transaction-scoped advisory lock and requires
     * one to already be active, so calling `settleDay` on this instance directly fails exactly the
     * way an autocommit caller of any other locking adapter in this package does.
     */
    private fun projectionWithTrailingDays(trailingDays: Int): DailyBalanceProjectionService =
        DailyBalanceProjectionService(
            store,
            journal,
            lock,
            tenants,
            permissions,
            AccountingProperties(dailyBalanceTrailingDays = trailingDays),
            clock,
            auditService,
        )

    /**
     * Runs [businessDate]'s build in its own transaction, exactly as the real
     * [DailyBalanceProjectionService] bean's `@Transactional settleDay` would if JobRunr's handler
     * had called it - each build commits (and releases the advisory lock it took) before the next
     * one starts, which is what lets three successive calls in this test see each other's watermark
     * the same way three successive JobRunr runs would.
     */
    private fun DailyBalanceProjectionService.settleDayInTransaction(
        organisationId: UUID,
        businessDate: LocalDate,
    ) {
        transactions.executeWithoutResult { settleDay(organisationId, businessDate) }
    }

    @Test
    fun `the default trailing window catches a posting that straddled two consecutive builds`() {
        val tenant = fixture.createTenant("projection-trailing-days-default")
        val defaultTrailing = AccountingProperties.DEFAULT_DAILY_BALANCE_TRAILING_DAYS
        val projection = projectionWithTrailingDays(defaultTrailing)
        val first = LocalDate.of(2026, 8, 10)
        val second = first.plusDays(1)
        val third = second.plusDays(1)
        postJournal(tenant, businessDate = first, postingDate = first, amount = "100.000000")
        projection.settleDayInTransaction(tenant.organisationId, first)
        postJournal(tenant, businessDate = second, postingDate = second, amount = "40.000000")
        projection.settleDayInTransaction(tenant.organisationId, second)

        // The straggler is business-dated on the FIRST day, but only committing now - after both
        // of that day's builds have already run and moved the watermark two days past it. That is
        // the race a fixed one-day trailing window absorbs and a single missed build does not
        // distinguish from: catching it takes re-scanning behind a watermark that has already
        // moved twice, not merely once.
        postJournal(tenant, businessDate = first, postingDate = first, amount = "7.000000")

        projection.settleDayInTransaction(tenant.organisationId, third)

        assertEquals(
            BigDecimal("147.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, second),
            "the default trailing window re-scans one day behind the watermark and catches it",
        )
    }

    @Test
    fun `a trailing window of zero misses the same straddling posting`() {
        val tenant = fixture.createTenant("projection-trailing-days-zero")
        val noTrailing = projectionWithTrailingDays(0)
        val first = LocalDate.of(2026, 8, 10)
        val second = first.plusDays(1)
        val third = second.plusDays(1)
        postJournal(tenant, businessDate = first, postingDate = first, amount = "100.000000")
        noTrailing.settleDayInTransaction(tenant.organisationId, first)
        postJournal(tenant, businessDate = second, postingDate = second, amount = "40.000000")
        noTrailing.settleDayInTransaction(tenant.organisationId, second)

        // The identical straggler the previous test proves the default window catches.
        postJournal(tenant, businessDate = first, postingDate = first, amount = "7.000000")

        noTrailing.settleDayInTransaction(tenant.organisationId, third)

        assertEquals(
            BigDecimal("140.000000"),
            closingOf(tenant.organisationId, tenant.debitAccountId, tenant.branchId, second),
            "with no re-scan behind the watermark the straggler is never picked up by a build, " +
                "which is why zero is permitted but not the default",
        )
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
