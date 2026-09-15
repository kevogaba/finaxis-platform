package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.support.FakeAccountingPermissionGuard
import com.finaxis.platform.accounting.support.FakeJournalReadStore
import com.finaxis.platform.accounting.support.PermissionCheck
import com.finaxis.platform.accounting.support.PermissionCheckKind
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.web.pagination.PaginationProperties
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lineage read behaviour, stated at the edges a container start cannot afford to visit.
 *
 * Three things are only observable from here. *Where the permission check sits*: a reader that
 * answers "no such journal" before asking whether the caller may look has leaked the row's
 * existence, and the evidence is the store having been touched at all - which is why the read
 * store is wrapped in a recorder rather than merely seeded. *What the page bound does*: the
 * service refuses a page size past the ceiling instead of clamping it, and the difference between
 * those two is a caller that learns its request was wrong and one that silently gets fewer rows.
 * *That the cursor is carried*: the fake implements the real keyset, so a page asked for without
 * its predecessor's cursor returns the first page again and fails here rather than in production.
 *
 * `PostingLineageIntegrationTests` proves the same reads run through the real adapter, the real
 * guard and real rows; nothing below asserts anything the database itself enforces.
 */
class PostingLineageServiceTests {
    private val journals = RecordingJournalReadStore()
    private val permissions = FakeAccountingPermissionGuard()
    private val service = PostingLineageService(journals, permissions, BOUNDS)

    /** The same service under the platform's own bounds, for pages [BOUNDS] is too small for. */
    private val platform =
        PostingLineageService(journals, permissions, PaginationProperties())

    @Test
    fun `a source reference reads forward to its request, journal and lines`() {
        val request = seedRequest(requestId(1))
        val journal = seedJournal(journalId(1), request)
        seedLines(journal.id)

        val lineage = assertNotNull(service.findBySource(sourceQuery()))

        assertEquals(request, lineage.request)
        assertEquals(journal, lineage.journal)
        assertEquals(listOf(1, 2), lineage.lines.map { it.lineNumber }, "lines come in line order")
        assertEquals(
            listOf(
                PermissionCheck(
                    kind = PermissionCheckKind.TENANT,
                    actorId = ACTOR,
                    organisationId = ORGANISATION,
                    permissionCode = AccountingPermissions.JOURNAL_VIEW,
                ),
            ),
            permissions.checks,
            "one tenant-scoped journal-view check, made with the query's own actor and tenant",
        )
        assertEquals(
            listOf("findPostingRequestBySource", "findJournalEntryForRequest", "findJournalLines"),
            journals.methods,
            "the forward read walks request to journal to lines, and stops there",
        )
    }

    @Test
    fun `a source reference nobody posted against is null, and the journal is never read`() {
        assertNull(service.findBySource(sourceQuery(sourceReference = "LN-404")))

        assertEquals(
            listOf(AccountingPermissions.JOURNAL_VIEW),
            permissions.checkedCodes,
            "the miss is still an authorized read, not an unchecked one",
        )
        assertEquals(
            listOf("findPostingRequestBySource"),
            journals.methods,
            "with no request there is nothing to look a journal up by",
        )
    }

    @Test
    fun `a request whose journal is not yet committed reads back with no journal and no lines`() {
        // PostingLineage.journal is nullable because the store cannot promise otherwise: a PENDING
        // request inside another open transaction is the case the type is keeping room for.
        val request = seedRequest(requestId(1))

        val lineage = assertNotNull(service.findBySource(sourceQuery()))

        assertEquals(request, lineage.request)
        assertNull(lineage.journal)
        assertTrue(lineage.lines.isEmpty(), "no journal means no lines, not an empty-journal read")
        assertEquals(
            listOf("findPostingRequestBySource", "findJournalEntryForRequest"),
            journals.methods,
            "findJournalLines is never called without a journal id to call it with",
        )
    }

