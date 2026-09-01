package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.application.InvalidOperationException
import java.util.UUID

/**
 * The chart-of-accounts structural rules, as pure functions.
 *
 * No Spring, no clock, no persistence: every rule below is a statement about values the caller
 * already holds, so each one is unit-testable without a database and reusable by issue #38's FSM
 * guards without dragging an adapter behind it.
 *
 * **What the database already enforces, and this does not repeat as a guess:** a parent in another
 * tenant, a parent that is not a `HEADER` account, demoting a parent that still has children, and
 * an account that is its own parent are all rejected by `V6` — the first three by
 * `fk_gl_account_parent_same_organisation`, the last by `chk_gl_account_not_own_parent`. This class
 * rejects them *earlier*, with a stable error code instead of a constraint-violation stack trace,
 * and adds the two rules a row-local constraint cannot express: a cycle longer than one hop, and a
 * depth bound.
 */
object ChartHierarchyPolicy {
    /**
     * The deepest chart this design supports: class, group, sub-group, control, account,
     * sub-account.
     *
     * A bound is needed at all because the hierarchy read is a recursive descent, and an unbounded
     * one over a corrupted parent chain does not terminate. Six is drawn from the target volume in
     * `docs/architecture/accounting-foundation.md`: 500 to 2,000 accounts per tenant is a wide
     * chart, not a deep one.
     */
    const val MAX_DEPTH = 6

    /** No parent may be assigned that would make an account its own ancestor. */
    const val CYCLE = "accounting.gl_account_hierarchy_cycle"

    /** The proposed parent is not a header account, or belongs to another tenant. */
    const val INVALID_PARENT = "accounting.gl_account_invalid_parent"

    /** The proposed placement would exceed [MAX_DEPTH]. */
    const val TOO_DEEP = "accounting.gl_account_hierarchy_too_deep"

    /** A child's class must match its parent's, so a subtree totals a single class. */
    const val CLASS_MISMATCH = "accounting.gl_account_class_mismatch"

    /** An account's code, class or usage changed after it acquired child accounts. */
    const val IMMUTABLE = "accounting.gl_account_structurally_immutable"

    /**
     * Validates a proposed parent for [child].
     *
     * [ancestorsOfParent] must be the parent's own ancestor chain, nearest first, as
     * `GlAccountStore.ancestorsOf` returns it. Passing an incomplete chain weakens the cycle check
     * to the one-hop case the database already covers, so the caller reads it in the same
     * transaction as the write.
     *
     * [subtreeHeightOfChild] is how many levels the child carries with it - 1 for a leaf, more
     * when a whole subtree is being moved. It defaults to 1 so a create reads naturally, and an
     * update must supply the real height or the bound only measures half the move.
     */
    fun requireAssignableParent(
        child: GlAccount,
        parent: GlAccount,
        ancestorsOfParent: List<GlAccount>,
        subtreeHeightOfChild: Int = 1,
    ) {
        // Evaluated in order and thrown once, so the caller always sees the *first* rule the
        // placement breaks rather than whichever branch happened to be written first.
        val failure =
            when {
                parent.id == child.id -> {
                    CYCLE to "An account cannot be its own parent."
                }

                parent.organisationId != child.organisationId -> {
                    INVALID_PARENT to "A parent account must belong to the same organisation."
                }

                parent.usage != AccountUsage.HEADER -> {
                    INVALID_PARENT to "Only a header account can be a parent."
                }

                parent.accountClass != child.accountClass -> {
                    CLASS_MISMATCH to
                        "A child account must have the same account class as its parent."
                }

                ancestorsOfParent.any { it.id == child.id } -> {
                    CYCLE to "The proposed parent is already a descendant of this account."
                }

                // Both sides of the move: the parent's own chain, plus the parent, plus the
                // height of the subtree being moved. Measuring only the parent's side is what an
                // earlier revision did, and it let a re-parenting push descendants past the bound
                // - which is not an error but a *silently truncated ancestry*, because the
                // recursive descent that reads a chain is itself capped at MAX_DEPTH. A rollup
                // over the moved subtree would then never reach the root and quietly report the
                // wrong total.
                ancestorsOfParent.size + 1 + subtreeHeightOfChild > MAX_DEPTH -> {
                    TOO_DEEP to "A chart of accounts is limited to $MAX_DEPTH levels."
                }

                else -> {
                    return
                }
            }
        throw invalid(failure.first, failure.second)
    }

    /**
     * Rejects a change to an account's **identity** once it has structure beneath it.
     *
     * Identity here is the `code`, `accountClass` and `usage` triple, and the reason those three
     * and not the parent is worth stating, because an earlier revision included the parent and the
     * consequence was subtle. A cycle can only exist if the account has descendants, so blocking
     * every re-parenting of an account with children made [requireAssignableParent]'s cycle branch
     * **unreachable** — a guard that could never fire, which is the failure this codebase has been
     * burned by before. It also forbade something legitimate: reorganising a chart moves subtrees,
     * and a posted journal line references its account, not the account's position, so a move
     * reinterprets no history. Re-parenting is therefore governed by [requireAssignableParent]'s
     * cycle, depth, class and header rules instead.
     *
     * A code, class or usage change does reinterpret history: every posted line was recorded
     * against that code, under that class, on an account that was postable.
     *
     * [hasChildren] is the half of *"has been used"* that is knowable before issue #40 creates
     * `journal_line`. It is not the whole rule and this KDoc says so rather than implying an
     * account with no children is safe to re-key: once a journal line references one, its code and
     * class are frozen, and #40 is where that predicate becomes answerable. Enforcing the knowable
     * half now is worth more than enforcing nothing, and less than the rule will eventually be.
     */
    fun requireStructurallyMutable(
        current: GlAccount,
        proposed: GlAccount,
        hasChildren: Boolean,
    ) {
        val identityChange =
            current.code != proposed.code ||
                current.accountClass != proposed.accountClass ||
                current.usage != proposed.usage

        if (identityChange && hasChildren) {
            throw invalid(
                IMMUTABLE,
                "An account that has child accounts cannot change its code, class or usage.",
            )
        }
    }

    private fun invalid(
        code: String,
        detail: String,
    ) = InvalidOperationException(code = code, safeDetail = detail)
}
