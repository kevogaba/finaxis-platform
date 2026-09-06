package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingDateRequest
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Fingerprint composition after issue #89 (ADR 0023): computed from the caller's inputs - never
 * from the resolved legs, which the engine no longer has in hand at the point the fingerprint is
 * needed - with a length-prefixed encoding that cannot collide on delimiter content.
 * [LedgerPostingRequest.productClass] and [LedgerPostingRequest.financialFacts] are the exceptions
 * that still give a rule-resolved posting selector- and amount-level discrimination without
 * touching the legs.
 */
class PostingFingerprintTests {
    private val branchId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    private val correctsId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    private val reversesId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
    private val context =
        AccountingContext(
            organisationId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            branchId = branchId,
            actorId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        )
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
        assertTrue(Regex("^[0-9a-f]{64}$").matches(fingerprint()), fingerprint())
    }

    @Test
    fun `the same request twice is the same fingerprint`() {
        assertEquals(fingerprint(), fingerprint())
    }

    @Test
    fun `a different branch is a different fingerprint`() {
        assertNotEquals(fingerprint(), fingerprint(branchId = null))
        assertNotEquals(fingerprint(branchId = branchId), fingerprint(branchId = reversesId))
    }

    @Test
    fun `a different correction or reversal target is a different fingerprint`() {
        val base = fingerprint()

        assertNotEquals(base, fingerprint(correctsPostingRequestId = correctsId))
        assertNotEquals(base, fingerprint(reversesJournalEntryId = reversesId))
        assertNotEquals(
            fingerprint(correctsPostingRequestId = correctsId),
            fingerprint(correctsPostingRequestId = reversesId),
        )
    }

    @Test
    fun `a different event code entry type date or currency is a different fingerprint`() {
        val base = fingerprint()

        assertNotEquals(base, fingerprint(eventCode = "SAVINGS_WITHDRAWAL"))
        assertNotEquals(base, fingerprint(entryType = JournalEntryType.MANUAL))
        assertNotEquals(
            base,
            fingerprint(dates = dates.copy(valueDate = dates.valueDate.plusDays(1))),
        )
        assertNotEquals(base, fingerprint(currencyCode = "UGX"))
    }

    @Test
    fun `a different source module type id or key is a different fingerprint`() {
        val base = fingerprint()

        assertNotEquals(base, fingerprint(source = source.copy(sourceModule = "loans")))
        assertNotEquals(base, fingerprint(source = source.copy(sourceType = "LOAN_REPAYMENT")))
        assertNotEquals(base, fingerprint(source = source.copy(sourceId = UUID.randomUUID())))
        assertNotEquals(base, fingerprint(source = source.copy(idempotencyKey = "dep-2")))
    }

    /**
     * The behaviour change ADR 0023 documents deliberately: since the claim happens before a
     * rule-resolved posting's legs can safely be asked for, the digest cannot depend on the
     * *resolved* legs. `financialFacts` is the caller's own asserted amounts, known before any
     * rule runs, and is what still gives amount-level discrimination without touching the legs.
     */
    @Test
    fun `resolved legs are not part of the fingerprint, but financial facts are`() {
        // No legs parameter exists to vary, on purpose: this pins the design so a future reviewer
        // who tries to add one back finds this assertion rather than rediscovering the reasoning.
        assertEquals(fingerprint(), fingerprint())

        val base = fingerprint(facts = listOf(fact("PRINCIPAL", "500.00")))
        assertNotEquals(base, fingerprint(facts = emptyList()))
        assertNotEquals(base, fingerprint(facts = listOf(fact("PRINCIPAL", "501.00"))))
        assertNotEquals(base, fingerprint(facts = listOf(fact("FEE", "500.00"))))
        assertNotEquals(base, fingerprint(facts = listOf(fact("PRINCIPAL", "500.00", "UGX"))))
    }

    @Test
    fun `financial facts in a different order are the same fingerprint`() {
        val facts = listOf(fact("PRINCIPAL", "500.00"), fact("FEE", "50.00"))

        assertEquals(fingerprint(facts = facts), fingerprint(facts = facts.reversed()))
    }

    /**
     * Regression for the amount scale being hashed raw: `500.0` and `500.00` are the same
     * settled amount at a different [java.math.BigDecimal] scale, and the fingerprint must not
     * tell them apart, or a faithful retry that happens to format its amount differently becomes a
     * spurious `POSTING_REQUEST_CONFLICT`.
     */
    @Test
    fun `a financial fact amount at a different scale is the same fingerprint`() {
        assertEquals(
            fingerprint(facts = listOf(fact("PRINCIPAL", "500.00"))),
            fingerprint(facts = listOf(fact("PRINCIPAL", "500.0"))),
        )
        assertEquals(
            fingerprint(facts = listOf(fact("PRINCIPAL", "500"))),
            fingerprint(facts = listOf(fact("PRINCIPAL", "500.000000"))),
        )
    }

    /**
     * [com.finaxis.platform.accounting.domain.PostingRuleSelector] can route the same event code to
     * a different rule by product class, so the fingerprint must discriminate on it even when every
     * financial fact is identical - otherwise a retry with a different product class would silently
     * replay the original journal instead of conflicting.
     */
    @Test
    fun `a different product class is a different fingerprint`() {
        val base = fingerprint(productClass = "SAVINGS:REGULAR")

        assertNotEquals(base, fingerprint(productClass = null))
        assertNotEquals(base, fingerprint(productClass = "SAVINGS:PREMIUM"))
    }

    @Test
    fun `the recorded-at instant is not part of the identity`() {
        // Two attempts at the same posting are made at different instants by definition.
        assertEquals(
            fingerprint(),
            fingerprint(dates = dates.copy(recordedAt = dates.recordedAt.plusSeconds(1))),
        )
    }

    @Test
    fun `the business date alone is not part of the identity beyond what it implies`() {
        // businessDate is carried on AccountingDates for storage but the posting/transaction/value
        // dates are what the digest hashes; two dates objects differing only in businessDate would
        // be an inconsistent AccountingDates in practice, so this pins that the digest reads the
        // three transaction-facing dates rather than the struct's other field.
        assertEquals(
            fingerprint(),
            fingerprint(dates = dates.copy(businessDate = dates.businessDate.plusDays(1))),
        )
    }

    private fun fingerprint(
        source: AccountingSourceReference = this.source,
        eventCode: String = "SAVINGS_DEPOSIT",
        entryType: JournalEntryType = JournalEntryType.STANDARD,
        branchId: UUID? = this.branchId,
        correctsPostingRequestId: UUID? = null,
        reversesJournalEntryId: UUID? = null,
        dates: AccountingDates = this.dates,
        currencyCode: String = "KES",
        facts: List<FinancialFact> = emptyList(),
        productClass: String? = null,
    ) = PostingFingerprint.of(
        LedgerPostingRequest(
            context = context,
            source = source,
            eventCode = eventCode,
            entryType = entryType,
            dates = PostingDateRequest(),
            correctsPostingRequestId = correctsPostingRequestId,
            reversesJournalEntryId = reversesJournalEntryId,
            financialFacts = facts,
            productClass = productClass,
        ),
        branchId,
        dates,
        currencyCode,
    )

    private fun fact(
        code: String,
        amount: String,
        currency: String = "KES",
    ) = FinancialFact(code, MonetaryAmount(BigDecimal(amount), currency))
}
