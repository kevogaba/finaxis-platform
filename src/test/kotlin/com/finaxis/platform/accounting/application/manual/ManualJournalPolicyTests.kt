package com.finaxis.platform.accounting.application.manual

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.ManualJournal
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The manual-journal rules that need nothing but the draft, exercised without a Spring context.
 *
 * `ManualJournalIntegrationTests` proves these fire through the real service, the real permission
 * guard and the real database; this suite is where their *edges* are affordable to state - the
 * exact bound, the character that is whitespace to Kotlin, the version that is one behind. A rule
 * about money whose only test costs a container start is a rule whose edges go untested.
 */
class ManualJournalPolicyTests {
    @Test
    fun `an amendment prepared against an earlier version is refused`() {
        val journal = journal(rowVersion = 4)

        ManualJournalPolicy.requireCurrentVersion(journal, 4)

        val stale =
            assertFailsWith<ConflictException> {
                ManualJournalPolicy.requireCurrentVersion(journal, 3)
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_STALE, stale.code)
        // A version *ahead* of the row is as stale as one behind: it is a view of a draft this
        // one is not, not a newer one, and guessing which way it drifted is not the check's job.
        assertEquals(
            PostingErrorCodes.MANUAL_JOURNAL_STALE,
            assertFailsWith<ConflictException> {
                ManualJournalPolicy.requireCurrentVersion(journal, 5)
            }.code,
        )
    }

    @Test
    fun `both stale-edit refusals carry the same code and message`() {
        // The service raises one of these under the header's lock and the other from the store's
        // compare-and-set. A caller retrying on MANUAL_JOURNAL_STALE has to get one answer.
        val underLock =
            assertFailsWith<ConflictException> {
                ManualJournalPolicy.requireCurrentVersion(journal(rowVersion = 1), 0)
            }
        val fromTheStore = ManualJournalPolicy.staleEdit()

        assertEquals(underLock.code, fromTheStore.code)
        assertEquals(underLock.message, fromTheStore.message)
    }

    @Test
    fun `an external reference is absent, or non-blank and within the column's bound`() {
        ManualJournalPolicy.requireStorableExternalReference(null)
        ManualJournalPolicy.requireStorableExternalReference("ADV-4471")
        ManualJournalPolicy.requireStorableExternalReference(
            "R".repeat(ManualJournalPolicy.MAXIMUM_EXTERNAL_REFERENCE),
        )

        assertEquals(
            PostingErrorCodes.MANUAL_JOURNAL_EXTERNAL_REFERENCE_INVALID,
            assertFailsWith<InvalidOperationException> {
                ManualJournalPolicy.requireStorableExternalReference(
                    "R".repeat(ManualJournalPolicy.MAXIMUM_EXTERNAL_REFERENCE + 1),
                )
            }.code,
        )
    }

    @Test
    fun `a reference of whitespace is refused whatever the whitespace is`() {
        // The database says the same thing with `~ '[^[:space:]]'`. An earlier revision wrote
        // `btrim(...) <> ''`, which strips spaces only, so a tab-only reference passed the column
        // and failed here - the two contracts disagreeing about one value.
        listOf("", " ", "   ", "\t", "\n", " \t\n ").forEach { blank ->
            assertEquals(
                PostingErrorCodes.MANUAL_JOURNAL_EXTERNAL_REFERENCE_INVALID,
                assertFailsWith<InvalidOperationException> {
                    ManualJournalPolicy.requireStorableExternalReference(blank)
                }.code,
                "expected `$blank` to be refused as blank",
            )
        }
    }

    @Test
    fun `the reference bound counts characters rather than UTF-16 units`() {
        // `chk_manual_journal_external_reference` bounds char_length(), which counts characters.
        // A supplementary character - every emoji is one - is two UTF-16 units in a Kotlin String,
        // so measuring `length` would refuse at half the bound a reference the column stores.
        val maximal = "🏦".repeat(ManualJournalPolicy.MAXIMUM_EXTERNAL_REFERENCE)

        ManualJournalPolicy.requireStorableExternalReference(maximal)

        assertEquals(
            PostingErrorCodes.MANUAL_JOURNAL_EXTERNAL_REFERENCE_INVALID,
            assertFailsWith<InvalidOperationException> {
                ManualJournalPolicy.requireStorableExternalReference(maximal + "🏦")
            }.code,
        )
    }

