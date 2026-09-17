package com.finaxis.platform.accounting.application.reconciliation

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerAggregate
import com.finaxis.platform.accounting.SubledgerProofProvider
import com.finaxis.platform.accounting.SubledgerProofQuery
import com.finaxis.platform.accounting.application.GlAccountPage
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.support.FakeAccountingPermissionGuard
import com.finaxis.platform.accounting.support.FakeReconciliationRunStore
import com.finaxis.platform.accounting.support.PermissionCheckKind
import com.finaxis.platform.accounting.support.ReconciliationAccessKind
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.web.pagination.PaginationProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The detective control's decisions, driven with fakes so every refusal is affordable to state.
 *
 * `ControlAccountReconciliationIntegrationTests` proves the wiring, the `REPEATABLE READ`
 * isolation and the `V9` check constraints against a real database. What is stated here is the
 * part that is pure decision: the order the checks run in, which side is read before which, the
 * arithmetic at the tolerance boundary, and the separation of duties between the actor who ran a
 * proof and the one who signs its break off.
 *
 * Two of these are easy to get wrong in a way no integration test notices. The tolerance is
 * compared with `<=` on [BigDecimal], so it is `compareTo` and not `equals` that decides a
 * verdict - a fact only a case whose two amounts differ in *scale* can prove. And every refusal
 * carries an assertion that nothing was read: a proof that reaches a provider, exports a snapshot
 * and then refuses has done real work on behalf of a caller that was never allowed to ask.
 */
class ControlAccountReconciliationServiceTests {
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val accounts = FakeControlAccountStore()
    private val ledger = RecordingLedgerBalanceQuery()
    private val snapshots = RecordingProofSnapshot()
    private val runs = FakeReconciliationRunStore(clock)
    private val tenants = FakeReconciliationTenantLookup()
    private val businessDates = FakeReconciliationBusinessDateLookup()
    private val permissions = FakeAccountingPermissionGuard()
    private val audits = RecordingReconciliationAuditRepository()
    private val provider =
        RecordingProofProvider(PROVIDER_NAME, ControlSubledgerKind.SAVINGS_DEPOSITS)
    private val service = serviceWith()

    @BeforeEach
    fun seedTenantAndChart() {
        accounts.put(controlAccount())
        tenants.branches += BRANCH_ID
        businessDates.current =
            AccountingBusinessDate(ORGANISATION_ID, TODAY, postingAllowed = true)
        ledger.balance = BigDecimal("-1000.000000")
        provider.answer = SubledgerAggregate(BigDecimal("-1000"), CURRENCY, POSITION_COUNT)
        snapshots.snapshotId = SNAPSHOT_ID
    }

    @Test
    fun `a matched run records both balances, the provider and the snapshot it read them on`() {
        val run = service.run(runCommand())

        assertEquals(ReconciliationStatus.MATCHED, run.status)
        assertEquals(PROVIDER_NAME, run.provider)
        assertEquals(ControlSubledgerKind.SAVINGS_DEPOSITS, run.kind)
        assertEquals(CURRENCY, run.currencyCode)
        assertEquals(ACCOUNT_ID, run.accountId)
        assertEquals(TODAY, run.asOfDate)
        assertEquals(ACTOR_ID, run.runBy)
        assertNull(run.branchId)
        assertSameAmount("-1000", run.glBalance)
        assertSameAmount("-1000", run.subledgerBalance)
        assertSameAmount("0", run.difference)
        // The evidence describes its own provenance: without the snapshot id a reader sees two
        // numbers and has to take on trust that they were ever true at the same instant.
        assertEquals(SNAPSHOT_ID, run.detail["snapshotId"])
        assertEquals(POSITION_COUNT, run.detail["positionCount"])
        assertEquals(SNAPSHOT_ID, provider.queries.single().snapshotId)
        assertEquals(1, snapshots.calls, "one proof reads one snapshot")
        assertEquals(LedgerRead(ORGANISATION_ID, ACCOUNT_ID, null, TODAY), ledger.calls.single())
    }