    @Test
    fun `the journal-view permission is required before the store is touched, on every read`() {
        val request = seedRequest(requestId(1))
        seedJournal(journalId(1), request)
        permissions.refuse(AccountingPermissions.JOURNAL_VIEW)

        assertFailsWith<ForbiddenOperationException> { service.findBySource(sourceQuery()) }
        assertFailsWith<ForbiddenOperationException> {
            service.findByJournal(journalQuery(journalId(1)))
        }
        assertFailsWith<ForbiddenOperationException> { service.listForSourceEntity(entityQuery()) }

        assertEquals(
            List(REFUSED_READS) { AccountingPermissions.JOURNAL_VIEW },
            permissions.checkedCodes,
            "all three public reads ask, and ask for the same permission",
        )
        assertTrue(
            journals.reads.isEmpty(),
            "a refused caller must not learn whether the rows exist; the guard precedes the read",
        )
        assertTrue(
            journals.store.requestedPageSizes.isEmpty(),
            "the listing is refused before it reaches the store, page size unvalidated",
        )
    }

    @Test
    fun `a journal reads back to the request that produced it`() {
        val request = seedRequest(requestId(1))
        val journal = seedJournal(journalId(1), request)
        seedLines(journal.id)

        val lineage = assertNotNull(service.findByJournal(journalQuery(journal.id)))

        assertEquals(request, lineage.request)
        assertEquals(journal, lineage.journal)
        assertEquals(listOf(1, 2), lineage.lines.map { it.lineNumber })
        assertEquals(listOf(AccountingPermissions.JOURNAL_VIEW), permissions.checkedCodes)
        assertEquals(
            listOf("findJournalEntry", "findPostingRequest", "findJournalLines"),
            journals.methods,
            "the backward read walks journal to request, and reads the lines of the journal it " +
                "already holds rather than looking it up again",
        )
    }

    @Test
    fun `a journal this tenant has not posted is null, and the request is never read`() {
        assertNull(service.findByJournal(journalQuery(journalId(9))))

        assertEquals(listOf(AccountingPermissions.JOURNAL_VIEW), permissions.checkedCodes)
        assertEquals(
            listOf("findJournalEntry"),
            journals.methods,
            "a missing journal ends the read; nothing downstream is attempted",
        )
    }

    @Test
    fun `a journal whose posting request is missing fails naming both ids`() {
        // journal_entry.posting_request_id is a NOT NULL foreign key, so this is unreachable
        // through the schema and can only be staged. It is still worth asserting: the message is
        // the only thing that would name the two rows an operator has to go and look at.
        val journal = seedJournal(journalId(1), seedRequest(requestId(1)))
        journals.store.requests.clear()

        val orphaned =
            assertFailsWith<IllegalStateException> {
                service.findByJournal(journalQuery(journal.id))
            }

        assertEquals(
            "journal_entry ${journal.id} references posting_request " +
                "${journal.postingRequestId}, which does not exist; the foreign key forbids this",
            orphaned.message,
            "the message has to name the journal and the request it points at, or the row that " +
                "needs looking at cannot be found from the log line",
        )
        assertEquals(
            listOf("findJournalEntry", "findPostingRequest"),
            journals.methods,
            "the lines are not read for a lineage that cannot be assembled",
        )
    }

    @Test
    fun `an unset page size takes the configured default`() {
        seedRequest(requestId(1))

        service.listForSourceEntity(entityQuery())

        assertEquals(listOf(BOUNDS.defaultPageSize), journals.store.requestedPageSizes)

        // The platform's own bounds flow through unchanged, ceiling included.
        platform.listForSourceEntity(entityQuery())

        assertEquals(listOf(BOUNDS.defaultPageSize, 25), journals.store.requestedPageSizes)
        assertEquals(
            "The page size must be between 1 and 100.",
            assertFailsWith<InvalidOperationException> {
                platform.listForSourceEntity(entityQuery(pageSize = 101))
            }.safeDetail,
        )
    }

    @Test
    fun `a page size past the ceiling, or below one, is refused rather than clamped`() {
        // Clamping would hand back fewer rows than asked for with no way to tell that from a
        // short last page, so a caller paging on size would stop early and believe it was done.
        val tooLarge =
            assertFailsWith<InvalidOperationException> {
                service.listForSourceEntity(entityQuery(pageSize = BOUNDS.maxPageSize + 1))
            }

        assertEquals("accounting.page_size_out_of_range", tooLarge.code)
        assertEquals("The page size must be between 1 and 4.", tooLarge.safeDetail)
        listOf(0, -1).forEach { size ->
            assertEquals(
                "accounting.page_size_out_of_range",
                assertFailsWith<InvalidOperationException> {
                    service.listForSourceEntity(entityQuery(pageSize = size))
                }.code,
                "a page size of $size is as far out of range as one past the ceiling",
            )
        }
        assertTrue(journals.reads.isEmpty(), "a refused bound never reaches the store")
        assertEquals(
            REFUSED_READS,
            permissions.checkedCodes.size,
            "the guard ran on every attempt, ahead of the bound that refused it",
        )
    }

