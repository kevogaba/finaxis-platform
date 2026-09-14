package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.application.GlAccountPage
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.GlAccountWriteStore
import com.finaxis.platform.accounting.application.NewGlAccount
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.ChartHierarchyPolicy
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_LEG
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `gl_account` adapter behind [GlAccountStore].
 *
 * Two properties are load-bearing and neither is incidental:
 *
 * **Every statement is tenant-filtered**, and the hierarchy walks carry the predicate inside the
 * recursive term as well as the anchor. Filtering only the anchor would let a corrupted
 * `parent_account_id` walk into another tenant's subtree — which the composite foreign key makes
 * unrepresentable today, so the predicate is defence in depth rather than the only guard.
 *
 * **Both walks are one statement and bounded.** A recursive CTE with an explicit depth column and
 * a `depth < MAX_DEPTH` guard, rather than a loop of parent lookups: the loop is the N+1 issue #37
 * forbids, and the depth guard is what makes a cycle terminate instead of spinning. At 500 to 2,000
 * accounts per tenant a materialised path or closure table would add write amplification and a
 * rebuild obligation to buy nothing — see `docs/database/accounting-erd.md`.
 */
@Component
class JooqGlAccountStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : GlAccountStore,
    GlAccountWriteStore {
    override fun findById(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount? =
        selectAccount()
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT.ID.eq(accountId))
            .fetchOne()
            ?.let(::toAccount)

    /**
     * `FOR UPDATE`, with the tenant predicate in the same statement as the lock.
     *
     * Locking on the primary key alone would let a caller that supplies another tenant's account id
     * lock, read and then transition that row, and the mismatch would be invisible to any check
     * performed against the caller's own organisation id.
     */
    override fun lockForStateChange(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount? {
        requireActiveTransaction("Locking a general-ledger account for a state change")
        return selectAccount()
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT.ID.eq(accountId))
            .forUpdate()
            .fetchOne()
            ?.let(::toAccount)
    }

    /**
     * `FOR SHARE`, with the tenant predicate in the same statement as the lock.
     *
     * Many postings hold this lock on the same account concurrently - it does not conflict with
     * itself - but it does conflict with [lockForStateChange]'s exclusive lock, so a lifecycle
     * transition on the account waits for every in-flight posting against it, and a posting waits
     * for a transition already in progress.
     */
    override fun lockForPosting(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount? {
        requireActiveTransaction("Locking a general-ledger account for posting")
        return selectAccount()
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT.ID.eq(accountId))
            .forShare()
            .fetchOne()
            ?.let(::toAccount)
    }

    /**
     * An `EXISTS` join from `posting_rule_leg` to `posting_rule_version`, served by
     * `idx_posting_rule_leg_account` on the leg side and the version's primary key on the other -
     * bounded and index-backed, never a count.
     *
     * Matches every *approved* status - `ACTIVE`, `SUPERSEDED` and `RETIRED` - not only `ACTIVE`:
     * [PostingRuleVersion.governs] resolves a posting against whichever approved version's
     * effective window covers the posting date, so a backdated posting can still be routed through
     * a `SUPERSEDED` or `RETIRED` version. Only `DRAFT` and `PENDING_APPROVAL` are truly
     * unreachable by the resolver.
     */
    override fun hasActivePostingRuleLegs(
        organisationId: UUID,
        accountId: UUID,
    ): Boolean =
        dsl.fetchExists(
            DSL
                .selectOne()
                .from(POSTING_RULE_LEG)
                .join(POSTING_RULE_VERSION)
                .on(POSTING_RULE_VERSION.ID.eq(POSTING_RULE_LEG.POSTING_RULE_VERSION_ID))
                .and(POSTING_RULE_VERSION.ORGANISATION_ID.eq(POSTING_RULE_LEG.ORGANISATION_ID))
                .where(POSTING_RULE_LEG.ORGANISATION_ID.eq(organisationId))
                .and(POSTING_RULE_LEG.GL_ACCOUNT_ID.eq(accountId))
                .and(POSTING_RULE_VERSION.STATUS.`in`(APPROVED_STATUS_NAMES)),
        )

    override fun findByCode(
        organisationId: UUID,
        code: AccountCode,
    ): GlAccount? =
        selectAccount()
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT.ACCOUNT_CODE.eq(code.value))
            .fetchOne()
            ?.let(::toAccount)

    /** Served by `uq_gl_account_control_kind`, which also guarantees the row is unique. */
    override fun findControlAccountFor(
        organisationId: UUID,
        kind: ControlSubledgerKind,
    ): GlAccount? =
        selectAccount()
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND.eq(kind.name))
            .and(GL_ACCOUNT.IS_CONTROL_ACCOUNT.isTrue)
            .fetchOne()
            ?.let(::toAccount)

    override fun ancestorsOf(
        organisationId: UUID,
        accountId: UUID,
    ): List<GlAccount> = ancestorWalk(organisationId, accountId).drop(1)

    /**
     * The depth of the deepest descendant, computed by the database.
     *
     * `MAX(depth)` over the downward recursion, so the result is one integer whatever the size of
     * the subtree. The `WHERE id = ?` anchor always matches at least the account itself, so
     * `coalesce` only guards the case where the account does not exist for this tenant - which the
     * caller has already rejected - and returning 1 there is the safe answer anyway.
     */
    override fun subtreeHeightOf(
        organisationId: UUID,
        accountId: UUID,
    ): Int =
        dsl
            .resultQuery(
                """
                WITH RECURSIVE walk (id, depth) AS (
                    SELECT id, 1
                    FROM gl_account
                    WHERE organisation_id = ? AND id = ?
                    UNION ALL
                    SELECT a.id, w.depth + 1
                    FROM gl_account a
                    JOIN walk w ON a.parent_account_id = w.id
                    WHERE a.organisation_id = ? AND w.depth < ?
                )
                SELECT coalesce(MAX(depth), 1) FROM walk
                """.trimIndent(),
                organisationId,
                accountId,
                organisationId,
                ChartHierarchyPolicy.MAX_DEPTH,
            ).fetchOne(0, Int::class.java) ?: 1

    override fun hasChildren(
        organisationId: UUID,
        accountId: UUID,
    ): Boolean =
        dsl.fetchExists(
            DSL
                .selectOne()
                .from(GL_ACCOUNT)
                .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
                .and(GL_ACCOUNT.PARENT_ACCOUNT_ID.eq(accountId)),
        )

    override fun hasJournalLines(
        organisationId: UUID,
        accountId: UUID,
    ): Boolean =
        dsl.fetchExists(
            DSL
                .selectOne()
                .from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
                .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId)),
        )

    override fun list(
        organisationId: UUID,
        afterCode: AccountCode?,
        pageSize: Int,
    ): GlAccountPage {
        // The bound belongs to the application layer, which refuses an over-large request with a
        // named error. An earlier revision enforced its own ceiling of 200 here with `require`,
        // which both duplicated `finaxis.pagination.max-page-size` (100) and turned a client
        // mistake into an IllegalArgumentException escaping a persistence adapter as a 500.
        val rows =
            selectAccount()
                .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
                .and(
                    afterCode?.let { GL_ACCOUNT.ACCOUNT_CODE.gt(it.value) } ?: DSL.noCondition(),
                ).orderBy(GL_ACCOUNT.ACCOUNT_CODE.asc())
                .limit(pageSize)
                .fetch()
                .map(::toAccount)

        // The cursor is null on a short page, so a caller looping until null terminates rather
        // than asking for one more page that is always empty.
        return GlAccountPage(
            accounts = rows,
            nextCursor = if (rows.size < pageSize) null else rows.last().code,
        )
    }

    override fun create(account: NewGlAccount): GlAccount {
        val now = OffsetDateTime.now(clock)
        val id =
            dsl
                .insertInto(GL_ACCOUNT)
                .set(GL_ACCOUNT.ORGANISATION_ID, account.organisationId)
                .set(GL_ACCOUNT.ACCOUNT_CODE, account.code.value)
                .set(GL_ACCOUNT.ACCOUNT_NAME, account.name)
                .set(GL_ACCOUNT.DESCRIPTION, account.description)
                .set(GL_ACCOUNT.ACCOUNT_CLASS, account.accountClass.name)
                .set(GL_ACCOUNT.ACCOUNT_USAGE, account.usage.name)
                .set(GL_ACCOUNT.IS_CONTRA_ACCOUNT, account.isContraAccount)
                .set(GL_ACCOUNT.MANUAL_POSTING_ALLOWED, account.manualPostingAllowed)
                .set(GL_ACCOUNT.IS_CONTROL_ACCOUNT, account.isControlAccount)
                .set(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND, account.controlSubledgerKind?.name)
                .set(GL_ACCOUNT.PARENT_ACCOUNT_ID, account.parentAccountId)
                .set(GL_ACCOUNT.STATUS, account.status.name)
                .set(GL_ACCOUNT.CREATED_AT, now)
                .set(GL_ACCOUNT.CREATED_BY, account.actorId)
                .set(GL_ACCOUNT.UPDATED_AT, now)
                .set(GL_ACCOUNT.UPDATED_BY, account.actorId)
                .returning(GL_ACCOUNT.ID)
                .fetchOne()
                ?.id
                ?: error("gl_account insert returned no identifier")

        // parent_account_usage and normal_balance are both GENERATED ALWAYS, so both are
        // deliberately absent above: writing either raises SQLSTATE 428C9, and reading the row back
        // is how the caller sees what the database derived.
        return checkNotNull(findById(account.organisationId, id))
    }

    /**
     * Updates the editable fields, matching on the row version the caller read.
     *
     * Two things this deliberately does **not** do, and both are load-bearing.
     *
     * It does not write `status`. Moving between statuses is the lifecycle FSM's, and an edit path
     * that also wrote the status would be a way around the approval it requires — an edit that
     * began before an approval committed would write the pre-approval status back, un-approving
     * the account while `gl_account_transition_log` still recorded the approval.
     *
     * And it matches on `row_version` rather than merely incrementing it, so a stale write loses
     * instead of silently overwriting a concurrent one. An earlier revision incremented the column
     * without ever comparing it, which is a counter, not a lock.
     */
    override fun update(
        account: GlAccount,
        actorId: UUID,
    ): Boolean =
        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.ACCOUNT_CODE, account.code.value)
            .set(GL_ACCOUNT.ACCOUNT_NAME, account.name)
            .set(GL_ACCOUNT.DESCRIPTION, account.description)
            .set(GL_ACCOUNT.ACCOUNT_CLASS, account.accountClass.name)
            .set(GL_ACCOUNT.ACCOUNT_USAGE, account.usage.name)
            .set(GL_ACCOUNT.IS_CONTRA_ACCOUNT, account.isContraAccount)
            .set(GL_ACCOUNT.MANUAL_POSTING_ALLOWED, account.manualPostingAllowed)
            .set(GL_ACCOUNT.IS_CONTROL_ACCOUNT, account.isControlAccount)
            .set(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND, account.controlSubledgerKind?.name)
            .set(GL_ACCOUNT.PARENT_ACCOUNT_ID, account.parentAccountId)
            .set(GL_ACCOUNT.UPDATED_AT, OffsetDateTime.now(clock))
            .set(GL_ACCOUNT.UPDATED_BY, actorId)
            .set(GL_ACCOUNT.ROW_VERSION, GL_ACCOUNT.ROW_VERSION.plus(1))
            .where(GL_ACCOUNT.ID.eq(account.id))
            .and(GL_ACCOUNT.ORGANISATION_ID.eq(account.organisationId))
            .and(GL_ACCOUNT.ROW_VERSION.eq(account.rowVersion))
            .execute() == 1

    override fun updateStatus(
        organisationId: UUID,
        accountId: UUID,
        from: GlAccountStatus,
        to: GlAccountStatus,
        reason: String?,
        actorId: UUID,
    ): Boolean =
        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.STATUS, to.name)
            .set(GL_ACCOUNT.STATUS_REASON, reason)
            .set(GL_ACCOUNT.UPDATED_AT, OffsetDateTime.now(clock))
            .set(GL_ACCOUNT.UPDATED_BY, actorId)
            .set(GL_ACCOUNT.ROW_VERSION, GL_ACCOUNT.ROW_VERSION.plus(1))
            .where(GL_ACCOUNT.ID.eq(accountId))
            .and(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            // The compare-and-set: the row must still be in the status the caller observed.
            .and(GL_ACCOUNT.STATUS.eq(from.name))
            .execute() == 1

    private fun selectAccount() =
        dsl
            .select(
                GL_ACCOUNT.ID,
                GL_ACCOUNT.ORGANISATION_ID,
                GL_ACCOUNT.ACCOUNT_CODE,
                GL_ACCOUNT.ACCOUNT_NAME,
                GL_ACCOUNT.DESCRIPTION,
                GL_ACCOUNT.ACCOUNT_CLASS,
                GL_ACCOUNT.ACCOUNT_USAGE,
                GL_ACCOUNT.NORMAL_BALANCE,
                GL_ACCOUNT.IS_CONTRA_ACCOUNT,
                GL_ACCOUNT.MANUAL_POSTING_ALLOWED,
                GL_ACCOUNT.PARENT_ACCOUNT_ID,
                GL_ACCOUNT.STATUS,
                GL_ACCOUNT.STATUS_REASON,
                GL_ACCOUNT.ROW_VERSION,
                GL_ACCOUNT.IS_CONTROL_ACCOUNT,
                GL_ACCOUNT.CONTROL_SUBLEDGER_KIND,
            ).from(GL_ACCOUNT)

    /**
     * One recursive CTE, walking up the parent chain.
     *
     * `depth` is carried in the CTE and guarded, so the walk terminates on a cycle instead of
     * recursing forever — the reason a depth bound exists at all. The tenant predicate appears in
     * both the anchor and the recursive term, so a parent id belonging to another tenant ends the
     * recursion rather than crossing into it.
     *
     * The downward direction is [subtreeHeightOf], which aggregates in SQL instead of returning
     * rows; an earlier revision parameterised this one helper over both directions and hydrated a
     * full account per descendant to compute a single depth.
     */
    private fun ancestorWalk(
        organisationId: UUID,
        accountId: UUID,
    ): List<GlAccount> =
        dsl
            .resultQuery(
                """
                WITH RECURSIVE walk (id, parent_account_id, depth) AS (
                    SELECT id, parent_account_id, 1
                    FROM gl_account
                    WHERE organisation_id = ? AND id = ?
                    UNION ALL
                    SELECT a.id, a.parent_account_id, w.depth + 1
                    FROM gl_account a
                    JOIN walk w ON a.id = w.parent_account_id
                    WHERE a.organisation_id = ? AND w.depth < ?
                )
                SELECT g.id, g.organisation_id, g.account_code, g.account_name, g.description,
                       g.account_class, g.account_usage, g.is_contra_account,
                       g.manual_posting_allowed, g.parent_account_id, g.status,
                       g.status_reason, g.row_version, g.is_control_account,
                       g.control_subledger_kind
                FROM walk w
                JOIN gl_account g ON g.id = w.id
                WHERE g.organisation_id = ?
                ORDER BY w.depth, g.account_code
                """.trimIndent(),
                organisationId,
                accountId,
                organisationId,
                ChartHierarchyPolicy.MAX_DEPTH,
                organisationId,
            ).fetch()
            .map(::toAccount)

    private fun toAccount(row: Record) =
        GlAccount(
            id = row.get(GL_ACCOUNT.ID)!!,
            organisationId = row.get(GL_ACCOUNT.ORGANISATION_ID)!!,
            code = AccountCode(row.get(GL_ACCOUNT.ACCOUNT_CODE)!!),
            name = row.get(GL_ACCOUNT.ACCOUNT_NAME)!!,
            description = row.get(GL_ACCOUNT.DESCRIPTION),
            accountClass = AccountClass.valueOf(row.get(GL_ACCOUNT.ACCOUNT_CLASS)!!),
            usage = AccountUsage.valueOf(row.get(GL_ACCOUNT.ACCOUNT_USAGE)!!),
            isContraAccount = row.get(GL_ACCOUNT.IS_CONTRA_ACCOUNT)!!,
            manualPostingAllowed = row.get(GL_ACCOUNT.MANUAL_POSTING_ALLOWED)!!,
            parentAccountId = row.get(GL_ACCOUNT.PARENT_ACCOUNT_ID),
            status = GlAccountStatus.valueOf(row.get(GL_ACCOUNT.STATUS)!!),
            statusReason = row.get(GL_ACCOUNT.STATUS_REASON),
            rowVersion = row.get(GL_ACCOUNT.ROW_VERSION)!!,
            isControlAccount = row.get(GL_ACCOUNT.IS_CONTROL_ACCOUNT)!!,
            controlSubledgerKind =
                row.get(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND)?.let(ControlSubledgerKind::valueOf),
        )

    private companion object {
        val APPROVED_STATUS_NAMES: List<String> =
            PostingRuleVersionStatus.entries.filter { it.isApproved }.map { it.name }
    }
}