    @Test
    fun `the run permission is the ordinary tenant check, made before anything is read`() {
        permissions.refuse(AccountingPermissions.RECONCILIATION_RUN)

        assertFailsWith<ForbiddenOperationException> { service.run(runCommand()) }

        // Reconciliation is not a break-glass control: it exercises no authority a system actor
        // would have to be denied, so the ordinary tenant check is the right one, and a suite that
        // only asserted "a check happened" would not notice the two being swapped.
        assertEquals(
            listOf(PermissionCheckKind.TENANT),
            permissions.checks.map { it.kind },
        )
        assertEquals(ORGANISATION_ID, permissions.checks.single().organisationId)
        assertEquals(ACTOR_ID, permissions.checks.single().actorId)
        assertTrue(accounts.lookups.isEmpty(), "a refused caller learns nothing about the chart")
        assertNothingProven()
    }

    @Test
    fun `a proof of a date the tenant has not reached, or with no business date, is refused`() {
        val future =
            assertFailsWith<InvalidOperationException> {
                service.run(runCommand(asOfDate = TODAY.plusDays(1)))
            }
        assertEquals(PostingErrorCodes.RECONCILIATION_DATE_IN_FUTURE, future.code)
        assertNothingProven()

        // The business date itself is the boundary and is a date that has happened.
        assertEquals(ReconciliationStatus.MATCHED, service.run(runCommand(asOfDate = TODAY)).status)

        businessDates.current = null
        val unavailable =
            assertFailsWith<ConflictException> { service.run(runCommand()) }
        assertEquals(PostingErrorCodes.BUSINESS_DATE_UNAVAILABLE, unavailable.code)
    }

