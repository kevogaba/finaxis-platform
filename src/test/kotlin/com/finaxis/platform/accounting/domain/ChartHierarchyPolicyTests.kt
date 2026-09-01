package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.application.InvalidOperationException
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The structural rules, as pure functions over values.
 *
 * No database and no Spring: every rule in [ChartHierarchyPolicy] is a statement about arguments
 * the caller already holds, which is what makes the two cases a row-local constraint cannot
 * express — a cycle longer than one hop, and a depth bound — testable at all.
 */
class ChartHierarchyPolicyTests {
    @Test
    fun `a header parent of the same class and tenant is assignable`() {
        // The positive control. Every other assertion here is a rejection, and all of them hold
        // for a policy that rejects everything.
        ChartHierarchyPolicy.requireAssignableParent(child(), header(), emptyList())
    }

    @Test
    fun `an account cannot be its own parent`() {
        val account = header()

        val failure =
            assertFailsWith<InvalidOperationException> {
                ChartHierarchyPolicy.requireAssignableParent(account, account, emptyList())
            }

        assertEquals(ChartHierarchyPolicy.CYCLE, failure.code)
    }

    @Test
    fun `a cycle longer than one hop is rejected`() {
        // The case chk_gl_account_not_own_parent cannot see. Re-parenting A under its own
        // grandchild is legal to every row-local constraint and would detach the subtree from the
        // chart entirely, so the walk that renders a trial balance would never reach it.
        val root = header(code = "1000")
        val grandchild = header(code = "1200")

        val failure =
            assertFailsWith<InvalidOperationException> {
                ChartHierarchyPolicy.requireAssignableParent(
                    root,
                    grandchild,
                    listOf(header(code = "1100"), root),
                )
            }

        assertEquals(ChartHierarchyPolicy.CYCLE, failure.code)
    }

    @Test
    fun `a postable account cannot be a parent`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                ChartHierarchyPolicy.requireAssignableParent(
                    child(),
                    child(code = "1010"),
                    emptyList(),
                )
            }

        assertEquals(ChartHierarchyPolicy.INVALID_PARENT, failure.code)
    }

    @Test
    fun `a parent in another organisation is rejected`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                ChartHierarchyPolicy.requireAssignableParent(
                    child(),
                    header().copy(organisationId = OTHER_ORGANISATION),
                    emptyList(),
                )
            }

        assertEquals(ChartHierarchyPolicy.INVALID_PARENT, failure.code)
    }

    @Test
    fun `a child must share its parent's account class`() {
        // Without this a subtree can mix classes, and every rollup over it sums assets into
        // liabilities while each individual row still satisfies its own CHECK.
        val failure =
            assertFailsWith<InvalidOperationException> {
                ChartHierarchyPolicy.requireAssignableParent(
                    child().copy(accountClass = AccountClass.LIABILITY),
                    header(),
                    emptyList(),
                )
            }

        assertEquals(ChartHierarchyPolicy.CLASS_MISMATCH, failure.code)
    }

    @Test
    fun `the chart is bounded at MAX_DEPTH levels`() {
        val deepestLegalChain = List(ChartHierarchyPolicy.MAX_DEPTH - 2) { header(code = "h$it") }
        ChartHierarchyPolicy.requireAssignableParent(child(), header(), deepestLegalChain)

        val failure =
            assertFailsWith<InvalidOperationException> {
                ChartHierarchyPolicy.requireAssignableParent(
                    child(),
                    header(),
                    deepestLegalChain + header(code = "one-too-many"),
                )
            }

        assertEquals(ChartHierarchyPolicy.TOO_DEEP, failure.code)
    }

    @Test
    fun `a structural change is refused once the account has children`() {
        val current = header()
        val renamed = current.copy(code = AccountCode("1001"))

        ChartHierarchyPolicy.requireStructurallyMutable(current, renamed, hasChildren = false)

        val failure =
            assertFailsWith<InvalidOperationException> {
                ChartHierarchyPolicy.requireStructurallyMutable(
                    current,
                    renamed,
                    hasChildren = true,
                )
            }

        assertEquals(ChartHierarchyPolicy.IMMUTABLE, failure.code)
    }

    @Test
    fun `a non-identity change is allowed even with children`() {
        // Guards the rule above against being written as "an account with children is frozen",
        // which would make renaming a header account for a typo impossible.
        val current = header()

        ChartHierarchyPolicy.requireStructurallyMutable(
            current,
            current.copy(name = "Assets (renamed)", description = "clarified"),
            hasChildren = true,
        )
    }

    @Test
    fun `re-parenting is not an identity change, so the cycle rule can actually fire`() {
        // The reason the parent is deliberately outside the immutability rule. A cycle requires
        // descendants, so if re-parenting an account with children were blocked here, the cycle
        // branch of requireAssignableParent could never be reached by any caller - a guard that
        // cannot fire. Moving a subtree is also legitimate: a posted line references its account,
        // not the account's position.
        val current = header()

        ChartHierarchyPolicy.requireStructurallyMutable(
            current,
            current.copy(parentAccountId = UUID.randomUUID()),
            hasChildren = true,
        )
    }

    private fun header(code: String = "1000") =
        GlAccount(
            id = UUID.randomUUID(),
            organisationId = ORGANISATION,
            code = AccountCode(code),
            name = "Assets",
            accountClass = AccountClass.ASSET,
            usage = AccountUsage.HEADER,
            status = GlAccountStatus.ACTIVE,
        )

    private fun child(code: String = "1010") =
        GlAccount(
            id = UUID.randomUUID(),
            organisationId = ORGANISATION,
            code = AccountCode(code),
            name = "Cash",
            accountClass = AccountClass.ASSET,
            usage = AccountUsage.POSTABLE,
            status = GlAccountStatus.ACTIVE,
        )

    private companion object {
        val ORGANISATION: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OTHER_ORGANISATION: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
