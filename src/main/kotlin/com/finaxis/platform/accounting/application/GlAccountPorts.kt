package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.ChartHierarchyPolicy
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import java.util.UUID

/**
 * A bounded page of chart-of-accounts rows.
 *
 * Keyset rather than offset, because the chart is read by code order and an offset page shifts
 * under a concurrent insert. [nextCursor] is the last row's code; passing it back resumes after it.
 */
data class GlAccountPage(
    val accounts: List<GlAccount>,
    val nextCursor: AccountCode?,
)

/**
 * Read port for the chart of accounts.
 *
 * Returns domain types, never generated jOOQ records: nothing outside
 * `accounting.adapter.outbound.persistence` may depend on a generated accounting table, and
 * `AccountingBoundaryRuleTests` enforces it. Every method is organisation-scoped by parameter
 * rather than by ambient context, so a caller cannot forget the tenant.
 */
interface GlAccountStore {
    /** Finds one account by id within a tenant, or null. */
    fun findById(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount?

    /** Finds one account by its tenant-unique code, or null. */
    fun findByCode(
        organisationId: UUID,
        code: AccountCode,
    ): GlAccount?

    /**
     * Returns [accountId]'s ancestors, nearest parent first, in **one** statement.
     *
     * A recursive CTE rather than a loop of parent lookups: the loop is the N+1 issue #37 forbids,
     * and at a depth bound of [ChartHierarchyPolicy.MAX_DEPTH] the recursion is a handful of index
     * lookups. The descent is bounded in SQL as well as by the policy, so a corrupted parent chain
     * terminates rather than spinning.
     */
    fun ancestorsOf(
        organisationId: UUID,
        accountId: UUID,
    ): List<GlAccount>

    /**
     * Returns how many levels [accountId] carries with it, itself counted as one.
     *
     * A leaf is 1, a parent of leaves is 2. Bounded by [ChartHierarchyPolicy.MAX_DEPTH], so a
     * corrupted parent chain terminates rather than spinning.
     *
     * A **height**, not the subtree. An earlier revision returned every descendant as
     * `subtreeOf(...): List<GlAccount>` and the one caller reduced it to this single number in
     * Kotlin. That made an unbounded read out of a bounded question - a header account near the
     * root of a large chart returns thousands of rows, which `INV-15` forbids for exactly this
     * reason - and it hydrated a full [GlAccount] per row to look at nothing but its parent id.
     * PostgreSQL computes the depth in the same recursive statement either way.
     */
    fun subtreeHeightOf(
        organisationId: UUID,
        accountId: UUID,
    ): Int

    /** True when the account has at least one child. */
    fun hasChildren(
        organisationId: UUID,
        accountId: UUID,
    ): Boolean

    /**
     * Lists a tenant's chart in code order, from [afterCode] exclusive, at most [pageSize] rows.
     *
     * There is no unpaginated variant on purpose: `INV-15` requires every accounting query to be
     * bounded, and an "all accounts" method is how that gets quietly broken.
     */
    fun list(
        organisationId: UUID,
        afterCode: AccountCode?,
        pageSize: Int,
    ): GlAccountPage
}

/**
 * Write port for the chart of accounts.
 *
 * Separate from [GlAccountStore] because the two are held to different rules: a read is bounded and
 * tenant-filtered, a write is optimistic and cannot touch a status except through [updateStatus].
 */
interface GlAccountWriteStore {
    /** Inserts a new account and returns it with its generated id. */
    fun create(account: NewGlAccount): GlAccount

    /**
     * Replaces the **editable** fields of an existing account; false when the row version moved.
     *
     * Deliberately cannot write `status`: moving between statuses is a transition, and an edit path
     * that also wrote the status would be a way around the approval that transition requires. Use
     * [updateStatus] for that.
     */
    fun update(
        account: GlAccount,
        actorId: UUID,
    ): Boolean

    /**
     * Moves an account's status, and only its status, as a compare-and-set on [from].
     *
     * Conditioning the write on the status the caller observed makes a concurrent double transition
     * fail instead of both succeeding — two approvals of the same account, or an approval racing a
     * deactivation. It needs no row lock precisely because the condition *is* the check, and it
     * returns false rather than throwing so the caller can name the conflict in its own vocabulary.
     */
    fun updateStatus(
        organisationId: UUID,
        accountId: UUID,
        from: GlAccountStatus,
        to: GlAccountStatus,
        reason: String?,
        actorId: UUID,
    ): Boolean
}

/**
 * A chart-of-accounts row before the database has given it an identity.
 *
 * Deliberately not a [GlAccount] with a null id: the identifier convention is that the database
 * default generates it and the application reads it back, so a type that cannot carry an id is
 * what keeps a caller from supplying one.
 */
data class NewGlAccount(
    val organisationId: UUID,
    val code: AccountCode,
    val name: String,
    val accountClass: AccountClass,
    val usage: AccountUsage,
    val status: GlAccountStatus,
    val actorId: UUID,
    val parentAccountId: UUID? = null,
    val description: String? = null,
    val isContraAccount: Boolean = false,
    val manualPostingAllowed: Boolean = false,
)
