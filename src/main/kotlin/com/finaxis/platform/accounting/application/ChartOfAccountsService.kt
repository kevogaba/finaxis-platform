package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.ChartHierarchyPolicy
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.pagination.PaginationProperties
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Chart-of-accounts behaviour over the `gl_account` schema.
 *
 * Owns the rules that are independent of the lifecycle state machine: hierarchy validity, code and
 * structural immutability, posting eligibility and tenant scope. Issue #38 puts the state
 * transitions between [GlAccountStatus] values under the common FSM with maker-checker; this
 * service deliberately does **not** move an account between statuses at all, so the two changes
 * cannot both claim to own the lifecycle.
 *
 * An earlier revision made one exception: a `deactivate` that wrote `INACTIVE` straight through the
 * store. That was the whole problem. It bypassed [com.finaxis.platform.common.transitions
 * .TransitionExecutor], `gl_account_transition_log`, maker-checker, and the audit emission
 * `gl_account.deactivate` is registered `HIGH` for — so a successful privileged deactivation left
 * no lifecycle and no audit evidence. Deactivation belongs to issue #38's lifecycle service, and
 * until that exists there is no path to it here.
 *
 * Every entry point takes an explicit `organisationId` and `actorId` and checks a permission code.
 * Nothing here reads the ambient request context: an accounting write must be callable from a
 * posting path and from a background job, and a service that reaches for a thread-local is callable
 * from only one of them.
 */
