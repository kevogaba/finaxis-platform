package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.CreateGlAccountCommand
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.reconciliation.ControlAccountReconciliationService
import com.finaxis.platform.accounting.application.reconciliation.ListReconciliationRunsQuery
import com.finaxis.platform.accounting.application.reconciliation.ReconciliationStatus
import com.finaxis.platform.accounting.application.reconciliation.ResolveReconciliationCommand
import com.finaxis.platform.accounting.application.reconciliation.RunReconciliationCommand
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.CONTROL_ACCOUNT_RECONCILIATION_RUN
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Control accounts and the GL-to-subledger proof against PostgreSQL (#46).
 *
 * No product module exists, so the subsidiary ledger is a test [SubledgerProofProvider] bean whose
 * answer each test sets - which is exactly the seam a real savings module will implement, and
 * proves the reconciliation reaches it only through the public port.
 */
@Import(
    PostgresTestConfiguration::class,
    ControlAccountReconciliationIntegrationTests.TestProviders::class,
)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ControlAccountReconciliationIntegrationTests(
    private val reconciliation: ControlAccountReconciliationService,
    private val chart: ChartOfAccountsService,
    private val engine: PostingEngine,
    private val savings: FakeSavingsLedger,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)
    private val newTransaction =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    /** The provider bean is shared, so one test's interleaving must not reach the next. */
    @BeforeEach
    fun resetProvider() {
        savings.reset()
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestProviders {
        @Bean
        fun fakeSavingsLedger(dsl: DSLContext) = FakeSavingsLedger(dsl)
    }

    @Test
    fun `a control account is created through the chart service, classification enforced`() {
        val tenant = provisionTenant("control-classify")

        // SHARE_CAPITAL rather than SAVINGS_DEPOSITS: the fixture already gave this tenant its one
        // deposits control account, and a tenant has at most one control account per class.
        val control =
            withRequestContext {
                chart.create(
                    CreateGlAccountCommand(
                        organisationId = tenant.organisationId,
                        actorId = MAKER,
                        code = AccountCode("2110"),
                        name = "Member share capital control",
                        accountClass = AccountClass.EQUITY,
                        usage = AccountUsage.POSTABLE,
                        isControlAccount = true,
                        controlSubledgerKind = ControlSubledgerKind.SHARE_CAPITAL,
                    ),
                )
            }
        assertTrue(control.isControlAccount)
        assertEquals(ControlSubledgerKind.SHARE_CAPITAL, control.controlSubledgerKind)

        // A control account can never take a manual entry, and a header can never be one.
        val manual =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    chart.create(
                        CreateGlAccountCommand(
                            organisationId = tenant.organisationId,
                            actorId = MAKER,
                            code = AccountCode("2101"),
                            name = "Bad control",
                            accountClass = AccountClass.LIABILITY,
                            usage = AccountUsage.POSTABLE,
                            manualPostingAllowed = true,
                            isControlAccount = true,
                            controlSubledgerKind = ControlSubledgerKind.SAVINGS_DEPOSITS,
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.CONTROL_ACCOUNT_INVALID, manual.code)
        assertFailsWith<InvalidOperationException> {
            withRequestContext {
                chart.create(
                    CreateGlAccountCommand(
                        organisationId = tenant.organisationId,
                        actorId = MAKER,
                        code = AccountCode("2102"),
                        name = "Header control",
                        accountClass = AccountClass.LIABILITY,
                        usage = AccountUsage.HEADER,
                        isControlAccount = true,
                        controlSubledgerKind = ControlSubledgerKind.SAVINGS_DEPOSITS,
                    ),
                )
            }
        }
    }

    @Test
    fun `a matching proof and a break are deterministic and recorded as evidence`() {
        val tenant = provisionTenant("control-proof")
        // Two deposits of 600 and 400 credited to the control account: the GL says -1000.
        post(tenant, "dep-1", "600.00")
        post(tenant, "dep-2", "400.00")
        val journalRows = ledgerRows(tenant)

        savings.balance = BigDecimal("-1000.00")
        val matched = withRequestContext { reconciliation.run(runCommand(tenant)) }
        assertEquals(ReconciliationStatus.MATCHED, matched.status)
        assertEquals(BigDecimal("-1000.000000"), matched.glBalance)
        assertEquals(BigDecimal("-1000.000000"), matched.subledgerBalance)
        assertEquals(BigDecimal.ZERO.setScale(6), matched.difference)
        assertEquals("savings-fake", matched.provider)
        // JSONB round-trips the count as a plain number, so compare its value, not its boxing.
        assertEquals(2, (matched.detail["positionCount"] as Number).toInt())

        savings.balance = BigDecimal("-950.00")
        val broken = withRequestContext { reconciliation.run(runCommand(tenant)) }
        assertEquals(ReconciliationStatus.BREAK, broken.status)
        assertEquals(BigDecimal("-50.000000"), broken.difference)

        // Repeatable: the same scope and date, the same two numbers.
        val again = withRequestContext { reconciliation.run(runCommand(tenant)) }
        assertEquals(broken.glBalance, again.glBalance)
        assertEquals(broken.subledgerBalance, again.subledgerBalance)

        // A tolerance is an explicit, recorded policy; exact equality is the default.
        val tolerated =
            withRequestContext { reconciliation.run(runCommand(tenant, tolerance = "50")) }
        assertEquals(ReconciliationStatus.MATCHED, tolerated.status)
        assertEquals(BigDecimal("50.000000"), tolerated.tolerance)

        assertEquals(journalRows, ledgerRows(tenant), "reconciliation never touches a journal")
        val page =
            withRequestContext {
                reconciliation.listRuns(
                    ListReconciliationRunsQuery(
                        tenant.organisationId,
                        MAKER,
                        tenant.controlAccountId,
                        pageSize = 3,
                    ),
                )
            }
        assertEquals(3, page.items.size)
        assertNotNull(page.nextCursor)
    }

    @Test
    fun `as-of and branch scope bound the proof`() {
        val tenant = provisionTenant("control-scope")
        post(tenant, "dep-1", "300.00")

        savings.balance = BigDecimal.ZERO
        val beforeAnyPosting =
            withRequestContext {
                reconciliation.run(runCommand(tenant, asOf = tenant.businessDate.minusDays(1)))
            }
        assertEquals(BigDecimal.ZERO.setScale(6), beforeAnyPosting.glBalance)
        assertEquals(ReconciliationStatus.MATCHED, beforeAnyPosting.status)

        savings.balance = BigDecimal("-300.00")
        val branchScoped =
            withRequestContext {
                reconciliation.run(runCommand(tenant, branchId = tenant.branchId))
            }
        assertEquals(BigDecimal("-300.000000"), branchScoped.glBalance)
        assertEquals(tenant.branchId, branchScoped.branchId)
    }

    @Test
    fun `both sides of a proof read one snapshot, and the provider is told which`() {
        val tenant = provisionTenant("control-snapshot")
        post(tenant, "dep-1", "500.00")
        savings.balance = BigDecimal("-500.00")

        val run = withRequestContext { reconciliation.run(runCommand(tenant)) }
        assertEquals(ReconciliationStatus.MATCHED, run.status)

        // The provider ran inside accounting's own transaction, so it inherited the snapshot the
        // general-ledger aggregate was read from - which is only a guarantee if that transaction's
        // snapshot cannot move under it. READ COMMITTED here would mean the two numbers the row
        // records were never true at the same instant.
        assertEquals("repeatable read", savings.observedIsolation)

        // And it was handed a snapshot identity it could adopt had it read elsewhere. Exported
        // snapshots are opaque, so the contract is that one exists and is not blank.
        val query = assertNotNull(savings.lastQuery)
        assertTrue(query.snapshotId.isNotBlank(), "the provider was given a snapshot to adopt")
        assertEquals(tenant.organisationId, query.organisationId)
        assertEquals(ControlSubledgerKind.SAVINGS_DEPOSITS, query.kind)

        // And the run row says which snapshot it was, so the evidence describes its own
        // provenance rather than asking a reader to take the two numbers on trust.
        assertEquals(query.snapshotId, run.detail["snapshotId"])
    }

    /**
     * The regression the whole snapshot contract exists for.
     *
     * A sub-ledger that moves in lockstep with its control account is always reconciled - unless
     * the two sides are read at different instants. A posting committing between them is then
     * counted by whichever side read later, and the run records a difference that was never true.
     * With one snapshot the interleaved posting is invisible to both sides, and the proof still
     * matches; under `READ COMMITTED` this test records a `BREAK` of exactly the amount that
     * committed mid-proof.
     */
    @Test
    fun `a posting committing between the two reads changes neither side of the proof`() {
        val tenant = provisionTenant("control-straddle")
        post(tenant, "dep-1", "1000.00")
        savings.mirrorsAccount = tenant.controlAccountId
        savings.duringAggregate = { postInNewTransaction(tenant, "dep-straddle", "500.00") }

        val run = withRequestContext { reconciliation.run(runCommand(tenant)) }

        assertEquals(ReconciliationStatus.MATCHED, run.status)
        assertEquals(BigDecimal("-1000.000000"), run.glBalance)
        assertEquals(BigDecimal("-1000.000000"), run.subledgerBalance)

        // The interleaved posting is real and committed - it simply belongs to a later proof.
        savings.duringAggregate = null
        val after = withRequestContext { reconciliation.run(runCommand(tenant)) }
        assertEquals(BigDecimal("-1500.000000"), after.glBalance)
        assertEquals(ReconciliationStatus.MATCHED, after.status)
    }

    /**
     * A published error code nothing can raise is worse than no code at all.
     *
     * Spring drops a declared isolation level without a word when the method joins a transaction
     * that is already open, so `@Transactional(isolation = REPEATABLE_READ)` is a request and not
     * a guarantee. `ProofSnapshot` asks PostgreSQL what is actually in force; this is the call that
     * proves the refusal fires rather than a torn proof being recorded as evidence.
     */
    @Test
    fun `a proof refuses to run inside a transaction whose snapshot can move under it`() {
        val tenant = provisionTenant("control-outer-transaction")

        val failure =
            assertFailsWith<ConflictException> {
                transactions.execute {
                    withRequestContext { reconciliation.run(runCommand(tenant)) }
                }
            }

        assertEquals(PostingErrorCodes.RECONCILIATION_SNAPSHOT_UNAVAILABLE, failure.code)
        assertEquals(0, runRowCount(tenant), "a refused proof records no evidence")
    }

    /**
     * The guard asks for a stable snapshot, not for one named level.
     *
     * `SERIALIZABLE` holds one snapshot for the life of the transaction as `REPEATABLE READ` does,
     * and adds predicate locking on top; refusing it would reject a caller whose guarantee is
     * strictly stronger than the one the proof demands - and a tenant who raised the isolation of
     * the whole application, which #108 proposes doing, would find reconciliation the one thing
     * that stopped working.
     */
    @Test
    fun `a proof runs inside an outer serializable transaction`() {
        val tenant = provisionTenant("control-outer-serializable")
        savings.balance = BigDecimal.ZERO
        val serializable =
            TransactionTemplate(transactionManager).apply {
                isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE
            }

        val run =
            serializable.execute {
                withRequestContext { reconciliation.run(runCommand(tenant)) }
            }

        assertEquals(ReconciliationStatus.MATCHED, run?.status)
    }

    @Test
    fun `a scope naming a branch of another organisation is refused before either side is read`() {
        val tenant = provisionTenant("control-branch-scope")
        val elsewhere = provisionTenant("control-branch-other")
        savings.balance = BigDecimal.ZERO
        val runsBefore = runRowCount(tenant)

        val unknown =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    reconciliation.run(runCommand(tenant, branchId = elsewhere.branchId))
                }
            }
        assertEquals(PostingErrorCodes.BRANCH_NOT_IN_ORGANISATION, unknown.code)

        val absent =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { reconciliation.run(runCommand(tenant, branchId = uuidV7())) }
            }
        assertEquals(PostingErrorCodes.BRANCH_NOT_IN_ORGANISATION, absent.code)

        // Refused up front, so nothing was computed, no provider was asked about a scope that does
        // not exist, and no half-formed evidence reached the table.
        assertEquals(runsBefore, runRowCount(tenant))
    }

    @Test
    fun `a tenant has at most one control account per sub-ledger class`() {
        val tenant = provisionTenant("control-one-per-class")

        val second =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    chart.create(
                        CreateGlAccountCommand(
                            organisationId = tenant.organisationId,
                            actorId = MAKER,
                            code = AccountCode("2199"),
                            name = "Second deposits control",
                            accountClass = AccountClass.LIABILITY,
                            usage = AccountUsage.POSTABLE,
                            isControlAccount = true,
                            controlSubledgerKind = ControlSubledgerKind.SAVINGS_DEPOSITS,
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.CONTROL_ACCOUNT_DUPLICATE, second.code)

        // Another class is free, because the query a provider answers names the class.
        withRequestContext {
            chart.create(
                CreateGlAccountCommand(
                    organisationId = tenant.organisationId,
                    actorId = MAKER,
                    code = AccountCode("3100"),
                    name = "Share capital control",
                    accountClass = AccountClass.EQUITY,
                    usage = AccountUsage.POSTABLE,
                    isControlAccount = true,
                    controlSubledgerKind = ControlSubledgerKind.SHARE_CAPITAL,
                ),
            )
        }
    }

    @Test
    fun `a break is resolved by a different actor with a reason, and the sign-off is audited`() {
        val tenant = provisionTenant("control-resolve")
        post(tenant, "dep-1", "100.00")
        savings.balance = BigDecimal("-90.00")
        val broken = withRequestContext { reconciliation.run(runCommand(tenant)) }

        val self =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext {
                    resolve(tenant, MAKER, broken.id, "I ran it")
                }
            }
        assertEquals(PostingErrorCodes.RECONCILIATION_SELF_RESOLUTION, self.code)

        assertFailsWith<InvalidOperationException> {
            withRequestContext {
                resolve(tenant, tenant.checker, broken.id, " ")
            }
        }

        val resolved =
            withRequestContext {
                reconciliation.resolve(
                    ResolveReconciliationCommand(
                        tenant.organisationId,
                        tenant.checker,
                        broken.id,
                        "Timing: deposit posted after cut-off",
                    ),
                )
            }
        assertEquals(ReconciliationStatus.RESOLVED, resolved.status)
        assertEquals(tenant.checker, resolved.resolvedBy)
        assertNotNull(resolved.resolvedAt)

        val twice =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    resolve(tenant, tenant.checker, broken.id, "Again")
                }
            }
        assertEquals(PostingErrorCodes.RECONCILIATION_NOT_A_BREAK, twice.code)

        val audit =
            dsl
                .selectFrom(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant.organisationId))
                .and(AUDIT_EVENT.ACTION.eq("reconciliation.resolve"))
                .fetchOne()
        assertNotNull(audit)
        assertEquals(tenant.checker, audit.actorUserId)
        assertEquals("Timing: deposit posted after cut-off", audit.reason)
    }

    @Test
    fun `matched runs do not resolve, plain accounts do not run, a missing provider is named`() {
        val tenant = provisionTenant("control-refusals")
        savings.balance = BigDecimal.ZERO
        val matched = withRequestContext { reconciliation.run(runCommand(tenant)) }

        val notBreak =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    resolve(tenant, tenant.checker, matched.id, "No")
                }
            }
        assertEquals(PostingErrorCodes.RECONCILIATION_NOT_A_BREAK, notBreak.code)

        val notControl =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    reconciliation.run(runCommand(tenant).copy(accountId = tenant.cashAccountId))
                }
            }
        assertEquals(PostingErrorCodes.NOT_A_CONTROL_ACCOUNT, notControl.code)

        val loans =
            schema.insertControlAccount(
                tenant.organisationId,
                "1300",
                "ASSET",
                ControlSubledgerKind.LOAN_PRINCIPAL,
            )
        val noProvider =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    reconciliation.run(runCommand(tenant).copy(accountId = loans))
                }
            }
        assertEquals(PostingErrorCodes.SUBLEDGER_PROVIDER_MISSING, noProvider.code)

        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { reconciliation.run(runCommand(tenant).copy(actorId = STRANGER)) }
        }
        assertNull(
            dsl
                .selectFrom(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
                .fetchAny(),
            "no proof wrote a journal",
        )
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * The stand-in for a product module's subsidiary ledger. Answers whatever the test sets, and
     * records what accounting asked it, so the port's own obligations can be asserted from the
     * provider's side rather than inferred from the run row.
     */
    class FakeSavingsLedger(
        private val dsl: DSLContext,
    ) : SubledgerProofProvider {
        var balance: BigDecimal = BigDecimal.ZERO

        var lastQuery: SubledgerProofQuery? = null
            private set

        /**
         * The isolation the provider itself observed, read on accounting's own connection.
         *
         * This is the half of the snapshot guarantee a run row cannot show: a provider reading in
         * accounting's transaction inherits its snapshot, and that is only worth anything if the
         * transaction is one whose snapshot does not move.
         */
        var observedIsolation: String? = null
            private set

        /**
         * Runs inside `aggregate`, between accounting's two reads. The interleaving seam.
         *
         * A test uses it to commit a posting mid-proof, which is the exact hazard the snapshot
         * exists to close and which no assertion about the run row alone can reach.
         */
        var duringAggregate: (() -> Unit)? = null

        /**
         * When set, the provider reports this general-ledger account's own total instead of
         * [balance] - a sub-ledger that moves in lockstep with the control account, which is what
         * a real one does. It reads on accounting's connection, so what it sees *is* the snapshot
         * question.
         */
        var mirrorsAccount: UUID? = null

        override val providerName = "savings-fake"

        override fun supports(kind: ControlSubledgerKind) =
            kind == ControlSubledgerKind.SAVINGS_DEPOSITS

        override fun aggregate(query: SubledgerProofQuery): SubledgerAggregate {
            lastQuery = query
            observedIsolation =
                dsl.fetchValue(
                    DSL.field("current_setting('transaction_isolation')", String::class.java),
                )
            duringAggregate?.invoke()
            val account = mirrorsAccount
            return SubledgerAggregate(
                if (account == null) balance else mirroredBalance(query.organisationId, account),
                query.currencyCode,
                positionCount = 2,
                detail = mapOf("newestPosition" to "SAV-0002"),
            )
        }

        /** Resets every hook, so one test's interleaving cannot leak into the next. */
        fun reset() {
            balance = BigDecimal.ZERO
            duringAggregate = null
            mirrorsAccount = null
        }

        private fun mirroredBalance(
            organisationId: UUID,
            accountId: UUID,
        ): BigDecimal =
            dsl
                .select(
                    DSL.coalesce(DSL.sum(JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT), BigDecimal.ZERO),
                ).from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
                .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId))
                .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
    }

    private data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val businessDate: LocalDate,
        val cashAccountId: UUID,
        val controlAccountId: UUID,
        val checker: UUID,
    )

    private fun provisionTenant(label: String): Tenant {
        val organisationId = tenants.createActiveOrganisation(label, MAKER)
        val checker = newChecker("$label-${UUID.randomUUID()}")
        tenants.grantTenantAdmin(organisationId, checker)
        grantDirectly(organisationId, MAKER, AccountingPermissions.RECONCILIATION_RUN)
        grantDirectly(organisationId, MAKER, AccountingPermissions.RECONCILIATION_RESOLVE)
        grantDirectly(organisationId, checker, AccountingPermissions.RECONCILIATION_RESOLVE)
        val businessDate =
            requireNotNull(
                dsl
                    .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                    .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
            )
        val branchId =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_CODE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            )
        openPeriodCovering(organisationId, businessDate)
        return Tenant(
            organisationId = organisationId,
            branchId = branchId,
            businessDate = businessDate,
            cashAccountId = schema.insertAccount(organisationId, "1010", "ASSET"),
            controlAccountId =
                schema.insertControlAccount(
                    organisationId,
                    "2100",
                    "LIABILITY",
                    ControlSubledgerKind.SAVINGS_DEPOSITS,
                ),
            checker = checker,
        )
    }

    /** How much evidence this tenant's control account has accumulated so far. */
    private fun runRowCount(tenant: Tenant): Int =
        dsl.fetchCount(
            CONTROL_ACCOUNT_RECONCILIATION_RUN,
            CONTROL_ACCOUNT_RECONCILIATION_RUN.ORGANISATION_ID.eq(tenant.organisationId),
        )

    private fun openPeriodCovering(
        organisationId: UUID,
        date: LocalDate,
    ) {
        val now = OffsetDateTime.now()
        val yearId =
            dsl
                .insertInto(ACCOUNTING_FISCAL_YEAR)
                .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, organisationId)
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, "FY${date.year}")
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, "Financial year ${date.year}")
                .set(ACCOUNTING_FISCAL_YEAR.START_DATE, date.withDayOfYear(1))
                .set(ACCOUNTING_FISCAL_YEAR.END_DATE, date.withDayOfYear(date.lengthOfYear()))
                .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now)
                .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now)
                .returning(ACCOUNTING_FISCAL_YEAR.ID)
                .fetchOne()!!
                .id!!
        dsl
            .insertInto(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, organisationId)
            .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, yearId)
            .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, date.monthValue)
            .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, "Period ${date.monthValue}")
            .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, date.withDayOfMonth(1))
            .set(ACCOUNTING_FISCAL_PERIOD.END_DATE, date.withDayOfMonth(date.lengthOfMonth()))
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, "OPEN")
            .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now)
            .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now)
            .execute()
    }

    /** A deposit: cash debited, the savings control account credited. */
    private fun post(
        tenant: Tenant,
        reference: String,
        amount: String,
    ) = post(tenant, reference, amount, transactions)

    /**
     * The same deposit, on a transaction of its own that commits immediately.
     *
     * `REQUIRES_NEW` suspends whatever transaction the caller is in and takes a second connection,
     * so a posting made from inside a running proof genuinely commits underneath it - which is the
     * only way to reproduce the interleaving the snapshot contract exists to survive.
     */
    private fun postInNewTransaction(
        tenant: Tenant,
        reference: String,
        amount: String,
    ) = post(tenant, reference, amount, newTransaction)

    private fun post(
        tenant: Tenant,
        reference: String,
        amount: String,
        template: TransactionTemplate,
    ) {
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(MAKER, null, null, null),
            ),
        ) {
            withRequestContext {
                template.execute {
                    engine.post(
                        LedgerPostingRequest(
                            context =
                                AccountingContext(tenant.organisationId, tenant.branchId, MAKER),
                            source =
                                AccountingSourceReference(
                                    "savings",
                                    "SAVINGS_DEPOSIT",
                                    uuidV7(),
                                    reference,
                                ),
                            eventCode = "SAVINGS_DEPOSIT",
                            entryType = JournalEntryType.STANDARD,
                        ),
                    ) {
                        ResolvedLegs(
                            listOf(
                                PostingLeg(tenant.cashAccountId, PostingSide.DEBIT, kes(amount)),
                                PostingLeg(
                                    tenant.controlAccountId,
                                    PostingSide.CREDIT,
                                    kes(amount),
                                    subledgerReference = "SAV-0001",
                                ),
                            ),
                            null,
                        )
                    }
                }
            }
        }
    }

    private fun runCommand(
        tenant: Tenant,
        asOf: LocalDate = tenant.businessDate,
        branchId: UUID? = null,
        tolerance: String = "0",
    ) = RunReconciliationCommand(
        organisationId = tenant.organisationId,
        actorId = MAKER,
        accountId = tenant.controlAccountId,
        asOfDate = asOf,
        branchId = branchId,
        tolerance = BigDecimal(tolerance),
    )

    @Test
    fun `a proof of a future date is refused before either ledger is read`() {
        val tenant = provisionTenant("recon-future")

        val future =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    reconciliation.run(
                        runCommand(tenant).copy(asOfDate = tenant.businessDate.plusDays(1)),
                    )
                }
            }
        assertEquals(PostingErrorCodes.RECONCILIATION_DATE_IN_FUTURE, future.code)
        assertEquals(
            emptyList(),
            withRequestContext {
                reconciliation
                    .listRuns(
                        ListReconciliationRunsQuery(
                            tenant.organisationId,
                            MAKER,
                            tenant.controlAccountId,
                        ),
                    ).items
            },
            "a refused proof records no evidence",
        )
    }

    private fun resolve(
        tenant: Tenant,
        actor: UUID,
        runId: UUID,
        reason: String,
    ) = reconciliation.resolve(
        ResolveReconciliationCommand(tenant.organisationId, actor, runId, reason),
    )

    private fun ledgerRows(tenant: Tenant): List<String> =
        dsl
            .selectFrom(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
            .fetch { it.toString() } +
            dsl
                .selectFrom(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))
                .fetch { it.toString() }

    private fun kes(amount: String) = MonetaryAmount(BigDecimal(amount), "KES")

    private fun newChecker(label: String): UUID {
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, "recon.checker.$label")
            .set(USER_ACCOUNT.EMAIL, "recon.checker.$label@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Reconciliation Checker")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .returning(USER_ACCOUNT.ID)
            .fetchOne()!!
            .id!!
    }

    private fun grantDirectly(
        organisationId: UUID,
        actorId: UUID,
        permissionCode: String,
    ) {
        val membershipId =
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.ID)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(actorId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID)
                ?: error("no membership for $actorId in $organisationId")
        val permissionId =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                .fetchOne(PERMISSION.ID)
                ?: error("permission $permissionCode is not seeded")
        val now = OffsetDateTime.now()
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

    private companion object {
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

        @Suppress("unused")
        val GL_ACCOUNT_TABLE = GL_ACCOUNT
    }
}