    @Test
    fun `a full page carries the cursor its continuation resumes from`() {
        val requests =
            (1..PAGED_REQUESTS).map { seedRequest(requestId(it), sourceReference = "L$it") }
        seedLines(seedJournal(journalId(5), requests.last()).id)

        val first = service.listForSourceEntity(entityQuery())

        assertEquals(listOf(requestId(5), requestId(4)), first.items.map { it.request.id })
        assertEquals(requestId(4), first.nextCursor, "the cursor is the oldest id on the page")
        assertEquals(
            journalId(5),
            first.items
                .first()
                .journal
                ?.id,
        )
        assertEquals(
            listOf(1, 2),
            first.items
                .first()
                .lines
                .map { it.lineNumber },
        )
        assertNull(first.items.last().journal, "each item carries its own journal, or none")
        assertTrue(
            first.items
                .last()
                .lines
                .isEmpty(),
        )

        val second = service.listForSourceEntity(entityQuery(cursor = first.nextCursor))

        assertEquals(listOf(requestId(3), requestId(2)), second.items.map { it.request.id })
        assertEquals(requestId(2), second.nextCursor)

        val third = service.listForSourceEntity(entityQuery(cursor = second.nextCursor))

        assertEquals(listOf(requestId(1)), third.items.map { it.request.id })
        assertNull(third.nextCursor, "a page short of the size asked for is the last page")
        assertEquals(
            listOf(null, requestId(4), requestId(2)),
            journals.store.requestedCursors,
            "the cursor is forwarded to the store, so the keyset excludes what was already read",
        )
    }

    @Test
    fun `a page that fills exactly still carries a cursor, and the page after it is empty`() {
        (1..BOUNDS.maxPageSize).forEach { seedRequest(requestId(it), sourceReference = "L$it") }

        val full = service.listForSourceEntity(entityQuery(pageSize = BOUNDS.maxPageSize))

        assertEquals(BOUNDS.maxPageSize, full.items.size, "the ceiling itself is an allowed size")
        assertEquals(
            requestId(1),
            full.nextCursor,
            "a page that fills cannot know it is the last one, so it offers a cursor and the " +
                "caller spends one more read finding out",
        )

        val beyond =
            service.listForSourceEntity(
                entityQuery(pageSize = BOUNDS.maxPageSize, cursor = full.nextCursor),
            )

        assertTrue(beyond.items.isEmpty())
        assertNull(beyond.nextCursor, "an empty page ends the walk")
    }

    @Test
    fun `a page of lineages costs three store reads however many items it carries`() {
        // Issue #96: hydrating item by item cost 1 + 2N round trips, so a page at the platform
        // ceiling of a hundred was two hundred and one. The count is the behaviour, not an
        // implementation detail - it is the only thing that changed and the only thing that can
        // silently change back.
        (1..HYDRATED_REQUESTS).forEach { sequence ->
            val request = seedRequest(requestId(sequence), sourceReference = "L$sequence")
            seedLines(seedJournal(journalId(sequence), request).id)
        }

        val page = platform.listForSourceEntity(entityQuery())

        assertEquals(HYDRATED_REQUESTS, page.items.size, "the whole page hydrates in one go")
        assertEquals(
            listOf(
                "listPostingRequestsForEntity",
                "findJournalEntriesForRequests",
                "findJournalLinesForEntries",
            ),
            journals.methods,
            "a page of $HYDRATED_REQUESTS lineages must cost exactly three store reads; a " +
                "findJournalEntryForRequest or findJournalLines in this list is the per-item " +
                "hydration of issue #96 reintroduced, and it grows with the page size",
        )
    }

