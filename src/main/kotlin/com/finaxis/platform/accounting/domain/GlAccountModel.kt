package com.finaxis.platform.accounting.domain

import java.util.UUID

/**
 * The five account classes, and the side each one's balance normally sits on.
 *
 * The mapping is accounting's, not this platform's: an asset or an expense increases on the debit
 * side, everything else on the credit side. `gl_account.normal_balance` is a generated column over
 * exactly this pairing and the contra flag, so this enum and the column cannot drift.
 */
enum class AccountClass(
    val impliedNormalBalance: NormalBalance,
) {
    /** Resources the tenant controls. */
    ASSET(NormalBalance.DEBIT),

    /** Obligations the tenant owes. */
    LIABILITY(NormalBalance.CREDIT),

    /** Residual interest in the assets after liabilities. */
    EQUITY(NormalBalance.CREDIT),

    /** Inflows that increase equity other than contributions. */
    INCOME(NormalBalance.CREDIT),

    /** Outflows that decrease equity other than distributions. */
    EXPENSE(NormalBalance.DEBIT),
}

/** The side of a GL account that increases its balance. */
enum class NormalBalance {
    /** Increased by debits. */
    DEBIT,

    /** Increased by credits. */
    CREDIT,
}

/**
 * Whether an account groups other accounts or can receive journal lines.
 *
 * Only a `HEADER` account may be a parent, and `fk_gl_account_parent_same_organisation` enforces
 * that at the database over a generated column — so this distinction is a schema guarantee rather
 * than an application convention.
 */
enum class AccountUsage {
    /** Groups other accounts. Never receives a journal line and never takes a manual entry. */
    HEADER,

    /** Can receive journal lines. Never has children. */
    POSTABLE,
}

/**
 * The GL-account lifecycle.
 *
 * There is deliberately no `REJECTED`: `uq_gl_account_organisation_code` makes an account code
 * unique per tenant, so a terminal rejected row would hold its code forever and the second attempt
 * at the same account could not reuse it. Rejection returns the account to [DRAFT] with a reason.
 * Issue #38 declares the transitions between these states; this enum is only the state set, and it
 * matches `chk_gl_account_status` exactly.
 */
enum class GlAccountStatus {
    /** Being prepared. Editable, and where a rejected account returns to. */
    DRAFT,

    /** Submitted and awaiting a checker who is not the maker. */
    PENDING_APPROVAL,

    /** Approved and in use. The only status a posting may target. */
    ACTIVE,

    /** Withdrawn from use without being deleted. History that references it stays valid. */
    INACTIVE,
}

/**
 * A tenant-unique chart-of-accounts code.
 *
 * Validated as a value object rather than as a bare `String` because it is the identifier an
 * accountant recognises, it is immutable once the account has financial use, and a stray space or
 * an empty string would otherwise reach `uq_gl_account_organisation_code` and occupy a code nobody
 * can type. Comparison is exact: `1010` and `1010 ` are the same code only because the value is
 * trimmed on the way in.
 */
@JvmInline
value class AccountCode(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "An account code cannot be blank." }
        require(value.length <= MAX_LENGTH) {
            "An account code cannot exceed $MAX_LENGTH characters."
        }
        require(PATTERN.matches(value)) {
            "An account code may contain letters, digits, dots, dashes and underscores only."
        }
    }

    override fun toString(): String = value

    /** Bounds and the trimming constructor an [AccountCode] is built through. */
    companion object {
        /** Long enough for a deep numeric chart with separators, short enough to stay typeable. */
        const val MAX_LENGTH = 32

        private val PATTERN = Regex("^[A-Za-z0-9._-]+$")

        /** Trims surrounding whitespace before validating, so a pasted code is still usable. */
        fun of(raw: String): AccountCode = AccountCode(raw.trim())
    }
}

/**
 * A chart-of-accounts entry as the domain sees it.
 *
 * Deliberately not the jOOQ record: nothing outside
 * `com.finaxis.platform.accounting.adapter.outbound.persistence` may depend on a generated
 * accounting table, and an ArchUnit rule enforces it.
 *
 * `hasFinancialUse` is absent on purpose. Whether an account has ever received a journal line is a
 * read of `journal_line`, which issue #40 creates; the mutation rules that depend on it are stated
 * in [ChartHierarchyPolicy] and enforced where they are knowable today.
 *
 * [rowVersion] is the value the row was read at, and every update matches on it. It is not a
 * cosmetic counter: an edit and an approval touch the same row, and without the predicate the
 * edit's stale snapshot would write the pre-approval status back — leaving the row and
 * `gl_account_transition_log` disagreeing about whether the account was ever approved.
 */
data class GlAccount(
    val id: UUID,
    val organisationId: UUID,
    val code: AccountCode,
    val name: String,
    val accountClass: AccountClass,
    val usage: AccountUsage,
    val status: GlAccountStatus,
    val parentAccountId: UUID? = null,
    val description: String? = null,
    val isContraAccount: Boolean = false,
    val manualPostingAllowed: Boolean = false,
    val statusReason: String? = null,
    val rowVersion: Long = 0,
) {
    /** True when the account may receive a journal line: `ACTIVE` and `POSTABLE`, both required. */
    val isPostable: Boolean
        get() = status == GlAccountStatus.ACTIVE && usage == AccountUsage.POSTABLE

    /**
     * The side that increases this account: the class-implied side, **inverted** when contra.
     *
     * Derived rather than stored, mirroring `gl_account.normal_balance`, which is a generated
     * column. Two reasons it is not a constructor field. It is a total function of two fields
     * already here, so an independent one would be a second source of truth that could disagree
     * with the row. And the earlier stored form was in fact wrong for every contra account: it
     * defaulted to `accountClass.impliedNormalBalance` whether or not the contra flag was set, so
     * a contra asset was created with a debit balance, and the schema check of the day - written
     * `is_contra_account OR normal_balance = ...` - was a disjunction that let the row through.
     */
    val normalBalance: NormalBalance
        get() =
            when {
                !isContraAccount -> accountClass.impliedNormalBalance
                accountClass.impliedNormalBalance == NormalBalance.DEBIT -> NormalBalance.CREDIT
                else -> NormalBalance.DEBIT
            }
}
