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
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
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

    @TestConfiguration(proxyBeanMethods = false)
    class TestProviders {
        @Bean
        fun fakeSavingsLedger() = FakeSavingsLedger()
    }

    @Test
    fun `a control account is created through the chart service, classification enforced`() {
        val tenant = provisionTenant("control-classify")

        val control =
            withRequestContext {
                chart.create(
                    CreateGlAccountCommand(
                        organisationId = tenant.organisationId,
                        actorId = MAKER,
                        code = AccountCode("2110"),
                        name = "Member deposits control",
                        accountClass = AccountClass.LIABILITY,
                        usage = AccountUsage.POSTABLE,
                        isControlAccount = true,
                        controlSubledgerKind = ControlSubledgerKind.SAVINGS_DEPOSITS,
                    ),
                )
            }
        assertTrue(control.isControlAccount)
        assertEquals(ControlSubledgerKind.SAVINGS_DEPOSITS, control.controlSubledgerKind)

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

    /** The stand-in for a product module's subsidiary ledger. Answers whatever the test sets. */
    class FakeSavingsLedger : SubledgerProofProvider {
        var balance: BigDecimal = BigDecimal.ZERO

        override val providerName = "savings-fake"

        override fun supports(kind: ControlSubledgerKind) =
            kind == ControlSubledgerKind.SAVINGS_DEPOSITS

        override fun aggregate(query: SubledgerProofQuery) =
            SubledgerAggregate(
                balance,
                query.currencyCode,
                positionCount = 2,
                detail = mapOf("newestPosition" to "SAV-0002"),
            )
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
    ) {
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(MAKER, null, null, null),
            ),
        ) {
            withRequestContext {
                transactions.execute {
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
