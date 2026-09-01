package com.finaxis.platform.accounting.domain

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The value objects, and the one derivation they exist to protect. */
class GlAccountModelTests {
    @Test
    fun `every account class implies the side accounting puts its balance on`() {
        // Asserted against the accounting convention rather than against the enum, so the pairing
        // cannot be "verified" by reading it back out of the thing under test. gl_account
        // .normal_balance is a generated column over the same pairing.
        assertEquals(NormalBalance.DEBIT, AccountClass.ASSET.impliedNormalBalance)
        assertEquals(NormalBalance.DEBIT, AccountClass.EXPENSE.impliedNormalBalance)
        assertEquals(NormalBalance.CREDIT, AccountClass.LIABILITY.impliedNormalBalance)
        assertEquals(NormalBalance.CREDIT, AccountClass.EQUITY.impliedNormalBalance)
        assertEquals(NormalBalance.CREDIT, AccountClass.INCOME.impliedNormalBalance)
    }

    @Test
    fun `a contra account carries the opposite side to its class`() {
        // The rule that used to be stated as an exemption and is now a derivation. Written out for
        // all ten combinations because the failure it replaces was silent: the service defaulted an
        // omitted normal balance to the class-implied side whether or not the contra flag was set,
        // and the schema check of the day - `is_contra_account OR normal_balance = ...` - was a
        // disjunction, so setting the flag removed the requirement instead of inverting it. A
        // contra asset was created as a debit and nothing objected.
        val expected =
            mapOf(
                (AccountClass.ASSET to false) to NormalBalance.DEBIT,
                (AccountClass.ASSET to true) to NormalBalance.CREDIT,
                (AccountClass.EXPENSE to false) to NormalBalance.DEBIT,
                (AccountClass.EXPENSE to true) to NormalBalance.CREDIT,
                (AccountClass.LIABILITY to false) to NormalBalance.CREDIT,
                (AccountClass.LIABILITY to true) to NormalBalance.DEBIT,
                (AccountClass.EQUITY to false) to NormalBalance.CREDIT,
                (AccountClass.EQUITY to true) to NormalBalance.DEBIT,
                (AccountClass.INCOME to false) to NormalBalance.CREDIT,
                (AccountClass.INCOME to true) to NormalBalance.DEBIT,
            )

        expected.forEach { (input, side) ->
            val (accountClass, contra) = input
            assertEquals(
                side,
                account(GlAccountStatus.ACTIVE, AccountUsage.POSTABLE)
                    .copy(accountClass = accountClass, isContraAccount = contra)
                    .normalBalance,
                "$accountClass with isContraAccount=$contra",
            )
        }
        assertEquals(
            expected.size,
            AccountClass.entries.size * 2,
            "every class must appear with the flag both set and clear",
        )
    }

    @Test
    fun `the status set matches what the schema accepts, and excludes REJECTED`() {
        assertEquals(
            listOf("DRAFT", "PENDING_APPROVAL", "ACTIVE", "INACTIVE"),
            GlAccountStatus.entries.map { it.name },
            "chk_gl_account_status names exactly these four; adding one here without a forward " +
                "migration ships an enum the database will reject",
        )
    }

    @Test
    fun `an account code is trimmed, bounded and refuses separators it cannot carry`() {
        assertEquals("1010", AccountCode.of("  1010  ").value)
        assertFailsWith<IllegalArgumentException> { AccountCode("") }
        assertFailsWith<IllegalArgumentException> { AccountCode("   ") }
        assertFailsWith<IllegalArgumentException> {
            AccountCode(
                "a".repeat(AccountCode.MAX_LENGTH + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> { AccountCode("10 10") }
        assertFailsWith<IllegalArgumentException> { AccountCode("10/10") }
    }

    @Test
    fun `postability needs both ACTIVE and POSTABLE`() {
        // Both halves, because either one alone is a plausible reading of "postable" and each
        // would let a posting land somewhere it must not: a draft account, or a header total.
        assertTrue(account(GlAccountStatus.ACTIVE, AccountUsage.POSTABLE).isPostable)
        assertFalse(account(GlAccountStatus.DRAFT, AccountUsage.POSTABLE).isPostable)
        assertFalse(account(GlAccountStatus.PENDING_APPROVAL, AccountUsage.POSTABLE).isPostable)
        assertFalse(account(GlAccountStatus.INACTIVE, AccountUsage.POSTABLE).isPostable)
        assertFalse(account(GlAccountStatus.ACTIVE, AccountUsage.HEADER).isPostable)
    }

    private fun account(
        status: GlAccountStatus,
        usage: AccountUsage,
    ) = GlAccount(
        id = UUID.randomUUID(),
        organisationId = UUID.randomUUID(),
        code = AccountCode("1010"),
        name = "Cash",
        accountClass = AccountClass.ASSET,
        usage = usage,
        status = status,
    )
}
