package com.finaxis.platform.accounting.subledger

import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerMovementPage
import com.finaxis.platform.accounting.SubledgerPosition
import com.finaxis.platform.accounting.SubledgerStatementCursor
import com.finaxis.platform.accounting.SubledgerStatementProvider
import com.finaxis.platform.accounting.SubledgerStatementQuery
import com.finaxis.platform.accounting.application.subledger.SubledgerStatement
import com.finaxis.platform.accounting.application.subledger.SubledgerStatementAssembler
import com.finaxis.platform.common.application.InvalidOperationException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The conformance suite every [SubledgerStatementProvider] must pass.
 *
 * A future savings, share or loan module extends this, points [provider] at its own implementation
 * and [newPosition]/[record] at its own writes, and inherits the obligations issue #50 (and #142's
 * stale-cursor fix) rather than re-deriving them. The point is that the obligations are
 * **executable**: a contract stated only in a document is one each module interprets slightly
 * differently, and the differences surface as a member's statement disagreeing with a control
 * account.
 *
 * What it does not do is prescribe storage. A module owns its own rows, its own projection and its
 * own transaction boundaries; this asks only that the answers come out right, including the cases
 * implementations get wrong — a movement backdated behind a checkpoint, a movement backdated
 * mid-walk, and a reversal.
 *
 * Extending classes supply [newPosition], [record] and, where they keep one, [settle], and nothing
 * else. Every assertion below is written against the port, so a module cannot satisfy it by
 * reaching into accounting.
 */
abstract class SubledgerStatementContract {
    /** One movement a scenario asks the implementation to record. */
    data class SeededMovement(
        val postingDate: LocalDate,
        val recordedOn: LocalDate,
        val signedAmount: BigDecimal,
    )

    /** The provider under test. */
    protected abstract fun provider(): SubledgerStatementProvider

    /**
     * Creates a fresh position with no movements recorded against it yet.
     *
     * Split out from [record] so a scenario that needs to interleave "record some movements, let a
     * page be served, then record more" — the stale-cursor cases below — can hold the position
     * across both calls rather than only ever seeing it fully seeded in one shot.
     */
    protected abstract fun newPosition(): SubledgerPosition

    /**
     * Records [movements] against an existing [position].
     *
     * [SeededMovement.recordedOn] is the date the movement was *recorded*, which may be later than
     * the date it is dated at — that is the backdated case, and an implementation that ignores the
     * distinction will fail `a movement backdated behind a checkpoint still appears`. Calling this
     * more than once against the same [position] is exactly how a movement recorded *after* an
     * earlier call — and dated at or before a day an earlier call already covered — is arranged.
     */
    protected abstract fun record(
        position: SubledgerPosition,
        movements: List<SeededMovement>,
    )

    /**
     * Settles whatever checkpoint the implementation keeps, if any.
     *
     * A no-op by default: [SubledgerStatementProvider]'s own KDoc treats a checkpoint as something
     * a *high-velocity* position needs, not every position, so most implementations of this
     * contract keep none. One that does keep a checkpoint overrides this to build or advance it, so
     * a scenario can arrange "the checkpoint was taken here" before recording movements the
     * checkpoint must not have seen.
     */
    protected open fun settle(position: SubledgerPosition) {}

    /**
     * Creates a fresh position and records [movements] against it in one call.
     *
     * Every scenario that only needs a single seeded batch calls this and nothing else; only the
     * stale-cursor scenarios below need [newPosition] and [record] as separate steps, so they call
     * those directly instead.
     */
    private fun seed(movements: List<SeededMovement>): SubledgerPosition {
        val position = newPosition()
        record(position, movements)
        return position
    }

    /** The page size the suite walks with, small enough that the paging cases actually page. */
    protected open val pageSize: Int = 2

    /** The platform's page ceiling, which the assembler holds every request to. */
    protected open val maxPageSize: Int = 100