    @Test
    fun `a hydrated page keeps its order and gives each item only its own journal's lines`() {
        val requests =
            (1..HYDRATION_SHAPES).map { seedRequest(requestId(it), sourceReference = "L$it") }
        seedLines(seedJournal(journalId(1), requests[0]).id, subledgerReference = "SUB-1")
        // requests[1] never reached a journal; requests[3]'s journal carries no line. Those are
        // the two absences the grouped read answers with a missing key rather than an empty list.
        seedLines(seedJournal(journalId(3), requests[2]).id, subledgerReference = "SUB-3")
        seedJournal(journalId(4), requests[3])

        val page = platform.listForSourceEntity(entityQuery())

        assertEquals(
            listOf(requestId(4), requestId(3), requestId(2), requestId(1)),
            page.items.map { it.request.id },
            "batching must leave the keyset's newest-request-first order exactly as it was",
        )
        assertEquals(
            listOf(journalId(4), journalId(3), null, journalId(1)),
            page.items.map { it.journal?.id },
            "each item keeps its own journal, and a request that has none keeps null",
        )
        assertEquals(
            listOf(1, 2),
            page.items[1].lines.map { it.lineNumber },
            "a journal's lines still arrive in ascending line order",
        )
        assertEquals(
            listOf("SUB-3", "SUB-3"),
            page.items[1].lines.map { it.subledgerReference },
            "the grouped read must hand each journal its own lines, never another journal's",
        )
        assertEquals(
            listOf("SUB-1", "SUB-1"),
            page.items[3].lines.map { it.subledgerReference },
            "the oldest item's lines are its own too, not the ones read for the newest",
        )
        assertNull(page.items[2].journal, "a request with no journal carries none")
        assertTrue(page.items[2].lines.isEmpty(), "and with no journal it carries no lines")
        assertTrue(
            page.items[0].lines.isEmpty(),
            "a journal the grouped read returns no key for reads back as no lines; an absent " +
                "key and an empty list must mean the same thing to the caller",
        )
    }

    @Test
    fun `an empty page asks for no journals at all`() {
        val page = service.listForSourceEntity(entityQuery())

        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
        assertEquals(
            listOf("listPostingRequestsForEntity"),
            journals.methods,
            "with no requests on the page there is nothing to hydrate, so neither batch read " +
                "is issued - an empty `IN (...)` is not SQL worth sending",
        )
    }

    @Test
    fun `the listing answers for one business entity within one module`() {
        seedRequest(requestId(1), sourceReference = "L1")
        seedRequest(requestId(2), sourceReference = "L2", sourceEntityId = OTHER_ENTITY)
        seedRequest(requestId(3), sourceReference = "L3", sourceModule = "savings")

        val page = service.listForSourceEntity(entityQuery())

        assertEquals(
            listOf(requestId(1)),
            page.items.map { it.request.id },
            "another entity's postings, and another module's, are not this entity's lineage",
        )
        assertNull(page.nextCursor)
    }

    @Test
    fun `every read is scoped to the organisation the query names`() {
        val mine = seedRequest(requestId(1))
        seedJournal(journalId(1), mine)
        val theirs =
            seedRequest(requestId(2), organisationId = OTHER_ORGANISATION, sourceReference = "LN-2")
        seedJournal(journalId(2), theirs)

        assertEquals(mine, assertNotNull(service.findBySource(sourceQuery())).request)
        assertNull(
            service.findBySource(sourceQuery(sourceReference = "LN-2")),
            "a reference held by another tenant is not visible here",
        )
        assertNull(
            service.findByJournal(journalQuery(journalId(2))),
            "another tenant's journal is absent, not forbidden - it is not this tenant's row",
        )
        assertEquals(
            listOf(requestId(1)),
            service.listForSourceEntity(entityQuery()).items.map { it.request.id },
            "the listing filters on the tenant as well as the entity",
        )
        assertTrue(
            journals.reads.all { it.organisationId == ORGANISATION },
            "every read carried the query's own organisation id into the store",
        )
    }