    @Test
    fun `a branch that is not this organisation's is refused before either side is read`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                service.run(runCommand(branchId = OTHER_BRANCH_ID))
            }

        assertEquals(PostingErrorCodes.BRANCH_NOT_IN_ORGANISATION, failure.code)
        // Named refusal rather than a foreign-key violation at the very end, after both aggregates
        // have been computed and a provider asked about a scope that never existed.
        assertTrue(accounts.lookups.isEmpty(), "the scope is checked before the account is read")
        assertNothingProven()
    }

    @Test
    fun `a branch scope reaches the branch check, the ledger read, the provider and the row`() {
        val run = service.run(runCommand(branchId = BRANCH_ID))

        assertEquals(listOf(BRANCH_ID), tenants.branchChecks)
        assertEquals(
            LedgerRead(ORGANISATION_ID, ACCOUNT_ID, BRANCH_ID, TODAY),
            ledger.calls.single(),
        )
        val query = provider.queries.single()
        assertEquals(
            SubledgerProofQuery(
                organisationId = ORGANISATION_ID,
                branchId = BRANCH_ID,
                kind = ControlSubledgerKind.SAVINGS_DEPOSITS,
                asOfDate = TODAY,
                currencyCode = CURRENCY,
                snapshotId = SNAPSHOT_ID,
            ),
            query,
        )
        assertEquals(BRANCH_ID, run.branchId)
    }

    @Test
    fun `a whole-organisation scope asks the lifecycle module nothing about branches`() {
        service.run(runCommand())

        assertTrue(tenants.branchChecks.isEmpty(), "there is no branch to validate")
        assertNull(provider.queries.single().branchId)
    }

    @Test
    fun `an unknown account, and one that is not a control account, are refused`() {
        val unknown =
            assertFailsWith<ResourceNotFoundException> {
                service.run(runCommand(accountId = UNKNOWN_ACCOUNT_ID))
            }
        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, unknown.code)

        accounts.put(controlAccount(isControl = false, kind = null))
        val ordinary = assertFailsWith<InvalidOperationException> { service.run(runCommand()) }
        assertEquals(PostingErrorCodes.NOT_A_CONTROL_ACCOUNT, ordinary.code)
        assertNothingProven()
    }

    @Test
    fun `a tenant of another organisation cannot be proven through this one's account`() {
        accounts.put(controlAccount().copy(organisationId = OTHER_ORGANISATION_ID))

        val failure = assertFailsWith<ResourceNotFoundException> { service.run(runCommand()) }

        assertEquals(PostingErrorCodes.ACCOUNT_NOT_POSTABLE, failure.code)
        assertNothingProven()
    }

    @Test
    fun `a control account carrying no class is a defect rather than a break`() {
        // uq_gl_account_control_kind and chk_gl_account_control_kind make this unreachable through
        // the database; if it is ever reached the run must fail loudly rather than record evidence
        // about a subsidiary ledger nobody can name.
        accounts.put(controlAccount(isControl = true, kind = null))

        assertFailsWith<IllegalArgumentException> { service.run(runCommand()) }

        assertNothingProven()
    }

    @Test
    fun `an organisation with no functional currency cannot be proven`() {
        tenants.functionalCurrency = null

        val failure = assertFailsWith<ConflictException> { service.run(runCommand()) }

        assertEquals(PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE, failure.code)
        assertNothingProven()
    }

    @Test
    fun `a control class no module answers for is refused before the proof reads anything`() {
        val unclaimed = serviceWith(providers = emptyList())

        val failure = assertFailsWith<ConflictException> { unclaimed.run(runCommand()) }

        assertEquals(PostingErrorCodes.SUBLEDGER_PROVIDER_MISSING, failure.code)
        assertNothingProven()
    }

    @Test
    fun `a negative tolerance is refused before the proof reads anything`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                service.run(runCommand(tolerance = BigDecimal("-0.01")))
            }

        assertEquals(PostingErrorCodes.RECONCILIATION_TOLERANCE_INVALID, failure.code)
        assertNothingProven()
        // Zero is the default and is not negative: exact equality is the ordinary case.
        assertEquals(ReconciliationStatus.MATCHED, service.run(runCommand()).status)
    }

    @Test
    fun `a snapshot the transaction cannot offer aborts the proof before either side is read`() {
        snapshots.failure =
            ConflictException(
                code = PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE,
                safeDetail = "no stable snapshot",
            )

        val failure = assertFailsWith<ConflictException> { service.run(runCommand()) }

        assertEquals(PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE, failure.code)
        assertTrue(ledger.calls.isEmpty(), "neither side is read without a stable snapshot")
        assertTrue(provider.queries.isEmpty())
        assertTrue(runs.created.isEmpty())
    }

    @Test
    fun `the tolerance boundary is compared numerically, not by scale`() {
        provider.answer = SubledgerAggregate(BigDecimal("100"), CURRENCY, POSITION_COUNT)

        // The sub-ledger balance is stored at NUMERIC(23, 6), so the difference here is 0.100000
        // and the tolerance is 0.10. BigDecimal.equals would call those two different numbers.
        assertNotEquals(BigDecimal("0.10"), BigDecimal("0.100000"))
        assertEquals(ReconciliationStatus.MATCHED, verdict(gl = "100.10", tolerance = "0.10"))
        assertEquals(ReconciliationStatus.MATCHED, verdict(gl = "100.09", tolerance = "0.10"))
        assertEquals(ReconciliationStatus.BREAK, verdict(gl = "100.11", tolerance = "0.10"))
        // abs(): a sub-ledger ahead of the ledger is as much of a break as one behind it.
        assertEquals(ReconciliationStatus.MATCHED, verdict(gl = "99.90", tolerance = "0.10"))
        assertEquals(ReconciliationStatus.BREAK, verdict(gl = "99.89", tolerance = "0.10"))
        // And with the default tolerance, a hundredth is a break.
        assertEquals(ReconciliationStatus.BREAK, verdict(gl = "100.01", tolerance = "0"))
    }

    @Test
    fun `a scope with no sub-ledger positions is proven against zero`() {
        provider.answer = null
        ledger.balance = BigDecimal.ZERO

        val run = service.run(runCommand())

        assertEquals(ReconciliationStatus.MATCHED, run.status)
        assertSameAmount("0", run.subledgerBalance)
        assertEquals(0L, run.detail["positionCount"])
        assertEquals(SNAPSHOT_ID, run.detail["snapshotId"])

        // An empty sub-ledger against a non-zero control account is a break, not an absence.
        ledger.balance = BigDecimal("-1.000000")
        assertEquals(ReconciliationStatus.BREAK, service.run(runCommand()).status)
    }

    @Test
    fun `a provider answering in another currency is refused rather than converted`() {
        provider.answer = SubledgerAggregate(BigDecimal("-1000"), "USD", POSITION_COUNT)

        val failure = assertFailsWith<InvalidOperationException> { service.run(runCommand()) }

        assertEquals(PostingErrorCodes.CURRENCY_NOT_SUPPORTED, failure.code)
        assertTrue(runs.created.isEmpty(), "no row stores one currency for two different numbers")
    }

    @Test
    fun `a sub-ledger balance finer than the evidence row can store is refused`() {
        provider.answer = SubledgerAggregate(BigDecimal("0.0000001"), CURRENCY, POSITION_COUNT)

        val failure = assertFailsWith<InvalidOperationException> { service.run(runCommand()) }

        assertEquals(PostingErrorCodes.SUBLEDGER_BALANCE_PRECISION, failure.code)
        assertTrue(runs.created.isEmpty())

        // Trailing zeros are not precision: this is the same number at a wider scale, and it is
        // stored rather than refused.
        provider.answer = SubledgerAggregate(BigDecimal("-1000.0000000"), CURRENCY, POSITION_COUNT)
        assertSameAmount("-1000", service.run(runCommand()).subledgerBalance)
    }

    @Test
    fun `provider detail cannot contradict the position count or the snapshot`() {
        provider.answer =
            SubledgerAggregate(
                balance = BigDecimal("-1000"),
                currencyCode = CURRENCY,
                positionCount = POSITION_COUNT,
                detail =
                    mapOf(
                        "oldestPosition" to "2026-01-01",
                        "positionCount" to 999L,
                        "snapshotId" to "forged",
                    ),
            )

        val run = service.run(runCommand())

        assertEquals("2026-01-01", run.detail["oldestPosition"], "drill-down detail is kept")
        assertEquals(POSITION_COUNT, run.detail["positionCount"])
        assertEquals(SNAPSHOT_ID, run.detail["snapshotId"])
    }

    @Test
    fun `resolving a break records the sign-off and a critical audit`() {
        val broken = runs.seed(BREAK_RUN_ID, ReconciliationStatus.BREAK)

        val resolved = service.resolve(resolveCommand(broken.id))

        assertEquals(ReconciliationStatus.RESOLVED, resolved.status)
        assertEquals(CHECKER_ID, resolved.resolvedBy)
        assertEquals(NOW, resolved.resolvedAt)
        assertEquals(REASON, resolved.resolutionReason)
        assertEquals(listOf(AccountingPermissions.RECONCILIATION_RESOLVE), permissions.checkedCodes)
        // The different-actor check and the status move must see one snapshot, so the row is read
        // under the lock; the returned row is the re-read that follows the write.
        assertEquals(
            listOf(ReconciliationAccessKind.LOCK, ReconciliationAccessKind.FIND),
            runs.accesses.map { it.kind },
        )
        val audit = audits.saved.single()
        assertEquals(AccountingAuditActions.RECONCILIATION_RESOLVE, audit.action)
        assertEquals(AuditSeverity.CRITICAL, audit.severity)
        assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        assertEquals("USER", audit.actorType)
        assertEquals(CHECKER_ID.toString(), audit.actorId)
        assertEquals(ORGANISATION_ID.toString(), audit.tenantId)
        assertEquals(BRANCH_ID.toString(), audit.branchId)
        assertEquals("CONTROL_ACCOUNT_RECONCILIATION_RUN", audit.resourceType)
        assertEquals(broken.id.toString(), audit.resourceId)
        assertEquals(REASON, audit.reason)
        assertEquals(ACCOUNT_ID.toString(), audit.metadata["accountId"])
        assertEquals(TODAY.toString(), audit.metadata["asOfDate"])
        assertEquals(broken.difference.toPlainString(), audit.metadata["difference"])
    }

    @Test
    fun `the resolve permission is checked before the run is read`() {
        runs.seed(BREAK_RUN_ID, ReconciliationStatus.BREAK)
        permissions.refuse(AccountingPermissions.RECONCILIATION_RESOLVE)

        assertFailsWith<ForbiddenOperationException> { service.resolve(resolveCommand()) }

        assertEquals(listOf(PermissionCheckKind.TENANT), permissions.checks.map { it.kind })
        assertTrue(runs.accesses.isEmpty(), "a refused caller does not learn the run exists")
        assertTrue(audits.saved.isEmpty())
    }

    @Test
    fun `a blank reason is refused before the run is locked`() {
        runs.seed(BREAK_RUN_ID, ReconciliationStatus.BREAK)

        listOf("", "   ").forEach { blank ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    service.resolve(resolveCommand(reason = blank))
                }
            assertEquals(PostingErrorCodes.RECONCILIATION_REASON_REQUIRED, failure.code)
        }

        // A sign-off with nothing to say is not a sign-off, so the row is never even read.
        assertTrue(runs.accesses.isEmpty())
        assertEquals(ReconciliationStatus.BREAK, runs.runs.getValue(BREAK_RUN_ID).status)
        assertTrue(audits.saved.isEmpty())
    }

    @Test
    fun `a run of another tenant, or none at all, is not found`() {
        val unknown =
            assertFailsWith<ResourceNotFoundException> { service.resolve(resolveCommand()) }
        assertEquals(PostingErrorCodes.RECONCILIATION_RUN_NOT_FOUND, unknown.code)

        runs.seed(BREAK_RUN_ID, ReconciliationStatus.BREAK, organisationId = OTHER_ORGANISATION_ID)
        val foreign =
            assertFailsWith<ResourceNotFoundException> { service.resolve(resolveCommand()) }
        assertEquals(
            PostingErrorCodes.RECONCILIATION_RUN_NOT_FOUND,
            foreign.code,
            "another tenant's break is indistinguishable from one that does not exist",
        )
        assertEquals(ReconciliationStatus.BREAK, runs.runs.getValue(BREAK_RUN_ID).status)
        assertTrue(audits.saved.isEmpty())
    }

    @Test
    fun `only a break resolves`() {
        listOf(ReconciliationStatus.MATCHED, ReconciliationStatus.RESOLVED).forEach { status ->
            runs.seed(BREAK_RUN_ID, status)

            val failure = assertFailsWith<ConflictException> { service.resolve(resolveCommand()) }

            assertEquals(PostingErrorCodes.RECONCILIATION_NOT_A_BREAK, failure.code)
            assertEquals(status, runs.runs.getValue(BREAK_RUN_ID).status)
        }
        assertTrue(audits.saved.isEmpty(), "a refused sign-off records no CRITICAL evidence")
    }

    @Test
    fun `the actor who ran a proof cannot resolve its break`() {
        runs.seed(BREAK_RUN_ID, ReconciliationStatus.BREAK, runBy = ACTOR_ID)

        val failure =
            assertFailsWith<ForbiddenOperationException> {
                service.resolve(resolveCommand(actorId = ACTOR_ID))
            }

        // A named refusal, not the permission guard's generic one: the actor holds
        // reconciliation.resolve, and is refused for who they are on this particular run.
        assertEquals(PostingErrorCodes.RECONCILIATION_SELF_RESOLUTION, failure.code)
        assertEquals(
            listOf(AccountingPermissions.RECONCILIATION_RESOLVE),
            permissions.checkedCodes,
            "the permission passed; INV-10 is what refused",
        )
        assertEquals(ReconciliationStatus.BREAK, runs.runs.getValue(BREAK_RUN_ID).status)
        assertTrue(audits.saved.isEmpty())
    }

    @Test
    fun `a break that will not resolve under the lock fails loudly, not silently`() {
        val stubborn = UnresolvableRunStore(storedRun(BREAK_RUN_ID, ReconciliationStatus.BREAK))

        assertFailsWith<IllegalStateException> {
            serviceWith(store = stubborn).resolve(resolveCommand())
        }

        assertTrue(audits.saved.isEmpty(), "no CRITICAL sign-off for a write that did not happen")
    }

    @Test
    fun `the listing is newest first, bounded by the cursor, and yields one only when full`() {
        val oldest = runs.seed(runIdOf(1), ReconciliationStatus.MATCHED)
        val middle = runs.seed(runIdOf(2), ReconciliationStatus.BREAK)
        val newest = runs.seed(runIdOf(3), ReconciliationStatus.MATCHED)
        runs.seed(runIdOf(4), ReconciliationStatus.MATCHED, organisationId = OTHER_ORGANISATION_ID)

        val first = service.listRuns(listQuery(pageSize = 2))

        assertEquals(listOf(newest.id, middle.id), first.items.map { it.id })
        assertEquals(middle.id, first.nextCursor, "a full page always offers the next cursor")
        assertEquals(listOf(2), runs.requestedPageSizes)
        assertEquals(listOf<UUID?>(null), runs.requestedCursors)

        val second = service.listRuns(listQuery(pageSize = 2, cursor = first.nextCursor))

        assertEquals(listOf(oldest.id), second.items.map { it.id }, "the cursor row is excluded")
        assertNull(second.nextCursor, "a short page is the last one")
        assertEquals(listOf<UUID?>(null, middle.id), runs.requestedCursors)
        assertEquals(
            List(2) { AccountingPermissions.RECONCILIATION_VIEW },
            permissions.checkedCodes,
            "both pages are authorized; the second is not waved through on the first",
        )
    }

    @Test
    fun `an absent page size uses the configured default`() {
        service.listRuns(listQuery(pageSize = null))

        assertEquals(listOf(DEFAULT_PAGE_SIZE), runs.requestedPageSizes)
    }

    @Test
    fun `a page size outside the configured bounds is refused before the store is read`() {
        listOf(0, -1, MAX_PAGE_SIZE + 1).forEach { pageSize ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    service.listRuns(listQuery(pageSize = pageSize))
                }
            assertEquals("accounting.page_size_out_of_range", failure.code)
        }
        assertTrue(
            runs.requestedPageSizes.isEmpty(),
            "INV-15: an unbounded query never reaches the store",
        )

        // Both bounds themselves are legitimate.
        service.listRuns(listQuery(pageSize = 1))
        service.listRuns(listQuery(pageSize = MAX_PAGE_SIZE))
        assertEquals(listOf(1, MAX_PAGE_SIZE), runs.requestedPageSizes)
    }

    @Test
    fun `the view permission is checked before the listing`() {
        runs.seed(runIdOf(1), ReconciliationStatus.BREAK)
        permissions.refuse(AccountingPermissions.RECONCILIATION_VIEW)

        assertFailsWith<ForbiddenOperationException> { service.listRuns(listQuery()) }

        assertEquals(listOf(PermissionCheckKind.TENANT), permissions.checks.map { it.kind })
        assertTrue(runs.requestedPageSizes.isEmpty())
    }

    private fun serviceWith(
        providers: List<SubledgerProofProvider> = listOf(provider),
        store: ReconciliationRunStore = runs,
    ) = ControlAccountReconciliationService(
        accounts,
        ledger,
        SubledgerProofProviderRegistry(providers),
        snapshots,
        store,
        tenants,
        businessDates,
        permissions,
        AuditService(audits, clock),
        PaginationProperties(defaultPageSize = DEFAULT_PAGE_SIZE, maxPageSize = MAX_PAGE_SIZE),
        clock,
    )

    /** The verdict for one general-ledger balance and tolerance, everything else unchanged. */
    private fun verdict(
        gl: String,
        tolerance: String,
    ): ReconciliationStatus {
        ledger.balance = BigDecimal(gl)
        return service.run(runCommand(tolerance = BigDecimal(tolerance))).status
    }

    private fun runCommand(
        asOfDate: LocalDate = TODAY,
        branchId: UUID? = null,
        tolerance: BigDecimal = BigDecimal.ZERO,
        accountId: UUID = ACCOUNT_ID,
    ) = RunReconciliationCommand(
        organisationId = ORGANISATION_ID,
        actorId = ACTOR_ID,
        accountId = accountId,
        asOfDate = asOfDate,
        branchId = branchId,
        tolerance = tolerance,
    )

    private fun resolveCommand(
        runId: UUID = BREAK_RUN_ID,
        actorId: UUID = CHECKER_ID,
        reason: String = REASON,
    ) = ResolveReconciliationCommand(
        organisationId = ORGANISATION_ID,
        actorId = actorId,
        runId = runId,
        reason = reason,
    )

    private fun listQuery(
        pageSize: Int? = DEFAULT_PAGE_SIZE,
        cursor: UUID? = null,
    ) = ListReconciliationRunsQuery(
        organisationId = ORGANISATION_ID,
        actorId = ACTOR_ID,
        accountId = ACCOUNT_ID,
        pageSize = pageSize,
        cursor = cursor,
    )

    private fun controlAccount(
        isControl: Boolean = true,
        kind: ControlSubledgerKind? = ControlSubledgerKind.SAVINGS_DEPOSITS,
    ) = GlAccount(
        id = ACCOUNT_ID,
        organisationId = ORGANISATION_ID,
        code = AccountCode("2100"),
        name = "Member deposits",
        accountClass = AccountClass.LIABILITY,
        usage = AccountUsage.POSTABLE,
        status = GlAccountStatus.ACTIVE,
        isControlAccount = isControl,
        controlSubledgerKind = kind,
    )

    /** Seeds one stored run and returns it as the store holds it. */
    private fun FakeReconciliationRunStore.seed(
        id: UUID,
        status: ReconciliationStatus,
        runBy: UUID = RUNNER_ID,
        organisationId: UUID = ORGANISATION_ID,
    ): ReconciliationRun {
        val run = storedRun(id, status, runBy, organisationId)
        put(run)
        return run
    }

    private fun storedRun(
        id: UUID,
        status: ReconciliationStatus,
        runBy: UUID = RUNNER_ID,
        organisationId: UUID = ORGANISATION_ID,
    ) = ReconciliationRun(
        id = id,
        organisationId = organisationId,
        accountId = ACCOUNT_ID,
        branchId = BRANCH_ID,
        kind = ControlSubledgerKind.SAVINGS_DEPOSITS,
        asOfDate = TODAY,
        currencyCode = CURRENCY,
        glBalance = BigDecimal("-1000.000000"),
        subledgerBalance = BigDecimal("-999.500000"),
        difference = BigDecimal("-0.500000"),
        tolerance = BigDecimal.ZERO,
        status = status,
        provider = PROVIDER_NAME,
        detail = mapOf("snapshotId" to SNAPSHOT_ID),
        resolutionReason = null,
        resolvedBy = null,
        resolvedAt = null,
        runBy = runBy,
        runAt = NOW,
    )

    /** Neither side read, and nothing recorded: what every pre-read refusal must leave behind. */
    private fun assertNothingProven() {
        assertEquals(0, snapshots.calls, "no snapshot is exported for a proof that never runs")
        assertTrue(ledger.calls.isEmpty(), "the general-ledger side was read anyway")
        assertTrue(provider.queries.isEmpty(), "a product module was asked about a refused scope")
        assertTrue(runs.created.isEmpty(), "a refusal recorded evidence")
    }

    /** Numeric comparison, so a `NUMERIC(23, 6)` read-back is not failed for its scale. */
    private fun assertSameAmount(
        expected: String,
        actual: BigDecimal,
    ) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got $actual")
    }

    private companion object {
        val ORGANISATION_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000a1")
        val OTHER_ORGANISATION_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000a2")
        val ACTOR_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000b1")
        val RUNNER_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000b2")
        val CHECKER_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000b3")
        val ACCOUNT_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000c1")
        val UNKNOWN_ACCOUNT_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000c2")
        val BRANCH_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000d1")
        val OTHER_BRANCH_ID: UUID = UUID.fromString("01990000-0000-7000-8000-0000000000d2")
        val BREAK_RUN_ID: UUID = runIdOf(9)
        val TODAY: LocalDate = LocalDate.of(2026, 8, 31)
        val NOW: Instant = Instant.parse("2026-08-31T09:15:00Z")
        const val CURRENCY = "KES"
        const val PROVIDER_NAME = "savings"
        const val SNAPSHOT_ID = "00000003-0000001B-1"
        const val REASON = "Break cleared by reversal JV-000123, evidenced in ticket OPS-4417."
        const val POSITION_COUNT = 42L
        const val DEFAULT_PAGE_SIZE = 25
        const val MAX_PAGE_SIZE = 100

        /**
         * Ids whose order is fixed rather than generated.
         *
         * `uuidV7()` is only monotonic across milliseconds, so ids minted in a loop would give the
         * newest-first assertions a result that depends on how fast the test ran.
         */
        fun runIdOf(ordinal: Int): UUID =
            UUID.fromString("01990000-0000-7000-8000-00000000000$ordinal")
    }
}

