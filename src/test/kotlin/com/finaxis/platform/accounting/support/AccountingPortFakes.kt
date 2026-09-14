// Hand-written fakes for the accounting application ports, shared by the unit suites of issue #93.
//
// Fakes rather than mocks, and rather than a Spring context. A mock proves that a method was
// called; these prove what the *behaviour around* the call is, which is what the three services
// under test actually depend on - that a cursor excludes its own row, that a compare-and-set
// refuses a status that moved, that a row version one behind is rejected. A stubbed
// `listPostingRequestsForEntity` that returns whatever the test handed it cannot fail when the
// service forgets to pass the cursor, so a suite written over one is green either way.
//
// They are also the reason `PostingLineageService`, `ControlAccountReconciliationService` and
// `ManualJournalService` can be exercised with no container start: every port below is in-memory,
// so the decisions those services make - permission ordering, page-size clamping, the
// different-actor rules - are affordable to state at their edges. What the database itself
// enforces, and the wiring that reaches it, stays the business of the `@SpringBootTest` suites.
//
// Every fake is organisation-scoped exactly as its port is, so a suite cannot accidentally prove
// a tenant leak is impossible by using a store that never looked at the tenant.
package com.finaxis.platform.accounting.support

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.ledger.JournalEntryView
import com.finaxis.platform.accounting.application.ledger.JournalLineView
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.PostingRequestView
import com.finaxis.platform.accounting.application.manual.ManualJournalDraftContent
import com.finaxis.platform.accounting.application.manual.ManualJournalMakerResolver
import com.finaxis.platform.accounting.application.manual.ManualJournalStore
import com.finaxis.platform.accounting.application.manual.NewManualJournal
import com.finaxis.platform.accounting.application.reconciliation.NewReconciliationRun
import com.finaxis.platform.accounting.application.reconciliation.ReconciliationRun
import com.finaxis.platform.accounting.application.reconciliation.ReconciliationRunStore
import com.finaxis.platform.accounting.application.reconciliation.ReconciliationStatus
import com.finaxis.platform.accounting.domain.ManualJournal
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.ManualJournalTransition
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.id.uuidV7
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Which of [AccountingPermissionGuard]'s three checks was made. */
internal enum class PermissionCheckKind {
    /** [AccountingPermissionGuard.requireTenantPermission]. */
    TENANT,

    /** [AccountingPermissionGuard.requireBreakGlassPermission]: no system-actor exemption. */
    BREAK_GLASS,

    /** [AccountingPermissionGuard.requireBranchPermission]. */
    BRANCH,
}

/** One authorization check as it was made, including the arguments it was made with. */
internal data class PermissionCheck(
    val kind: PermissionCheckKind,
    val actorId: UUID,
    val organisationId: UUID,
    val permissionCode: String,
    val branchId: UUID? = null,
)

/**
 * Recording [AccountingPermissionGuard] that answers yes until a code is armed to refuse.
 *
 * The recording is ordered and kept even for a refused check, which is the point: a service that
 * reads a row, discovers it is missing and reports *not found* before asking whether the caller
 * was allowed to look has leaked the row's existence, and the only evidence of that is where the
 * check sits in [checks] relative to the store's own calls.
 *
 * A refusal throws [ForbiddenOperationException] with its defaults, because that is exactly what
 * the production adapter produces: `AccessDeniedException` extends it and discards the message it
 * is given. Accounting must not depend on the identity module, so the fake names the common
 * supertype the port documents rather than the identity subclass, and `assertFailsWith
 * <ForbiddenOperationException>` passes against either.
 */
internal class FakeAccountingPermissionGuard : AccountingPermissionGuard {
    /** Every check made, in call order, refused ones included. */
    val checks = mutableListOf<PermissionCheck>()

    private val refused = mutableSetOf<String>()

    /** Just the permission codes, in call order: the assertion most suites want. */
    val checkedCodes: List<String>
        get() = checks.map { it.permissionCode }

    /** Arms [permissionCode] to be refused however it is checked. */
    fun refuse(permissionCode: String) {
        refused += permissionCode
    }

    /** Disarms a refusal armed by [refuse]. */
    fun allow(permissionCode: String) {
        refused -= permissionCode
    }

    /** Forgets every recorded check, leaving the armed refusals in place. */
    fun clearChecks() {
        checks.clear()
    }

    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        record(PermissionCheckKind.TENANT, actorId, organisationId, permissionCode)
    }

    override fun requireBreakGlassPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        record(PermissionCheckKind.BREAK_GLASS, actorId, organisationId, permissionCode)
    }

    override fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        record(PermissionCheckKind.BRANCH, actorId, organisationId, permissionCode, branchId)
    }

    private fun record(
        kind: PermissionCheckKind,
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
        branchId: UUID? = null,
    ) {
        checks += PermissionCheck(kind, actorId, organisationId, permissionCode, branchId)
        if (permissionCode in refused) {
            throw ForbiddenOperationException()
        }
    }
}