    private fun seedRequest(
        id: UUID,
        organisationId: UUID = ORGANISATION,
        sourceReference: String = "LN-1",
        sourceEntityId: UUID = ENTITY,
        sourceModule: String = MODULE,
    ): PostingRequestView {
        val request =
            PostingRequestView(
                id = id,
                organisationId = organisationId,
                branchId = null,
                sourceModule = sourceModule,
                sourceEntityType = ENTITY_TYPE,
                sourceEntityId = sourceEntityId,
                sourceReference = sourceReference,
                eventCode = "loan.disbursed",
                status = PostingRequestStatus.POSTED,
                postingRuleVersionId = null,
                correctsPostingRequestId = null,
                postingDate = TODAY,
                businessDate = TODAY,
                narrative = null,
                postedAt = POSTED_AT,
                requestedBy = ACTOR,
                correlationId = null,
                requestId = null,
            )
        journals.store.addRequest(request)
        return request
    }

    private fun seedJournal(
        id: UUID,
        request: PostingRequestView,
    ): JournalEntryView {
        val journal =
            JournalEntryView(
                id = id,
                organisationId = request.organisationId,
                branchId = null,
                postingRequestId = request.id,
                fiscalPeriodId = PERIOD,
                entryNumber = 1,
                entryType = JournalEntryType.STANDARD,
                reversesJournalEntryId = null,
                postingDate = TODAY,
                businessDate = TODAY,
                currencyCode = "KES",
                functionalCurrencyCode = "KES",
                totalDebitFunctional = AMOUNT,
                lineCount = 2,
                narrative = null,
                postedAt = POSTED_AT,
                postedBy = ACTOR,
            )
        journals.store.addJournal(journal)
        return journal
    }

    /**
     * Seeds two lines out of line order, so an ordered read back means something.
     *
     * [subledgerReference] marks whose lines these are, which is how a grouped read handing one
     * journal another journal's lines is told apart from one that grouped them correctly.
     */
    private fun seedLines(
        journalEntryId: UUID,
        subledgerReference: String? = null,
    ) {
        journals.store.putLines(
            journalEntryId,
            listOf(
                line(2, PostingSide.CREDIT, subledgerReference),
                line(1, PostingSide.DEBIT, subledgerReference),
            ),
        )
    }

    private fun line(
        lineNumber: Int,
        side: PostingSide,
        subledgerReference: String? = null,
    ) = JournalLineView(
        lineNumber = lineNumber,
        accountId = ACCOUNT,
        side = side,
        amount = AMOUNT,
        currencyCode = "KES",
        functionalAmount = AMOUNT,
        narrative = null,
        subledgerReference = subledgerReference,
    )

    private fun sourceQuery(
        organisationId: UUID = ORGANISATION,
        sourceReference: String = "LN-1",
    ) = SourceLineageQuery(
        organisationId = organisationId,
        actorId = ACTOR,
        sourceModule = MODULE,
        sourceReference = sourceReference,
    )

    private fun journalQuery(journalEntryId: UUID) =
        JournalLineageQuery(ORGANISATION, ACTOR, journalEntryId)

    private fun entityQuery(
        pageSize: Int? = null,
        cursor: UUID? = null,
    ) = SourceEntityLineageQuery(
        organisationId = ORGANISATION,
        actorId = ACTOR,
        sourceModule = MODULE,
        sourceEntityType = ENTITY_TYPE,
        sourceEntityId = ENTITY,
        pageSize = pageSize,
        cursor = cursor,
    )

    private companion object {
        val ORGANISATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OTHER_ORGANISATION: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val ACTOR: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val ENTITY: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val OTHER_ENTITY: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val ACCOUNT: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val PERIOD: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val AMOUNT: BigDecimal = BigDecimal("100.000000")
        val POSTED_AT: Instant = Instant.parse("2026-02-11T09:15:00Z")
        val TODAY: LocalDate = LocalDate.parse("2026-02-11")
        const val MODULE = "loans"
        const val ENTITY_TYPE = "loan"

        /** Small enough that a default page and a ceiling breach need only a handful of rows. */
        val BOUNDS = PaginationProperties(defaultPageSize = 2, maxPageSize = 4)

        /** Requests seeded for the three-page walk: two full pages and a short one. */
        const val PAGED_REQUESTS = 5

        /** The three public reads, each of which is refused once. */
        const val REFUSED_READS = 3

        /**
         * Enough items that per-item hydration would cost twenty-one reads rather than three.
         *
         * Read under [PaginationProperties]'s own default page size, not [BOUNDS], so the page
         * holds all of them and the read count is the count for the whole page.
         */
        const val HYDRATED_REQUESTS = 10

        /** One request with a journal and lines, one with no journal, and one journal with none. */
        const val HYDRATION_SHAPES = 4
    }
}