/** One call into [LedgerBalanceQuery], recorded with the scope it was made for. */
private data class LedgerRead(
    val organisationId: UUID,
    val accountId: UUID,
    val branchId: UUID?,
    val asOfDate: LocalDate,
)

/** The general-ledger side of a proof, answering one balance and recording what it was asked. */
private class RecordingLedgerBalanceQuery : LedgerBalanceQuery {
    var balance: BigDecimal = BigDecimal.ZERO
    val calls = mutableListOf<LedgerRead>()

    override fun signedBalanceAsOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        asOfDate: LocalDate,
    ): BigDecimal {
        calls += LedgerRead(organisationId, accountId, branchId, asOfDate)
        return balance
    }
}

/** A [ProofSnapshot] that counts its calls, so "before either side is read" is assertable. */
private class RecordingProofSnapshot : ProofSnapshot {
    var snapshotId: String = ""
    var calls = 0
    var failure: RuntimeException? = null

    override fun currentSnapshotId(): String {
        calls++
        val refusal = failure
        if (refusal != null) {
            throw refusal
        }
        return snapshotId
    }
}

/** A [SubledgerProofProvider] that owns one class and records every query it is handed. */
private class RecordingProofProvider(
    override val providerName: String,
    private val owned: ControlSubledgerKind,
) : SubledgerProofProvider {
    var answer: SubledgerAggregate? = null
    val queries = mutableListOf<SubledgerProofQuery>()

    override fun supports(kind: ControlSubledgerKind): Boolean = kind == owned

    override fun aggregate(query: SubledgerProofQuery): SubledgerAggregate? {
        queries += query
        return answer
    }
}