/**
 * In-memory [JournalReadStore] over seeded journals, requests and lines.
 *
 * [listPostingRequestsForEntity] implements the real keyset: newest first by id, from the cursor
 * *exclusive*, at most the page size asked for. A fake that ignored the cursor would let a service
 * that forgot to pass one pass its own pagination test, which is the defect the lineage suite
 * exists to catch.
 *
 * [findJournalLines] is keyed by journal and tenant-filtered through the journal's own header, so
 * lines seeded for a journal that was never seeded are returned as they were given. Every other
 * read is filtered on `organisationId` exactly as the adapter's `WHERE` clause is.
 */
internal class FakeJournalReadStore : JournalReadStore {
    /** Seeded journals; [addJournal] appends, order is irrelevant to every read. */
    val journals = mutableListOf<JournalEntryView>()

    /** Seeded posting requests; the listing orders them itself. */
    val requests = mutableListOf<PostingRequestView>()

    /** Seeded lines by journal id; returned in line order. */
    val lines = mutableMapOf<UUID, List<JournalLineView>>()

    /** Every page size [listPostingRequestsForEntity] was called with, in order. */
    val requestedPageSizes = mutableListOf<Int>()

    /** Every cursor [listPostingRequestsForEntity] was called with, in order, nulls included. */
    val requestedCursors = mutableListOf<UUID?>()

    /** Seeds one journal. */
    fun addJournal(journal: JournalEntryView) {
        journals += journal
    }

    /** Seeds one posting request. */
    fun addRequest(request: PostingRequestView) {
        requests += request
    }

    /** Seeds the lines of one journal. */
    fun putLines(
        journalEntryId: UUID,
        journalLines: List<JournalLineView>,
    ) {
        lines[journalEntryId] = journalLines
    }

    override fun findJournalEntry(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView? =
        journals.firstOrNull {
            it.organisationId == organisationId && it.id == journalEntryId
        }

    override fun findJournalEntryForRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): JournalEntryView? =
        journals.firstOrNull {
            it.organisationId == organisationId && it.postingRequestId == postingRequestId
        }

    override fun findReversalOf(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView? =
        journals.firstOrNull {
            it.organisationId == organisationId && it.reversesJournalEntryId == journalEntryId
        }

    override fun findPostingRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): PostingRequestView? =
        requests.firstOrNull {
            it.organisationId == organisationId && it.id == postingRequestId
        }

    override fun findPostingRequestBySource(
        organisationId: UUID,
        sourceModule: String,
        sourceReference: String,
    ): PostingRequestView? =
        requests.firstOrNull {
            it.organisationId == organisationId &&
                it.sourceModule == sourceModule &&
                it.sourceReference == sourceReference
        }

    @Suppress("LongParameterList")
    override fun listPostingRequestsForEntity(
        organisationId: UUID,
        sourceModule: String,
        sourceEntityType: String,
        sourceEntityId: UUID,
        beforeId: UUID?,
        pageSize: Int,
    ): List<PostingRequestView> {
        requestedPageSizes += pageSize
        requestedCursors += beforeId
        return requests
            .filter {
                it.organisationId == organisationId &&
                    it.sourceModule == sourceModule &&
                    it.sourceEntityType == sourceEntityType &&
                    it.sourceEntityId == sourceEntityId
            }.filter { beforeId == null || compareIds(it.id, beforeId) < 0 }
            .newestFirst { it.id }
            .take(pageSize)
    }

    override fun findJournalLines(
        organisationId: UUID,
        journalEntryId: UUID,
    ): List<JournalLineView> {
        val header = journals.firstOrNull { it.id == journalEntryId }
        if (header != null && header.organisationId != organisationId) {
            return emptyList()
        }
        return lines[journalEntryId].orEmpty().sortedBy { it.lineNumber }
    }
}

/** Whether a reconciliation row was read plainly or under the row lock. */
internal enum class ReconciliationAccessKind {
    /** [ReconciliationRunStore.find]. */
    FIND,

    /** [ReconciliationRunStore.lock]: the `FOR UPDATE` read. */
    LOCK,
}

/** One read of a reconciliation row, recorded so the lock's position is assertable. */
internal data class ReconciliationAccess(
    val kind: ReconciliationAccessKind,
    val runId: UUID,
)