    /**
     * Runs [block] the way a real caller of [SubledgerStatementAssembler.statementOf] must: inside
     * one transaction at `REPEATABLE_READ` isolation or stronger, so a mid-walk page's staleness
     * check and its movements read - two separate provider calls - see one consistent snapshot
     * rather than two independent `READ COMMITTED` ones with a commit window open between them.
     *
     * A no-op by default, because this contract does not prescribe storage and most conceivable
     * implementations have no transaction manager of their own to demarcate one with. A
     * JDBC-backed implementation - [GeneralLedgerStatementContractTests] among them - overrides
     * this with a real transaction, so its mid-walk cases exercise the isolation
     * [SubledgerStatementProvider.recordedBehindConsumedRange]'s own KDoc requires rather than
     * merely asserting against a provider that happens not to check.
     */
    protected open fun <T> statementRead(block: () -> T): T = block()

    @Test
    fun `an opening balance is everything dated on or before the day the window opens`() {
        val position =
            seed(
                listOf(
                    movement(DAY_1, "-100.00"),
                    movement(DAY_2, "-40.00"),
                    movement(DAY_4, "-7.00"),
                ),
            )

        assertEquals(
            BigDecimal("-140.00").stripTrailingZeros(),
            provider().openingBalanceAt(position, null, DAY_3).stripTrailingZeros(),
            "the two movements before the window, and not the one inside it",
        )
    }

    @Test
    fun `a statement's arithmetic holds and it closes at the window's as-of balance`() {
        val position =
            seed(
                listOf(
                    movement(DAY_1, "-100.00"),
                    movement(DAY_2, "-40.00"),
                    movement(DAY_3, "25.00"),
                ),
            )

        val statement = statement(position, DAY_2, DAY_4)

        assertTrue(statement.consistent, "opening plus the page's movements is the closing balance")
        assertEquals(
            BigDecimal("-100.00").stripTrailingZeros(),
            statement.openingSigned.stripTrailingZeros(),
        )
        assertEquals(
            provider().openingBalanceAt(position, null, DAY_4).stripTrailingZeros(),
            statement.closingSigned.stripTrailingZeros(),
            "a window's closing balance is the position's balance at its end date",
        )
    }

    @Test
    fun `a running balance is carried across pages and no movement repeats or is dropped`() {
        val position =
            seed(
                listOf(
                    movement(DAY_1, "-10.00"),
                    movement(DAY_2, "-20.00"),
                    movement(DAY_3, "-30.00"),
                    movement(DAY_4, "-40.00"),
                    movement(DAY_5, "-50.00"),
                ),
            )

        val seen = mutableListOf<UUID>()
        var page = statement(position, DAY_1, DAY_5)
        var pages = 1
        assertEquals(BigDecimal.ZERO.stripTrailingZeros(), page.openingSigned.stripTrailingZeros())
        seen += page.movements.map { it.movementId }
        while (page.nextCursor != null && pages < PAGE_LIMIT) {
            val carried = page.closingSigned
            page = statement(position, DAY_1, DAY_5, page.nextCursor, carried)
            assertEquals(
                carried.stripTrailingZeros(),
                page.openingSigned.stripTrailingZeros(),
                "each page opens where the previous one closed",
            )
            assertTrue(page.consistent)
            seen += page.movements.map { it.movementId }
            pages++
        }

        assertNull(page.nextCursor, "the walk terminates rather than asking forever")
        assertEquals(seen.size, seen.distinct().size, "the keyset never repeats a movement")
        assertEquals(5, seen.size, "and never drops one")
        assertEquals(
            BigDecimal("-150.00").stripTrailingZeros(),
            page.closingSigned.stripTrailingZeros(),
        )
    }

