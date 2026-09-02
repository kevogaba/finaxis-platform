package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PostingFingerprintTests {
    private val debitAccount = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val creditAccount = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val source =
        AccountingSourceReference("savings", "SAVINGS_DEPOSIT", UUID.randomUUID(), "dep-1")
    private val dates =
        AccountingDates(
            businessDate = LocalDate.of(2026, 8, 15),
            transactionDate = LocalDate.of(2026, 8, 15),
            valueDate = LocalDate.of(2026, 8, 15),
            postingDate = LocalDate.of(2026, 8, 15),
            recordedAt = Instant.parse("2026-08-15T10:00:00Z"),
        )

    @Test
    fun `the digest is sixty-four lower-case hex characters`() {
        val digest = fingerprint(legs())

        assertTrue(Regex("^[0-9a-f]{64}$").matches(digest), digest)
    }

    @Test
    fun `the same request in a different leg order is the same fingerprint`() {
        assertEquals(fingerprint(legs()), fingerprint(legs().reversed()))
    }

    @Test
    fun `a different amount account side or date is a different fingerprint`() {
        val base = fingerprint(legs())

        assertNotEquals(base, fingerprint(legs(amount = "100.01")))
        assertNotEquals(base, fingerprint(legs(debit = creditAccount, credit = debitAccount)))
        assertNotEquals(
            base,
            fingerprint(legs(), dates.copy(valueDate = dates.valueDate.plusDays(1))),
        )
        assertNotEquals(base, fingerprint(legs(), entryType = JournalEntryType.MANUAL))
    }

    @Test
    fun `narrative changes do not change the fingerprint`() {
        // A retry that rewords its description is not a different financial fact.
        assertEquals(fingerprint(legs()), fingerprint(legs(narrative = "reworded")))
    }

    @Test
    fun `the recorded-at instant is not part of the identity`() {
        // Two attempts at the same posting are made at different instants by definition.
        assertEquals(
            fingerprint(legs()),
            fingerprint(legs(), dates.copy(recordedAt = dates.recordedAt.plusSeconds(1))),
        )
    }

    private fun fingerprint(
        legs: List<PostingLeg>,
        at: AccountingDates = dates,
        entryType: JournalEntryType = JournalEntryType.STANDARD,
    ) = PostingFingerprint.of(source, "SAVINGS_DEPOSIT", entryType, at, "KES", legs)

    private fun legs(
        amount: String = "100.000000",
        debit: UUID = debitAccount,
        credit: UUID = creditAccount,
        narrative: String? = null,
    ) = listOf(
        PostingLeg(debit, PostingSide.DEBIT, MonetaryAmount(BigDecimal(amount), "KES"), narrative),
        PostingLeg(
            credit,
            PostingSide.CREDIT,
            MonetaryAmount(BigDecimal(amount), "KES"),
            narrative,
        ),
    )
}