/**
 * The chart of accounts as reconciliation uses it: one tenant-filtered lookup by id.
 *
 * Every other method is unreachable from this service, and says so rather than answering, so a
 * future revision that starts using one is caught here instead of being silently satisfied.
 */
private class FakeControlAccountStore : GlAccountStore {
    private val accounts = mutableMapOf<UUID, GlAccount>()

    /** Every account id looked up, in call order. */
    val lookups = mutableListOf<UUID>()

    fun put(account: GlAccount) {
        accounts[account.id] = account
    }

    override fun findById(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount? {
        lookups += accountId
        return accounts[accountId]?.takeIf { it.organisationId == organisationId }
    }

    override fun findByCode(
        organisationId: UUID,
        code: AccountCode,
    ): GlAccount? = unused()

    override fun findControlAccountFor(
        organisationId: UUID,
        kind: ControlSubledgerKind,
    ): GlAccount? = unused()

    override fun ancestorsOf(
        organisationId: UUID,
        accountId: UUID,
    ): List<GlAccount> = unused()

    override fun subtreeHeightOf(
        organisationId: UUID,
        accountId: UUID,
    ): Int = unused()

    override fun lockForStateChange(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount? = unused()

    override fun lockForPosting(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount? = unused()

    override fun hasActivePostingRuleLegs(
        organisationId: UUID,
        accountId: UUID,
    ): Boolean = unused()

    override fun hasChildren(
        organisationId: UUID,
        accountId: UUID,
    ): Boolean = unused()

    override fun hasJournalLines(
        organisationId: UUID,
        accountId: UUID,
    ): Boolean = unused()

    override fun list(
        organisationId: UUID,
        afterCode: AccountCode?,
        pageSize: Int,
    ): GlAccountPage = unused()

    private fun unused(): Nothing = error("reconciliation does not read the chart this way")
}

/** The lifecycle facts a proof asks for: branch existence, and the functional currency. */
private class FakeReconciliationTenantLookup : AccountingTenantLookup {
    var functionalCurrency: String? = "KES"

    /** Branches that exist within the organisation, whatever their state. */
    val branches = mutableSetOf<UUID>()

    /** Every branch existence check, in call order. */
    val branchChecks = mutableListOf<UUID>()

    override fun isOrganisationPostable(organisationId: UUID): Boolean = true

    override fun isBranchPostable(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = true

    override fun branchBelongsTo(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean {
        branchChecks += branchId
        return branchId in branches
    }

    override fun functionalCurrencyOf(organisationId: UUID): String? = functionalCurrency

    /** Reconciliation never posts, so the locking read is a wiring error if it is ever reached. */
    override fun functionalCurrencyForPosting(organisationId: UUID): String? =
        error("reconciliation must not take the posting lock on the organisation row")
}

/** The tenant business date a proof is bounded by. */
private class FakeReconciliationBusinessDateLookup : AccountingBusinessDateLookup {
    var current: AccountingBusinessDate? = null

    override fun currentBusinessDate(organisationId: UUID): AccountingBusinessDate? = current

    /** A read-side caller: it must never reach the posting path's locking business-date read. */
    override fun currentBusinessDateForPosting(organisationId: UUID): AccountingBusinessDate? =
        error("reconciliation must not take the posting lock on the business-date row")
}

/**
 * A store whose row is a `BREAK` under the lock and refuses to resolve anyway.
 *
 * The shared fake cannot stage this - its `lock` and `resolve` read one map, so a row locked as a
 * `BREAK` always resolves - yet the real store can: the row moves between the two statements only
 * if the lock did not hold. The service's `check` is what turns that into a loud failure instead
 * of a success reported for a write that never happened.
 */
private class UnresolvableRunStore(
    private val run: ReconciliationRun,
) : ReconciliationRunStore {
    override fun create(run: NewReconciliationRun): ReconciliationRun =
        error("the stubborn store never inserts")

    override fun find(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun? = run.takeIf { it.id == runId && it.organisationId == organisationId }

    override fun lock(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun? = find(organisationId, runId)

    override fun resolve(
        organisationId: UUID,
        runId: UUID,
        reason: String,
        actorId: UUID,
        resolvedAt: Instant,
    ): Boolean = false

    override fun listForAccount(
        organisationId: UUID,
        accountId: UUID,
        beforeId: UUID?,
        pageSize: Int,
    ): List<ReconciliationRun> = emptyList()
}

/** Collects the audit events the resolution path records. */
private class RecordingReconciliationAuditRepository : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        saved += event
    }
}