/**
 * In-memory [ReconciliationRunStore].
 *
 * [create] mirrors the database in the two places the service can observe it: the id is generated,
 * and `difference` is the *generated column* `gl_balance - subledger_balance` rather than anything
 * the caller supplies - there is no way to store a difference that contradicts the two balances,
 * exactly as `V9` has it. The three amounts and the tolerance are normalised to
 * [MoneyPolicy.STORAGE_SCALE], because `NUMERIC(23, 6)` is what a row read back would carry; a
 * suite that seeds `100` therefore sees `100.000000`, as it would from Postgres.
 *
 * [lock] returns what [find] returns - the fake has no second snapshot - but records itself
 * separately, so a suite can prove the lock was taken before the status moved rather than after.
 */
internal class FakeReconciliationRunStore(
    private val clock: Clock = Clock.systemUTC(),
) : ReconciliationRunStore {
    /** Every stored run by id, in insertion order. Seed with [put] or build one with [create]. */
    val runs = linkedMapOf<UUID, ReconciliationRun>()

    /** Every run [create] was asked to insert, in order. */
    val created = mutableListOf<NewReconciliationRun>()

    /** Every [find] and [lock], in call order. */
    val accesses = mutableListOf<ReconciliationAccess>()

    /** Every page size [listForAccount] was called with, in order. */
    val requestedPageSizes = mutableListOf<Int>()

    /** Every cursor [listForAccount] was called with, in order, nulls included. */
    val requestedCursors = mutableListOf<UUID?>()

    /** Seeds one run, replacing any run already stored under its id. */
    fun put(run: ReconciliationRun) {
        runs[run.id] = run
    }

    override fun create(run: NewReconciliationRun): ReconciliationRun {
        created += run
        val stored =
            ReconciliationRun(
                id = uuidV7(),
                organisationId = run.organisationId,
                accountId = run.accountId,
                branchId = run.branchId,
                kind = run.kind,
                asOfDate = run.asOfDate,
                currencyCode = run.currencyCode,
                glBalance = atStorageScale(run.glBalance),
                subledgerBalance = atStorageScale(run.subledgerBalance),
                difference = atStorageScale(run.glBalance - run.subledgerBalance),
                tolerance = atStorageScale(run.tolerance),
                status = run.status,
                provider = run.provider,
                detail = run.detail,
                resolutionReason = null,
                resolvedBy = null,
                resolvedAt = null,
                runBy = run.actorId,
                runAt = clock.instant(),
            )
        runs[stored.id] = stored
        return stored
    }

    override fun find(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun? {
        accesses += ReconciliationAccess(ReconciliationAccessKind.FIND, runId)
        return tenantRun(organisationId, runId)
    }

    override fun lock(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun? {
        accesses += ReconciliationAccess(ReconciliationAccessKind.LOCK, runId)
        return tenantRun(organisationId, runId)
    }

    override fun resolve(
        organisationId: UUID,
        runId: UUID,
        reason: String,
        actorId: UUID,
        resolvedAt: Instant,
    ): Boolean {
        val current = tenantRun(organisationId, runId) ?: return false
        if (current.status != ReconciliationStatus.BREAK) {
            return false
        }
        runs[runId] =
            current.copy(
                status = ReconciliationStatus.RESOLVED,
                resolutionReason = reason,
                resolvedBy = actorId,
                resolvedAt = resolvedAt,
            )
        return true
    }

    override fun listForAccount(
        organisationId: UUID,
        accountId: UUID,
        beforeId: UUID?,
        pageSize: Int,
    ): List<ReconciliationRun> {
        requestedPageSizes += pageSize
        requestedCursors += beforeId
        val ofAccount =
            runs.values.filter {
                it.organisationId == organisationId && it.accountId == accountId
            }
        return ofAccount
            .filter { beforeId == null || compareIds(it.id, beforeId) < 0 }
            .newestFirst { it.id }
            .take(pageSize)
    }

    private fun tenantRun(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun? = runs[runId]?.takeIf { it.organisationId == organisationId }
}

/** Which [ManualJournalStore] method was called. */
internal enum class ManualJournalAccessKind {
    /** [ManualJournalStore.find]. */
    FIND,

    /** [ManualJournalStore.lockForStateChange]: the `FOR UPDATE` read. */
    LOCK,

    /** [ManualJournalStore.create]. */
    CREATE,

    /** [ManualJournalStore.updateDraft], whether or not the row version matched. */
    UPDATE_DRAFT,

    /** [ManualJournalStore.updateStatus], whether or not the compare-and-set held. */
    UPDATE_STATUS,

    /** [ManualJournalStore.findLines]. */
    FIND_LINES,

    /** [ManualJournalStore.replaceLines]. */
    REPLACE_LINES,
}

/** One call into the manual-journal store, recorded so the lock's position is assertable. */
internal data class ManualJournalAccess(
    val kind: ManualJournalAccessKind,
    val journalId: UUID,
)

/** One status move the store was asked to apply, recorded whether or not it held. */
internal data class ManualJournalStatusMove(
    val journalId: UUID,
    val from: ManualJournalStatus,
    val to: ManualJournalStatus,
    val reason: String?,
    val journalEntryId: UUID?,
    val actorId: UUID,
)

/**
 * In-memory [ManualJournalStore] that models the two contracts the service leans on.
 *
 * *The stale edit*: [updateDraft] is the adapter's `WHERE status = 'DRAFT' AND row_version = ?`,
 * so it returns false for a version that moved and for a header that left `DRAFT`, and bumps
 * `row_version` when it succeeds. A fake that always returned true would let a lost update pass.
 *
 * *The compare-and-set*: [updateStatus] refuses when the current status is not `from`, which is
 * how a second approval of the same journal loses. Like the adapter it writes `status_reason`
 * unconditionally - `null` included - and records the journal entry only when one is supplied.
 *
 * Line writes are stored as handed over and returned in line order, matching an adapter that
 * inserts in order and reads back `ORDER BY line_number`. [updateDraft] deliberately ignores
 * `content.lines`: the header statement does not touch the line table, and the service replaces
 * lines through [replaceLines] under the same lock.
 */
internal class FakeManualJournalStore : ManualJournalStore {
    /** Every stored header by id, in insertion order. Seed with [put] or build with [create]. */
    val journals = linkedMapOf<UUID, ManualJournal>()

    /** Stored lines by journal id, as last written. */
    val lines = mutableMapOf<UUID, List<ManualJournalLine>>()

    /** Every call into the store, in order. */
    val accesses = mutableListOf<ManualJournalAccess>()

    /** Every status move attempted, in order, refused ones included. */
    val statusMoves = mutableListOf<ManualJournalStatusMove>()

    /** Every draft content accepted by [updateDraft], in order. */
    val draftUpdates = mutableListOf<ManualJournalDraftContent>()

    /** Seeds one header, replacing any header already stored under its id. */
    fun put(journal: ManualJournal) {
        journals[journal.id] = journal
    }

    /** Seeds the lines of one journal. */
    fun putLines(
        journalId: UUID,
        journalLines: List<ManualJournalLine>,
    ) {
        lines[journalId] = journalLines
    }

    override fun find(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal? {
        accesses += ManualJournalAccess(ManualJournalAccessKind.FIND, journalId)
        return tenantRow(organisationId, journalId)
    }

    override fun lockForStateChange(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal? {
        accesses += ManualJournalAccess(ManualJournalAccessKind.LOCK, journalId)
        return tenantRow(organisationId, journalId)
    }

    override fun create(journal: NewManualJournal): ManualJournal {
        val created =
            ManualJournal(
                id = uuidV7(),
                organisationId = journal.organisationId,
                branchId = journal.branchId,
                title = journal.title,
                externalReference = journal.externalReference,
                narrative = journal.narrative,
                status = ManualJournalStatus.DRAFT,
                statusReason = null,
                transactionDate = journal.transactionDate,
                valueDate = journal.valueDate,
                postingDate = journal.postingDate,
                journalEntryId = null,
                createdBy = journal.actorId,
                rowVersion = 0,
            )
        journals[created.id] = created
        accesses += ManualJournalAccess(ManualJournalAccessKind.CREATE, created.id)
        return created
    }

    override fun updateDraft(
        organisationId: UUID,
        journalId: UUID,
        content: ManualJournalDraftContent,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        accesses += ManualJournalAccess(ManualJournalAccessKind.UPDATE_DRAFT, journalId)
        val current = tenantRow(organisationId, journalId) ?: return false
        val draft = current.status == ManualJournalStatus.DRAFT
        if (!draft || current.rowVersion != expectedRowVersion) {
            return false
        }
        draftUpdates += content
        journals[journalId] =
            current.copy(
                branchId = content.branchId,
                title = content.title,
                externalReference = content.externalReference,
                narrative = content.narrative,
                transactionDate = content.transactionDate,
                valueDate = content.valueDate,
                postingDate = content.postingDate,
                rowVersion = current.rowVersion + 1,
            )
        return true
    }

    @Suppress("LongParameterList")
    override fun updateStatus(
        organisationId: UUID,
        journalId: UUID,
        from: ManualJournalStatus,
        to: ManualJournalStatus,
        reason: String?,
        journalEntryId: UUID?,
        actorId: UUID,
    ): Boolean {
        accesses += ManualJournalAccess(ManualJournalAccessKind.UPDATE_STATUS, journalId)
        statusMoves +=
            ManualJournalStatusMove(
                journalId = journalId,
                from = from,
                to = to,
                reason = reason,
                journalEntryId = journalEntryId,
                actorId = actorId,
            )
        val current = tenantRow(organisationId, journalId) ?: return false
        if (current.status != from) {
            return false
        }
        journals[journalId] =
            current.copy(
                status = to,
                statusReason = reason,
                journalEntryId = journalEntryId ?: current.journalEntryId,
                rowVersion = current.rowVersion + 1,
            )
        return true
    }

    override fun findLines(
        organisationId: UUID,
        journalId: UUID,
    ): List<ManualJournalLine> {
        accesses += ManualJournalAccess(ManualJournalAccessKind.FIND_LINES, journalId)
        if (belongsToAnotherTenant(organisationId, journalId)) {
            return emptyList()
        }
        return lines[journalId].orEmpty().sortedBy { it.lineNumber }
    }

    override fun replaceLines(
        organisationId: UUID,
        journalId: UUID,
        lines: List<ManualJournalLine>,
        actorId: UUID,
    ) {
        accesses += ManualJournalAccess(ManualJournalAccessKind.REPLACE_LINES, journalId)
        if (belongsToAnotherTenant(organisationId, journalId)) {
            return
        }
        this.lines[journalId] = lines.toList()
    }

    private fun tenantRow(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal? = journals[journalId]?.takeIf { it.organisationId == organisationId }

    private fun belongsToAnotherTenant(
        organisationId: UUID,
        journalId: UUID,
    ): Boolean {
        val header = journals[journalId] ?: return false
        return header.organisationId != organisationId
    }
}

/**
 * In-memory [ManualJournalMakerResolver], for the separation-of-duties rules.
 *
 * The transition log it stands for is append-only, so the fake stores only the *last* actor per
 * transition, which is all the port exposes. Lookups are recorded because the service asks for the
 * maker only on the paths where a checker is required, and a suite may want to prove it did not
 * ask on the others.
 */
internal class FakeManualJournalMakerResolver : ManualJournalMakerResolver {
    private val actors = mutableMapOf<Pair<UUID, ManualJournalTransition>, UUID>()

    /** Every lookup made, as journal id to transition, in call order. */
    val lookups = mutableListOf<Pair<UUID, ManualJournalTransition>>()

    /** Declares that [actorId] most recently performed [transition] on [journalId]. */
    fun performedBy(
        journalId: UUID,
        transition: ManualJournalTransition,
        actorId: UUID,
    ) {
        actors[journalId to transition] = actorId
    }

    /** Declares that [transition] never happened on [journalId]. */
    fun neverPerformed(
        journalId: UUID,
        transition: ManualJournalTransition,
    ) {
        actors -= journalId to transition
    }

    override fun lastActorFor(
        organisationId: UUID,
        journalId: UUID,
        transition: ManualJournalTransition,
    ): UUID? {
        lookups += journalId to transition
        return actors[journalId to transition]
    }
}

/**
 * Orders by id the way PostgreSQL orders the `uuid` type: unsigned, most significant half first.
 *
 * [UUID.compareTo] compares both halves as *signed* longs, so it disagrees with the database for
 * any pair whose top bit differs. UUIDv7 keeps that bit clear for the next few thousand years, so
 * the two agree today and would quietly diverge later; the keyset fakes do not rest on that.
 */
private fun compareIds(
    left: UUID,
    right: UUID,
): Int {
    val leftHigh = left.mostSignificantBits.toULong()
    val rightHigh = right.mostSignificantBits.toULong()
    if (leftHigh != rightHigh) {
        return leftHigh.compareTo(rightHigh)
    }
    return left.leastSignificantBits.toULong().compareTo(right.leastSignificantBits.toULong())
}

/** Newest first by id, as every keyset listing in accounting orders its page. */
private fun <T> List<T>.newestFirst(id: (T) -> UUID): List<T> =
    sortedWith(Comparator { left, right -> compareIds(id(right), id(left)) })

/** Normalises an amount to `NUMERIC(23, 6)`, which is what a row read back would carry. */
private fun atStorageScale(value: BigDecimal): BigDecimal =
    value.setScale(MoneyPolicy.STORAGE_SCALE, MoneyPolicy.ROUNDING)