@Service
class ChartOfAccountsService(
    private val accounts: GlAccountStore,
    private val writes: GlAccountWriteStore,
    private val permissions: AccountingPermissionGuard,
    private val hierarchyLock: ChartHierarchyLock,
    private val pagination: PaginationProperties,
) {
    /**
     * Creates an account in `DRAFT`, validating its placement in the chart.
     *
     * `DRAFT` is not negotiable here and is not a parameter: an account reaches `ACTIVE` through
     * #38's approval transition, and a create path that accepted a status would be a way around it.
     */
    @Transactional
    fun create(command: CreateGlAccountCommand): GlAccount {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.GL_ACCOUNT_CREATE,
        )
        requireCodeAvailable(command.organisationId, command.code)
        if (command.parentAccountId != null) {
            lockHierarchy(command.organisationId)
        }

        val candidate =
            GlAccount(
                id = PLACEHOLDER_ID,
                organisationId = command.organisationId,
                code = command.code,
                name = command.name,
                accountClass = command.accountClass,
                usage = command.usage,
                status = GlAccountStatus.DRAFT,
                parentAccountId = command.parentAccountId,
                description = command.description,
                isContraAccount = command.isContraAccount,
                manualPostingAllowed = command.manualPostingAllowed,
            )
        requireManualPostingMatchesUsage(candidate)
        validatePlacement(candidate, existing = false)

        return translatingDuplicateCode {
            writes.create(
                NewGlAccount(
                    organisationId = candidate.organisationId,
                    code = candidate.code,
                    name = candidate.name,
                    accountClass = candidate.accountClass,
                    usage = candidate.usage,
                    status = candidate.status,
                    actorId = command.actorId,
                    parentAccountId = candidate.parentAccountId,
                    description = candidate.description,
                    isContraAccount = candidate.isContraAccount,
                    manualPostingAllowed = candidate.manualPostingAllowed,
                ),
            )
        }
    }

    /**
     * Applies a change to an existing account, refusing a structural change once it has children.
     *
     * The status is carried over from the stored row rather than taken from the caller, for the
     * same reason [create] fixes it: moving between statuses is #38's transition, not an update.
     */
    @Transactional
    fun update(command: UpdateGlAccountCommand): GlAccount {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.GL_ACCOUNT_UPDATE,
        )
        // Before the read, not after it. Re-parenting is validated against the chart as read here,
        // so the read and the write have to sit inside the same serialised section - see
        // `lockHierarchy`.
        lockHierarchy(command.organisationId)
        val current = requireAccount(command.organisationId, command.accountId)

        val proposed =
            current.copy(
                code = command.code ?: current.code,
                name = command.name ?: current.name,
                description = command.description.orKeep(current.description),
                usage = command.usage ?: current.usage,
                parentAccountId = command.parentAccountId.orKeep(current.parentAccountId),
                manualPostingAllowed = command.manualPostingAllowed ?: current.manualPostingAllowed,
            )

        ChartHierarchyPolicy.requireStructurallyMutable(
            current,
            proposed,
            accounts.hasChildren(command.organisationId, command.accountId),
        )
        if (proposed.code != current.code) {
            requireCodeAvailable(command.organisationId, proposed.code)
        }
        requireManualPostingMatchesUsage(proposed)
        validatePlacement(proposed, existing = true)

        if (!translatingDuplicateCode { writes.update(proposed, command.actorId) }) {
            // The row version moved, so something else changed this account between the read and
            // the write - most likely an approval. Reporting it as a conflict is what stops the
            // stale snapshot being written over the change that won.
            throw ConflictException(
                code = STALE_ACCOUNT,
                safeDetail = "The general-ledger account changed while this edit was in progress.",
            )
        }
        return proposed.copy(rowVersion = proposed.rowVersion + 1)
    }

    /** Reads one account, permission-gated and tenant-scoped. */
    @Transactional(readOnly = true)
    fun get(
        organisationId: UUID,
        accountId: UUID,
        actorId: UUID,
    ): GlAccount {
        permissions.requireTenantPermission(
            actorId,
            organisationId,
            AccountingPermissions.GL_ACCOUNT_VIEW,
        )
        return requireAccount(organisationId, accountId)
    }

    /** Lists a bounded page of the tenant's chart in code order. */
    @Transactional(readOnly = true)
    fun list(command: ListGlAccountsCommand): GlAccountPage {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.GL_ACCOUNT_VIEW,
        )
        // Bounded by the platform-wide ceiling every other listing endpoint honours, rather than
        // a constant accounting would drift from, and refused here with a named code rather than
        // by an IllegalArgumentException escaping the adapter.
        if (command.pageSize !in 1..pagination.maxPageSize) {
            throw InvalidOperationException(
                code = INVALID_PAGE_SIZE,
                safeDetail = "A page must be between 1 and ${pagination.maxPageSize} rows.",
            )
        }
        return accounts.list(command.organisationId, command.afterCode, command.pageSize)
    }

    /**
     * Returns the account's **ancestors**, root first, as one bounded read.
     *
     * The account itself is not included: `ancestorsOf` drops the anchor row. A caller building a
     * breadcrumb appends the account; a caller building a rollup chain does not want it twice.
     *
     * Ancestors rather than the subtree because the caller that needs this is a report deciding
     * where a figure rolls up to. The subtree read exists on the port for the same purpose in the
     * other direction and is not exposed here until something needs it.
     */
    @Transactional(readOnly = true)
    fun ancestry(
        organisationId: UUID,
        accountId: UUID,
        actorId: UUID,
    ): List<GlAccount> {
        permissions.requireTenantPermission(
            actorId,
            organisationId,
            AccountingPermissions.GL_ACCOUNT_VIEW,
        )
        requireAccount(organisationId, accountId)
        return accounts.ancestorsOf(organisationId, accountId).asReversed()
    }

    private fun validatePlacement(
        candidate: GlAccount,
        existing: Boolean,
    ) {
        val parentId = candidate.parentAccountId ?: return
        val parent =
            accounts.findById(candidate.organisationId, parentId)
                ?: throw ResourceNotFoundException(
                    code = ChartHierarchyPolicy.INVALID_PARENT,
                    safeDetail = "The parent general-ledger account does not exist.",
                )
        ChartHierarchyPolicy.requireAssignableParent(
            candidate,
            parent,
            accounts.ancestorsOf(candidate.organisationId, parent.id),
            subtreeHeightOfChild = if (existing) heightOf(candidate) else 1,
        )
    }

    /**
     * Serialises chart-structure changes for one tenant, for the whole transaction.
     *
     * Re-parenting is validated by reading the chart and then writing to it, and under the
     * repository's READ COMMITTED isolation those are two separate snapshots. Two concurrent moves
     * can therefore both pass validation and both commit: reparent A under B while reparenting B
     * under A, and each transaction's ancestry read predates the other's write. The schema catches
     * only the one-hop case, `chk_gl_account_not_own_parent`, so the multi-hop cycle
     * [ChartHierarchyPolicy] exists to prevent is exactly what lands.
     *
     * A tenant-scoped advisory lock is the cheap fix, and cheap because of what it is **not**
     * guarding. This is not the posting path: chart edits are rare administrative operations, so
     * serialising them per tenant costs nothing measurable, whereas ordering row locks over an
     * unbounded set of affected accounts would be intricate and easy to get subtly wrong. The lock
     * is transaction-scoped, so it releases at commit or rollback with no unlock path to forget.
     *
     * Taken before the read that validation depends on. Taken after it, the lock would exclude the
     * concurrent writer from a section whose premise had already been read outside it.
     */
    private fun lockHierarchy(organisationId: UUID) {
        hierarchyLock.lockChartOfAccounts(organisationId)
    }

    /**
     * How many levels the account carries with it, itself included.
     *
     * Read for an update and not for a create, because a new account has nothing beneath it, and
     * without it the depth bound would measure only the parent's side of a move.
     */
    private fun heightOf(account: GlAccount): Int =
        accounts.subtreeHeightOf(account.organisationId, account.id)

    /**
     * Rejects a header account that also claims to accept manual entries.
     *
     * `V6` enforces the same pairing in `chk_gl_account_manual_posting`. Without this check the
     * combination reaches the database and surfaces as an untranslated
     * `DataIntegrityViolationException` — a 500 with no stable code, while every other chart rule
     * is refused with a named one.
     *
     * There is no companion normal-balance check any more, and its absence is the fix rather than
     * an omission. `normal_balance` is a generated column and [GlAccount.normalBalance] derives the
     * same value, so the pairing cannot be stated wrongly. The check that used to live here read
     * `!isContraAccount && normalBalance != implied`, which mirrored a schema constraint that was
     * itself a disjunction: both let a contra asset carry a debit balance.
     */
    private fun requireManualPostingMatchesUsage(candidate: GlAccount) {
        if (candidate.usage == AccountUsage.HEADER && candidate.manualPostingAllowed) {
            throw InvalidOperationException(
                code = HEADER_NOT_POSTABLE,
                safeDetail = "A header account cannot accept manual journal entries.",
            )
        }
    }

    /**
     * Runs a write, turning a `uq_gl_account_organisation_code` violation into the published
     * conflict.
     *
     * [requireCodeAvailable] is a courtesy, not the check. Two requests creating or renaming to the
     * same code can both see `findByCode` return nothing and both proceed; the loser then fails the
     * unique index. Without this translation it reaches the caller as an infrastructure exception —
     * a 500 — for a case the contract documents as `accounting.gl_account_duplicate_code`. The
     * database is the authority on uniqueness; the precheck only makes the common case a friendlier
     * error than a constraint name.
     */
    private fun <T> translatingDuplicateCode(write: () -> T): T =
        try {
            write()
        } catch (ex: DuplicateKeyException) {
            throw ConflictException(
                code = DUPLICATE_CODE,
                safeDetail = "An account with that code already exists in this organisation.",
                cause = ex,
            )
        }

    private fun requireCodeAvailable(
        organisationId: UUID,
        code: AccountCode,
    ) {
        if (accounts.findByCode(organisationId, code) != null) {
            throw ConflictException(
                code = DUPLICATE_CODE,
                safeDetail = "An account with that code already exists in this organisation.",
            )
        }
    }

    private fun requireAccount(
        organisationId: UUID,
        accountId: UUID,
    ): GlAccount =
        accounts.findById(organisationId, accountId)
            ?: throw ResourceNotFoundException(
                code = NOT_FOUND,
                safeDetail = "The general-ledger account does not exist.",
            )

    private companion object {
        const val NOT_FOUND = "accounting.gl_account_not_found"
        const val DUPLICATE_CODE = "accounting.gl_account_duplicate_code"
        const val HEADER_NOT_POSTABLE = "accounting.gl_account_header_not_postable"
        const val STALE_ACCOUNT = "accounting.gl_account_stale"
        const val INVALID_PAGE_SIZE = "accounting.gl_account_invalid_page_size"

        /**
         * Stands in while the candidate is validated, and is never written.
         *
         * `create` builds a [GlAccount] purely so [ChartHierarchyPolicy] can validate it as a
         * value, then hands a [NewGlAccount] to the store — which has no id field at all, so the
         * database default generates the real one. The nil UUID is used rather than a random one
         * so that a leak would be obvious rather than plausible.
         */
        val PLACEHOLDER_ID: UUID = UUID(0L, 0L)
    }
}

