package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two batched lineage reads of `JooqJournalStore`, executed against real rows (#96).
 *
 * `PostingLineageServiceTests` proves the service asks for a page's journals and lines once each;
 * nothing there can prove that the SQL those two calls become answers the same question the
 * per-item reads answered. Three things only PostgreSQL can say: that the entry map is keyed by
 * the *request*, which `uq_journal_entry_posting_request` is what makes unambiguous; that a
 * `GROUP BY` over returned rows omits a journal with no lines rather than mapping it to an empty
 * list; and that both reads are filtered on `organisation_id` in their own right, so an id
 * belonging to another tenant answers nothing rather than leaking a row.
 *
 * Rows are inserted through [JournalSchemaFixture] rather than the posting engine, because the
 * shapes worth reading back - a request that never produced a journal, a journal with no lines -
 * are exactly the ones the engine refuses to create.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JooqJournalStoreIntegrationTests(
    private val journals: JournalReadStore,
    dsl: DSLContext,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `the batched entry read keys by posting request and omits a request with no journal`() {
        val tenant = fixture.createTenant("batch-entries")
        val posted = fixture.insertPostingRequest(tenant.organisationId, sourceReference = "R-1")
        val journalId = fixture.insertJournalEntry(tenant, postingRequestId = posted)
        val pending =
            fixture.insertPostingRequest(
                tenant.organisationId,
                sourceReference = "R-2",
                status = "PENDING",
            )

        val entries =
            journals.findJournalEntriesForRequests(tenant.organisationId, listOf(posted, pending))

        assertEquals(
            setOf(posted),
            entries.keys,
            "the map is keyed by the request that produced the journal, and a request that has " +
                "produced none is absent - a caller reading an absent key gets no journal, " +
                "which is what a still-pending request means",
        )
        assertEquals(
            journalId,
            entries.getValue(posted).id,
            "the value is the journal of that request, not of whichever row came back first",
        )
        assertEquals(
            posted,
            entries.getValue(posted).postingRequestId,
            "the key and the view's own request id have to agree, or the join is wrong",
        )
    }

    @Test
    fun `the batched line read groups by journal in line order and omits an empty journal`() {
        val tenant = fixture.createTenant("batch-lines")
        val debits = fixture.insertJournalEntry(tenant)
        val credits = fixture.insertJournalEntry(tenant)
        val empty = fixture.insertJournalEntry(tenant)
        // Inserted out of line order, and interleaved between the two journals, so that ordered
        // and correctly grouped rows are read back rather than stumbled into.
        listOf(3, 1, 2).forEach { number ->
            fixture.insertJournalLine(tenant, debits, lineNumber = number)
            fixture.insertJournalLine(
                tenant,
                credits,
                lineNumber = number,
                glAccountId = tenant.creditAccountId,
                direction = "CREDIT",
            )
        }

        val lines =
            journals.findJournalLinesForEntries(
                tenant.organisationId,
                listOf(debits, credits, empty),
            )

        assertEquals(
            setOf(debits, credits),
            lines.keys,
            "a journal with no lines is absent from the grouping, not mapped to an empty list; " +
                "a caller that reads it back as no lines is reading an absent key",
        )
        assertEquals(
            listOf(listOf(1, 2, 3), listOf(1, 2, 3)),
            listOf(debits, credits).map { id -> lines.getValue(id).map { it.lineNumber } },
            "each group arrives in ascending line_number, which is what the ORDER BY on " +
                "(journal_entry_id, line_number) is for",
        )
        assertEquals(
            listOf(
                List(LINES_PER_JOURNAL) { tenant.debitAccountId },
                List(LINES_PER_JOURNAL) { tenant.creditAccountId },
            ),
            listOf(debits, credits).map { id -> lines.getValue(id).map { it.accountId } },
            "a group holds only its own journal's lines; interleaved inserts must not bleed " +
                "one journal's lines into another's",
        )
        assertEquals(
            lines.getValue(debits),
            journals.findJournalLines(tenant.organisationId, debits),
            "the single-journal read is the one-element case of the batch, so the two cannot " +
                "answer differently",
        )
    }

    @Test
    fun `neither batched read answers for an id another tenant owns`() {
        val owner = fixture.createTenant("batch-owner")
        val other = fixture.createTenant("batch-other")
        val request = fixture.insertPostingRequest(owner.organisationId)
        val journalId = fixture.insertJournalEntry(owner, postingRequestId = request)
        fixture.insertJournalLine(owner, journalId)

        assertTrue(
            journals.findJournalEntriesForRequests(other.organisationId, listOf(request)).isEmpty(),
            "a journal is reachable only through the organisation that owns it; an id guessed " +
                "or carried over from another tenant must answer nothing",
        )
        assertTrue(
            journals.findJournalLinesForEntries(other.organisationId, listOf(journalId)).isEmpty(),
            "journal_line is filtered on its own organisation_id, not trusted to be safe " +
                "because its header was checked somewhere else",
        )
        assertEquals(
            listOf(setOf(request), setOf(journalId)),
            listOf(
                journals.findJournalEntriesForRequests(owner.organisationId, listOf(request)).keys,
                journals.findJournalLinesForEntries(owner.organisationId, listOf(journalId)).keys,
            ),
            "the owner still reads its own rows, so the two refusals above are isolation and " +
                "not a predicate that returns nothing for everybody",
        )
    }

    @Test
    fun `an empty collection of ids is answered without going to the database`() {
        val tenant = fixture.createTenant("batch-empty")
        fixture.insertBalancedJournal(tenant)

        assertTrue(
            journals.findJournalEntriesForRequests(tenant.organisationId, emptyList()).isEmpty(),
            "a page with no requests has no journals to fetch, so the read is skipped rather " +
                "than sent as a predicate over an empty list",
        )
        assertTrue(
            journals.findJournalLinesForEntries(tenant.organisationId, emptyList()).isEmpty(),
            "and a page whose requests produced no journal at all has no lines to fetch either",
        )
    }

    private companion object {
        /** Lines seeded per journal in the grouping test. */
        const val LINES_PER_JOURNAL = 3
    }
}
