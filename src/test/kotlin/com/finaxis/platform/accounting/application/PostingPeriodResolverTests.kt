package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.accounting.domain.PostingDateClassification
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Resolver behaviour, driven with fakes so the decision logic is testable without a database.
 *
 * The case that matters most is
 * [a period closed under the lock is rejected even when the unlocked lookup saw it open] - the
 * unit-level twin of the S2 concurrency scenario. It is the assertion that proves the decision uses
 * the status read *under the lock* rather than the earlier lookup.
 */
class PostingPeriodResolverTests {
    private val periods = FakeFiscalPeriodStateStore()
    private val businessDates = FakeBusinessDateLookup()
    private val permissions = RecordingPermissionGuard()
    private val audits = RecordingAuditRepositoryForPostingPeriod()
    private val clock = Clock.fixed(RECORDED_AT, ZoneOffset.UTC)
    private val resolver =
        PostingPeriodResolver(
            periods,
            businessDates,
            permissions,
            AuditService(audits, clock),
            clock,
        )

    @AfterEach
    fun clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false)
    }

    @Test
    fun `a current-dated posting into an open period resolves without a permission check`() {
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.OPEN)

        val resolved = resolver.resolveForPosting(command())

        assertEquals(PostingDateClassification.CURRENT, resolved.classification)
        assertEquals(FiscalPeriodStatus.OPEN, resolved.period.status)
        assertTrue(permissions.required.isEmpty(), "a same-day posting needs no extra permission")
    }

    @Test
    fun `a backdated posting requires the prior-period permission`() {
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.OPEN)

        resolver.resolveForPosting(command(PostingDateRequest(postingDate = TODAY.minusDays(3))))

        assertEquals(listOf(AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD), permissions.required)
        assertEquals(
            listOf(AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD),
            permissions.breakGlass,
            "the break-glass path must be used, not the ordinary tenant check - the ordinary one " +
                "short-circuits to allow for system actors",
        )
    }

    @Test
    fun `exercising prior-period authority is recorded`() {
        // Enforcement without a record is the finding an auditor leads with: this asserts the
        // control and its evidence ship together, and that the row carries the two dates that
        // make it meaningful - what was posted into, and what the tenant's day actually was.
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.OPEN)
        val postingDate = TODAY.minusDays(3)

        resolver.resolveForPosting(command(PostingDateRequest(postingDate = postingDate)))

        val recorded = audits.saved.single()
        assertEquals(AccountingAuditActions.JOURNAL_POST_PRIOR_PERIOD, recorded.action)
        assertEquals(ACTOR_ID.toString(), recorded.actorId)
        assertEquals(ORGANISATION_ID.toString(), recorded.tenantId)
        assertEquals(postingDate.toString(), recorded.metadata["postingDate"])
        assertEquals(TODAY.toString(), recorded.metadata["businessDate"])
    }

    @Test
    fun `the recorded authority names the posting it was exercised for`() {
        // The two halves of the INV-7 idempotency identity, which is what ADR 0025 tells an
        // auditor to group these rows by. Without them the row identifies only a period and a
        // date, which two different backdated postings by one operator share.
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.OPEN)

        resolver.resolveForPosting(command(PostingDateRequest(postingDate = TODAY.minusDays(3))))

        val recorded = audits.saved.single()
        assertEquals(SOURCE.sourceModule, recorded.metadata["sourceModule"])
        assertEquals(
            SOURCE.idempotencyKey,
            recorded.metadata["sourceReference"],
            "the idempotency key is what posting_request.source_reference holds, so the audit " +
                "row and the claim it was exercised against name the same posting",
        )
    }

    @Test
    fun `a backdated posting into a closed period records no authority-exercised event`() {
        // The audit says prior-period authority was exercised on an ADMISSIBLE posting, so it must
        // not fire when the posting is rejected. An earlier revision recorded it at the permission
        // check, before the period was resolved - which left a permanent SUCCESS row claiming a
        // prior-period posting that never happened, and recordIndependently means it survives the
        // rollback that follows.
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.CLOSED)

        assertFailsWith<ConflictException> {
            resolver.resolveForPosting(
                command(PostingDateRequest(postingDate = TODAY.minusDays(3))),
            )
        }

        assertEquals(
            emptyList(),
            audits.saved,
            "a rejected backdated posting must leave no authority-exercised record",
        )
        assertEquals(
            listOf(AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD),
            permissions.breakGlass,
            "the permission was still checked - only the record is withheld",
        )
    }

    @Test
    fun `an ordinary current-dated posting records nothing`() {
        // The negative control. Without it the assertion above would also pass if the resolver
        // audited every posting, which would bury break-glass use in routine noise.
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.OPEN)

        resolver.resolveForPosting(command())

        assertEquals(emptyList(), audits.saved)
    }

    @Test
    fun `a period closed under the lock is rejected even when the unlocked lookup saw it open`() {
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.CLOSED)

        val failure = assertFailsWith<ConflictException> { resolver.resolveForPosting(command()) }

        assertEquals("accounting.fiscal_period_closed", failure.code)
    }

    @Test
    fun `a closed period is rejected even for an actor holding the prior-period permission`() {
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.CLOSED)
        periods.locked = snapshot(FiscalPeriodStatus.CLOSED)

        val failure =
            assertFailsWith<ConflictException> {
                resolver.resolveForPosting(
                    command(PostingDateRequest(postingDate = TODAY.minusDays(3))),
                )
            }

        assertEquals(
            "accounting.fiscal_period_closed",
            failure.code,
            "reopening is an explicit audited operation, never an implicit consequence of " +
                "holding a permission",
        )
    }

    @Test
    fun `a posting with no covering period is rejected`() {
        inTransaction()
        periods.covering = null

        val failure = assertFailsWith<ConflictException> { resolver.resolveForPosting(command()) }

        assertEquals("accounting.fiscal_period_not_found", failure.code)
    }

    @Test
    fun `a period whose bounds moved under the lock no longer covers the posting date`() {
        // The unit twin of the coverage race. The lookup found a period covering the posting date,
        // but the snapshot read under the lock has different bounds - someone corrected them and
        // committed in between. Coverage is exactly as stale as status was, so it is re-checked
        // against the locked snapshot; deciding from the lookup would commit a journal into a
        // period that does not contain its posting date.
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked =
            snapshot(FiscalPeriodStatus.OPEN).copy(
                startDate = TODAY.plusMonths(1).withDayOfMonth(1),
                endDate = TODAY.plusMonths(1).withDayOfMonth(TODAY.plusMonths(1).lengthOfMonth()),
            )

        val failure = assertFailsWith<ConflictException> { resolver.resolveForPosting(command()) }

        assertEquals("accounting.fiscal_period_not_found", failure.code)
    }

    @Test
    fun `a posting into a locked period is rejected`() {
        // LOCKED is terminal, and distinct from CLOSED: a closed period may be reopened, a locked
        // one may not. Posting must refuse both, so this pins that requireOpen rejects on "not
        // OPEN" rather than on "== CLOSED", which would silently admit postings into locked books.
        inTransaction()
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.LOCKED)

        val failure = assertFailsWith<ConflictException> { resolver.resolveForPosting(command()) }

        assertEquals("accounting.fiscal_period_closed", failure.code)
    }

    @Test
    fun `a missing business date is rejected`() {
        inTransaction()
        businessDates.current = null

        val failure = assertFailsWith<ConflictException> { resolver.resolveForPosting(command()) }

        assertEquals("accounting.business_date_unavailable", failure.code)
    }

    @Test
    fun `resolving outside a transaction fails rather than taking a lock that is released`() {
        periods.covering = snapshot(FiscalPeriodStatus.OPEN)
        periods.locked = snapshot(FiscalPeriodStatus.OPEN)

        assertFailsWith<IllegalStateException> { resolver.resolveForPosting(command()) }
    }

    private fun inTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
    }

    private fun command(dates: PostingDateRequest = PostingDateRequest()) =
        ResolvePostingPeriodCommand(ORGANISATION_ID, ACTOR_ID, SOURCE, dates)

    private fun snapshot(status: FiscalPeriodStatus) =
        FiscalPeriodSnapshot(
            key = FiscalPeriodKey(ORGANISATION_ID, PERIOD_ID),
            startDate = TODAY.withDayOfMonth(1),
            endDate = TODAY.withDayOfMonth(TODAY.lengthOfMonth()),
            status = status,
        )

    private class FakeFiscalPeriodStateStore : FiscalPeriodStateStore {
        var covering: FiscalPeriodSnapshot? = null
        var locked: FiscalPeriodSnapshot? = null

        override fun findById(key: FiscalPeriodKey) = covering

        override fun findCovering(
            organisationId: UUID,
            postingDate: LocalDate,
        ) = covering

        override fun lockCoveringForPosting(
            organisationId: UUID,
            postingDate: LocalDate,
        ) = locked

        override fun lockForStateChange(key: FiscalPeriodKey) = locked

        override fun updateStatus(
            key: FiscalPeriodKey,
            newStatus: FiscalPeriodStatus,
            actorId: UUID,
            reason: String?,
        ) = true
    }

    private class FakeBusinessDateLookup : AccountingBusinessDateLookup {
        var current: AccountingBusinessDate? =
            AccountingBusinessDate(ORGANISATION_ID, TODAY, true)

        override fun currentBusinessDate(organisationId: UUID) = current
    }

    private class RecordingPermissionGuard : AccountingPermissionGuard {
        val required = mutableListOf<String>()
        val breakGlass = mutableListOf<String>()

        override fun requireTenantPermission(
            actorId: UUID,
            organisationId: UUID,
            permissionCode: String,
        ) {
            required += permissionCode
        }

        override fun requireBreakGlassPermission(
            actorId: UUID,
            organisationId: UUID,
            permissionCode: String,
        ) {
            required += permissionCode
            breakGlass += permissionCode
        }

        override fun requireBranchPermission(
            actorId: UUID,
            organisationId: UUID,
            branchId: UUID,
            permissionCode: String,
        ) {
            required += permissionCode
        }
    }

    private companion object {
        val ORGANISATION_ID: UUID = uuidV7()
        val ACTOR_ID: UUID = uuidV7()
        val PERIOD_ID: UUID = uuidV7()
        val SOURCE =
            AccountingSourceReference("savings", "SAVINGS_DEPOSIT", uuidV7(), "dep-resolver")
        val TODAY: LocalDate = LocalDate.of(2026, 8, 31)
        val RECORDED_AT: Instant = Instant.parse("2026-08-31T09:15:00Z")
    }
}

private class RecordingAuditRepositoryForPostingPeriod : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        saved.add(event)
    }
}