/**
 * Request to add an account to the chart. Status is always `DRAFT`; it is not a parameter.
 *
 * There is no `normalBalance`. It is a total function of [accountClass] and [isContraAccount] —
 * a generated column in the schema and a derived property on [GlAccount] — so a caller supplying it
 * could only agree or be wrong. An earlier revision accepted it, defaulted it to the class-implied
 * side whether or not the contra flag was set, and so created every contra account on the wrong
 * side of the ledger.
 */
data class CreateGlAccountCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val code: AccountCode,
    val name: String,
    val accountClass: AccountClass,
    val usage: AccountUsage,
    val parentAccountId: UUID? = null,
    val description: String? = null,
    val isContraAccount: Boolean = false,
    val manualPostingAllowed: Boolean = false,
)

/**
 * Request to change an account.
 *
 * A field left `null` leaves the stored value alone — except for the two where `null` is itself a
 * value a caller may want to assign, which take a [Patch] instead. Collapsing "say nothing" and
 * "set it to null" into one representation made two documented operations unreachable: an account
 * could never be moved back to the root of the chart, because `parentAccountId = null` read as
 * *keep the current parent*, and a description could never be cleared once set.
 */
data class UpdateGlAccountCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val accountId: UUID,
    val code: AccountCode? = null,
    val name: String? = null,
    val description: Patch<String?> = Patch.Unchanged,
    val usage: AccountUsage? = null,
    val parentAccountId: Patch<UUID?> = Patch.Unchanged,
    val manualPostingAllowed: Boolean? = null,
)

/**
 * A patch field: *say nothing* and *assign this value, which may be null*, told apart.
 *
 * Needed only where `null` is a meaningful assignment. For a field whose stored value can never be
 * null — a code, a name, a usage — a nullable parameter already says "unchanged" unambiguously, and
 * wrapping it would be ceremony.
 */
sealed interface Patch<out T> {
    /** The caller said nothing about this field. */
    data object Unchanged : Patch<Nothing>

    /** The caller assigned [value], which may be null. */
    data class Set<out T>(
        val value: T,
    ) : Patch<T>
}

/** Resolves a [Patch] against the stored value: [current] unless the caller assigned something. */
fun <T> Patch<T>.orKeep(current: T): T =
    when (this) {
        is Patch.Set -> value
        Patch.Unchanged -> current
    }

/** Request for one bounded page of the chart. */
data class ListGlAccountsCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val afterCode: AccountCode? = null,
    val pageSize: Int = 50,
)
