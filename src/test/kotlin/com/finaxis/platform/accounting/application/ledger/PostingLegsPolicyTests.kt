package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The eligibility pass the posting engine and the administrator's dry run now share (issue #95).
 *
 * Two things are under test and only one of them is "does it refuse the bad thing". The other is
 * the **order** it refuses in: a leg can be defective in more than one way at once, and because
 * there is exactly one validator, the code a caller sees must not depend on which caller ran it.
 * `a leg defective twice reports the first check that fails, not the worst problem` is the
 * assertion that pins that, and it is the one that fails if someone reorders the checks for
 * readability.
 */
class PostingLegsPolicyTests {
    private val cash = uuidV7()
    private val liability = uuidV7()

    @Test
    fun `a balanced pair of legs settles to storage scale and reports its totals`() {
        val settled =
            PostingLegsPolicy.settle(
                listOf(debit("100.00"), credit("100.00")),
                FUNCTIONAL,
                accounts(),
            )

        assertEquals(2, settled.legs.size, "both legs survive the pass")
        assertEquals(
            listOf("100.000000", "100.000000"),
            settled.legs.map { it.amount.amount.toPlainString() },
            "settlement rescales to MoneyPolicy.STORAGE_SCALE, which is what a journal stores",
        )
        assertEquals(
            MoneyPolicy.STORAGE_SCALE,
            settled.totalDebit.scale(),
            "the totals are at storage scale too, because they are summed from settled legs",
        )
        assertEquals(0, settled.totalDebit.compareTo(BigDecimal("100")), "debits total 100")
        assertEquals(0, settled.totalCredit.compareTo(BigDecimal("100")), "credits total 100")
    }

    @Test
    fun `a single leg cannot balance and is refused before any account is read`() {
        var reads = 0
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(listOf(debit("100.00")), FUNCTIONAL) { id ->
                    reads++
                    accounts()(id)
                }
            }