    @Test
    fun `a movement backdated behind a checkpoint still appears`() {
        // Dated on day 1, recorded on day 5. An implementation whose checkpoint covers day 1 and
        // whose delta starts after it sees this movement in neither half, and reports a balance the
        // position does not have. The general ledger's own version of this mistake is documented at
        // length on DailyBalanceReader; it is the same mistake, one ledger down.
        val position =
            seed(
                listOf(
                    movement(DAY_1, "-100.00"),
                    movement(DAY_2, "-40.00"),
                    SeededMovement(DAY_1, DAY_5, BigDecimal("-7.00")),
                ),
            )

        assertEquals(
            BigDecimal("-107.00").stripTrailingZeros(),
            provider().openingBalanceAt(position, null, DAY_1).stripTrailingZeros(),
            "the backdated movement belongs to the day it is dated at, not the day it arrived",
        )
        assertEquals(
            BigDecimal("-147.00").stripTrailingZeros(),
            provider().openingBalanceAt(position, null, DAY_5).stripTrailingZeros(),
        )
    }

    @Test
    fun `a page is refused once the ledger has recorded behind its cursor`() {
        val position = newPosition()
        record(
            position,
            listOf(movement(DAY_1, "-10.00"), movement(DAY_2, "-20.00"), movement(DAY_3, "-30.00")),
        )
        settle(position)

        val firstPage = statement(position, DAY_1, DAY_5)
        assertTrue(firstPage.nextCursor != null, "must produce a next page to page through")

        // Backdated onto a day this walk already served, recorded after the walk began: no later
        // page's keyset predicate could ever reach it, so serving one would be short by this.
        record(position, listOf(movement(DAY_1, "-5.00")))

        val failure =
            runCatching {
                statement(position, DAY_1, DAY_5, firstPage.nextCursor, firstPage.closingSigned)
            }.exceptionOrNull()
        assertTrue(
            failure is InvalidOperationException,
            "expected the stale cursor to be refused, got ${failure ?: "a statement"}",
        )
    }

    @Test
    fun `an ordinary same-day posting after the cursor does not refuse the next page`() {
        // Three movements on one day, split across a page boundary by pageSize=2: the cursor after
        // page one sits mid-day rather than on a day boundary, which is exactly the case a check
        // that only compared dates - not the cursor's own id too - would misjudge.
        val position = newPosition()
        record(
            position,
            listOf(movement(DAY_1, "-10.00"), movement(DAY_2, "-20.00"), movement(DAY_2, "-5.00")),
        )
        settle(position)

        val firstPage = statement(position, DAY_1, DAY_5)
        assertTrue(firstPage.nextCursor != null, "must produce a next page to page through")

        // Same day as the cursor, but recorded after it and therefore ordered after it in the
        // keyset too - an ordinary posting the very next page reaches on its own, not a defect.
        record(position, listOf(movement(DAY_2, "-30.00")))

        val secondPage =
            statement(position, DAY_1, DAY_5, firstPage.nextCursor, firstPage.closingSigned)

        assertEquals(
            BigDecimal("-65.00").stripTrailingZeros(),
            secondPage.closingSigned.stripTrailingZeros(),
            "a same-day posting ordered after the cursor is forward progress, not a stale cursor",
        )
    }

    @Test
    fun `a reversal nets out rather than being hidden`() {
        val position =
            seed(
                listOf(
                    movement(DAY_1, "-100.00"),
                    movement(DAY_2, "100.00"),
                ),
            )

        val statement = statement(position, DAY_1, DAY_3)

        assertEquals(
            2,
            statement.movements.size,
            "both halves stay visible - a statement shows what happened, not what remains",
        )
        assertEquals(
            BigDecimal.ZERO.stripTrailingZeros(),
            statement.closingSigned.stripTrailingZeros(),
            "and they net to zero by arithmetic rather than by filtering",
        )
    }

    @Test
    fun `a window that ends before it starts is refused before a provider is asked`() {
        val position = seed(listOf(movement(DAY_1, "-10.00")))

        assertRefused(position, DAY_3, DAY_1, pageSize)
    }

    @Test
    fun `a window wider than a year and a day is refused`() {
        val position = seed(listOf(movement(DAY_1, "-10.00")))

        assertRefused(position, DAY_1, DAY_1.plusYears(2), pageSize)
    }