/** One read the service made, so the guard can be proven to precede every one of them. */
private data class StoreRead(
    val method: String,
    val organisationId: UUID,
)

/**
 * [FakeJournalReadStore] with the call recording this suite needs wrapped around it.
 *
 * The shared fake records the page size and cursor of the keyset listing, which is what the
 * pagination assertions need, but nothing about the single-row reads - and permission *ordering*
 * cannot be asserted from state alone, only from whether the store was entered at all. Wrapping
 * rather than editing the shared fake keeps that need local to the one suite that has it.
 */
private class RecordingJournalReadStore : JournalReadStore {
    /** The wrapped fake: seed through it, and read its own recordings from it. */
    val store = FakeJournalReadStore()

    /** Every read made, in call order, with the tenant it was made for. */
    val reads = mutableListOf<StoreRead>()

    /** Just the method names, in call order. */
    val methods: List<String>
        get() = reads.map { it.method }

    override fun findJournalEntry(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView? {
        reads += StoreRead("findJournalEntry", organisationId)
        return store.findJournalEntry(organisationId, journalEntryId)
    }

    override fun findJournalEntryForRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): JournalEntryView? {
        reads += StoreRead("findJournalEntryForRequest", organisationId)
        return store.findJournalEntryForRequest(organisationId, postingRequestId)
    }

    override fun findReversalOf(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalEntryView? {
        reads += StoreRead("findReversalOf", organisationId)
        return store.findReversalOf(organisationId, journalEntryId)
    }

    override fun findPostingRequest(
        organisationId: UUID,
        postingRequestId: UUID,
    ): PostingRequestView? {
        reads += StoreRead("findPostingRequest", organisationId)
        return store.findPostingRequest(organisationId, postingRequestId)
    }

    override fun findPostingRequestBySource(
        organisationId: UUID,
        sourceModule: String,
        sourceReference: String,
    ): PostingRequestView? {
        reads += StoreRead("findPostingRequestBySource", organisationId)
        return store.findPostingRequestBySource(organisationId, sourceModule, sourceReference)
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
        reads += StoreRead("listPostingRequestsForEntity", organisationId)
        return store.listPostingRequestsForEntity(
            organisationId = organisationId,
            sourceModule = sourceModule,
            sourceEntityType = sourceEntityType,
            sourceEntityId = sourceEntityId,
            beforeId = beforeId,
            pageSize = pageSize,
        )
    }

    override fun findJournalLines(
        organisationId: UUID,
        journalEntryId: UUID,
    ): List<JournalLineView> {
        reads += StoreRead("findJournalLines", organisationId)
        return store.findJournalLines(organisationId, journalEntryId)
    }

    override fun findJournalEntriesForRequests(
        organisationId: UUID,
        postingRequestIds: Collection<UUID>,
    ): Map<UUID, JournalEntryView> {
        reads += StoreRead("findJournalEntriesForRequests", organisationId)
        return store.findJournalEntriesForRequests(organisationId, postingRequestIds)
    }

    override fun findJournalLinesForEntries(
        organisationId: UUID,
        journalEntryIds: Collection<UUID>,
    ): Map<UUID, List<JournalLineView>> {
        reads += StoreRead("findJournalLinesForEntries", organisationId)
        return store.findJournalLinesForEntries(organisationId, journalEntryIds)
    }
}

/**
 * Ascending ids, so the keyset's newest-first order over a seeded page is known rather than hoped.
 *
 * Deliberately not `uuidV7()`: ids minted inside one millisecond are not guaranteed to ascend, and
 * a pagination assertion that depends on them is a test that fails once in a hundred runs.
 */
private fun requestId(sequence: Int): UUID =
    UUID.fromString("0199aa00-0000-7000-8000-%012x".format(sequence))

/** Journal ids from a separate block, so a mixed-up id cannot accidentally match a request. */
private fun journalId(sequence: Int): UUID =
    UUID.fromString("0199bb00-0000-7000-8000-%012x".format(sequence))