    @Test
    fun `a draft needs a title and a reason`() {
        ManualJournalPolicy.requireNarrated("Adjustment", "Correct a mis-posting")

        listOf("" to "reason", "title" to "", " " to "reason", "title" to "\t").forEach { pair ->
            assertEquals(
                PostingErrorCodes.MANUAL_JOURNAL_REASON_REQUIRED,
                assertFailsWith<InvalidOperationException> {
                    ManualJournalPolicy.requireNarrated(pair.first, pair.second)
                }.code,
            )
        }
    }

    @Test
    fun `lines are two or more, numbered without gaps, and within the cap`() {
        ManualJournalPolicy.requireContiguous(balanced())

        val tooFew =
            assertFailsWith<InvalidOperationException> {
                ManualJournalPolicy.requireContiguous(listOf(line(1, PostingSide.DEBIT, "10.00")))
            }
        assertEquals(PostingErrorCodes.MANUAL_JOURNAL_LINES_INVALID, tooFew.code)

        val gapped =
            listOf(line(1, PostingSide.DEBIT, "10.00"), line(3, PostingSide.CREDIT, "10.00"))
        assertEquals(
            PostingErrorCodes.MANUAL_JOURNAL_LINES_INVALID,
            assertFailsWith<InvalidOperationException> {
                ManualJournalPolicy.requireContiguous(gapped)
            }.code,
        )

        val capped =
            (1..ManualJournalPolicy.MAXIMUM_LINES).map {
                line(it, if (it % 2 == 0) PostingSide.CREDIT else PostingSide.DEBIT, "1.00")
            }
        ManualJournalPolicy.requireContiguous(capped)
        assertEquals(
            PostingErrorCodes.MANUAL_JOURNAL_LINES_INVALID,
            assertFailsWith<InvalidOperationException> {
                ManualJournalPolicy.requireContiguous(
                    capped +
                        line(
                            ManualJournalPolicy.MAXIMUM_LINES + 1,
                            PostingSide.CREDIT,
                            "1.00",
                        ),
                )
            }.code,
        )
    }

    @Test
    fun `an amount the draft column would round is refused rather than rounded`() {
        ManualJournalPolicy.requireSettledAmounts(balanced())

        assertFailsWith<InvalidOperationException> {
            ManualJournalPolicy.requireSettledAmounts(
                listOf(line(1, PostingSide.DEBIT, "10.0000001")),
            )
        }
    }

    @Test
    fun `debits must equal credits, at any scale`() {
        ManualJournalPolicy.requireBalanced(balanced())
        // compareTo, not equals: 10.00 and 10.000000 are the same money at different scales, and
        // an equals-based check would refuse a draft that balances.
        ManualJournalPolicy.requireBalanced(
            listOf(line(1, PostingSide.DEBIT, "10.00"), line(2, PostingSide.CREDIT, "10.000000")),
        )

        val unbalanced =
            assertFailsWith<InvalidOperationException> {
                ManualJournalPolicy.requireBalanced(
                    listOf(
                        line(1, PostingSide.DEBIT, "10.00"),
                        line(2, PostingSide.CREDIT, "9.00"),
                    ),
                )
            }
        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, unbalanced.code)
    }

    private fun balanced() =
        listOf(line(1, PostingSide.DEBIT, "10.00"), line(2, PostingSide.CREDIT, "10.00"))

    private fun line(
        lineNumber: Int,
        side: PostingSide,
        amount: String,
    ) = ManualJournalLine(
        lineNumber = lineNumber,
        accountId = ACCOUNT,
        side = side,
        amount = BigDecimal(amount),
        currencyCode = "KES",
        narrative = null,
    )

    private fun journal(rowVersion: Long) =
        ManualJournal(
            id = uuidV7(),
            organisationId = ORGANISATION,
            branchId = null,
            title = "Adjustment",
            externalReference = null,
            narrative = "Correct a mis-posting",
            status = ManualJournalStatus.DRAFT,
            statusReason = null,
            transactionDate = null,
            valueDate = null,
            postingDate = null,
            journalEntryId = null,
            createdBy = ORGANISATION,
            rowVersion = rowVersion,
        )

    private companion object {
        val ORGANISATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ACCOUNT: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