        assertEquals(
            PostingErrorCodes.UNBALANCED_POSTING,
            failure.code,
            "fewer than two legs is an unbalanced posting, not a missing account",
        )
        assertEquals(
            "A journal needs at least two legs.",
            failure.safeDetail,
            "the engine's own message, unchanged",
        )
        assertEquals(0, reads, "the leg count is checked before any account lookup")
    }

    @Test
    fun `no legs at all is refused the same way`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(emptyList(), FUNCTIONAL, accounts())
            }

        assertEquals(
            PostingErrorCodes.UNBALANCED_POSTING,
            failure.code,
            "an empty posting is refused rather than treated as trivially balanced",
        )
    }

    @Test
    fun `a leg in a currency other than the functional one is refused`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(debit("100.00", currency = "USD"), credit("100.00")),
                    FUNCTIONAL,
                    accounts(),
                )
            }

        assertEquals(
            PostingErrorCodes.CURRENCY_NOT_SUPPORTED,
            failure.code,
            "ADR 0019 ships no rate table, so a foreign leg is refused rather than converted",
        )
        assertEquals(
            "Postings are accepted in the functional currency KES only; no exchange rate is " +
                "configured.",
            failure.safeDetail,
            "the engine's own message, unchanged, naming the currency that is accepted",
        )
    }

    @Test
    fun `a leg naming an account that cannot be read is refused as not postable`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(debit("100.00"), credit("100.00")),
                    FUNCTIONAL,
                ) { if (it == cash) postable(cash) else null }
            }

        assertEquals(
            PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
            failure.code,
            "an account the caller could not resolve is refused under the published code",
        )
        assertEquals(
            "A referenced general-ledger account does not exist.",
            failure.safeDetail,
            "the engine's own message for an account it could not lock",
        )
    }

    @Test
    fun `a leg naming a deactivated account is refused`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(debit("100.00"), credit("100.00")),
                    FUNCTIONAL,
                ) {
                    if (it == cash) {
                        postable(cash)
                    } else {
                        postable(liability).copy(status = GlAccountStatus.INACTIVE)
                    }
                }
            }

        assertEquals(
            PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
            failure.code,
            "GlAccountPostingPolicy decides postability; this pass only routes the account to it",
        )
    }

    @Test
    fun `debits that do not equal credits are refused`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(debit("100.00"), credit("99.00")),
                    FUNCTIONAL,
                    accounts(),
                )
            }

        assertEquals(PostingErrorCodes.UNBALANCED_POSTING, failure.code, "INV-1 is not negotiable")
        assertEquals(
            "Debit and credit totals must be equal and positive.",
            failure.safeDetail,
            "the engine's own message, unchanged",
        )
    }

    @Test
    fun `two legs on the same side balance to zero on the other and are refused`() {
        // Both totals equal only vacuously here: the credit side is zero, so the posting is not
        // balanced in any accounting sense, and the signum guard is what says so.
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(debit("100.00"), debit("100.00")),
                    FUNCTIONAL,
                    accounts(),
                )
            }

        assertEquals(
            PostingErrorCodes.UNBALANCED_POSTING,
            failure.code,
            "a posting with nothing on one side is refused, not accepted as 100 against 0",
        )
    }

    @Test
    fun `a leg defective twice reports the first check that fails, not the worst problem`() {
        // One leg, wrong currency AND an account that is not postable. The order is contract:
        // amount, then currency, then account. Whichever caller runs this pass must see the same
        // code, or the dry run and the posting have diverged again - which is the whole defect.
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(debit("100.00", currency = "USD"), credit("100.00")),
                    FUNCTIONAL,
                ) { postable(it).copy(usage = AccountUsage.HEADER) }
            }

        assertEquals(
            PostingErrorCodes.CURRENCY_NOT_SUPPORTED,
            failure.code,
            "currency is checked before the account, per leg, in leg order",
        )
    }

    @Test
    fun `the account of the first defective leg wins, not the lowest account id`() {
        // The engine locks accounts in ascending id order; this pass walks legs in leg order. The
        // two are different orders on purpose, and a caller reading a defect must be told about
        // the leg it wrote, not about whichever account happens to sort first.
        val first = UUID(0, 2)
        val second = UUID(0, 1)
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(
                        debit("100.00").copy(accountId = first),
                        credit("100.00").copy(accountId = second),
                    ),
                    FUNCTIONAL,
                ) { if (it == first) null else postable(second) }
            }

        assertEquals(
            "A referenced general-ledger account does not exist.",
            failure.safeDetail,
            "the first leg is judged first even though its account sorts last",
        )
    }

    @Test
    fun `a zero amount is refused by the money policy before the currency is looked at`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingLegsPolicy.settle(
                    listOf(debit("0.00", currency = "USD"), credit("100.00")),
                    FUNCTIONAL,
                    accounts(),
                )
            }

        assertEquals(
            MoneyPolicy.AMOUNT_NOT_POSITIVE,
            failure.code,
            "the amount is settled first, so a non-positive amount pre-empts the currency check",
        )
    }

    private fun debit(
        amount: String,
        currency: String = FUNCTIONAL,
    ) = PostingLeg(cash, PostingSide.DEBIT, MonetaryAmount(BigDecimal(amount), currency))

    private fun credit(
        amount: String,
        currency: String = FUNCTIONAL,
    ) = PostingLeg(liability, PostingSide.CREDIT, MonetaryAmount(BigDecimal(amount), currency))

    /** Every account resolves, and every one is postable: the lookup that gets out of the way. */
    private fun accounts(): (UUID) -> GlAccount = { postable(it) }

    private fun postable(id: UUID) =
        GlAccount(
            id = id,
            organisationId = ORGANISATION,
            code = AccountCode.of("1010"),
            name = "Account 1010",
            accountClass = AccountClass.ASSET,
            usage = AccountUsage.POSTABLE,
            status = GlAccountStatus.ACTIVE,
        )

    private companion object {
        const val FUNCTIONAL = "KES"
        val ORGANISATION: UUID = uuidV7()
    }
}