    @Test
    fun `a page size outside the platform's bounds is refused`() {
        val position = seed(listOf(movement(DAY_1, "-10.00")))

        assertRefused(position, DAY_1, DAY_2, maxPageSize + 1)
        assertRefused(position, DAY_1, DAY_2, 0)
    }

    /**
     * The assembler refuses, and it refuses **before** the provider is asked anything.
     *
     * That ordering is the promise the port makes to an implementation: a provider applies its page
     * size as a `LIMIT` rather than re-validating it, which is only safe if nothing unbounded ever
     * reaches it.
     */
    private fun assertRefused(
        position: SubledgerPosition,
        fromDate: LocalDate,
        toDate: LocalDate,
        pageSize: Int,
    ) {
        val query =
            SubledgerStatementQuery(
                position = position,
                fromDate = fromDate,
                toDate = toDate,
                pageSize = pageSize,
            )
        // Handing in a prebuilt zero and an empty page would assert nothing about ordering: the
        // refusal would pass whether the bounds were checked before the reads or long after them.
        // A provider that fails the test if it is touched is what makes the ordering observable.
        val mustNotBeAsked =
            object : SubledgerStatementProvider {
                override val providerName: String = "must-not-be-asked"

                override fun supports(kind: ControlSubledgerKind): Boolean = true

                override fun openingBalanceAt(
                    position: SubledgerPosition,
                    branchId: UUID?,
                    asOfDate: LocalDate,
                ): BigDecimal =
                    fail("an opening balance was read for a query that must be refused first")

                override fun movements(query: SubledgerStatementQuery): SubledgerMovementPage =
                    fail("movements were read for a query that must be refused first")

                override fun currentWatermark(): String =
                    fail("a watermark was minted for a query that must be refused first")

                override fun recordedBehindConsumedRange(
                    position: SubledgerPosition,
                    branchId: UUID?,
                    cursor: SubledgerStatementCursor,
                ): Boolean =
                    fail(
                        "the stale-cursor check was asked for a query that must be refused first",
                    )
            }
        val failure =
            runCatching {
                SubledgerStatementAssembler.statementOf(
                    provider = mustNotBeAsked,
                    query = query,
                    maxPageSize = maxPageSize,
                )
            }.exceptionOrNull()
        assertTrue(
            failure is InvalidOperationException,
            "expected the bounds to be refused, got ${failure ?: "a statement"}",
        )
    }

    private fun movement(
        postingDate: LocalDate,
        amount: String,
    ) = SeededMovement(postingDate, postingDate, BigDecimal(amount))

    private fun statement(
        position: SubledgerPosition,
        fromDate: LocalDate,
        toDate: LocalDate,
        cursor: SubledgerStatementCursor? = null,
        carried: BigDecimal? = null,
    ): SubledgerStatement {
        val query =
            SubledgerStatementQuery(
                position = position,
                fromDate = fromDate,
                toDate = toDate,
                cursor = cursor,
                pageSize = pageSize,
            )
        // Through the orchestration rather than assembling two reads by hand, so every conformance
        // test travels the path a caller travels - bounds first, provider afterwards - inside the
        // transaction statementRead demarcates, exactly as a real caller must.
        return statementRead {
            SubledgerStatementAssembler.statementOf(
                provider = provider(),
                query = query,
                maxPageSize = maxPageSize,
                carriedBalance = carried,
            )
        }
    }

    protected companion object {
        val DAY_1: LocalDate = LocalDate.of(2026, 8, 10)
        val DAY_2: LocalDate = LocalDate.of(2026, 8, 11)
        val DAY_3: LocalDate = LocalDate.of(2026, 8, 12)
        val DAY_4: LocalDate = LocalDate.of(2026, 8, 13)
        val DAY_5: LocalDate = LocalDate.of(2026, 8, 14)

        /** A walk that has not terminated by here is not paging, it is looping. */
        const val PAGE_LIMIT = 10
    }
}
