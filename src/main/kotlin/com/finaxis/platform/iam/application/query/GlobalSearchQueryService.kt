package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.query.BranchFilter
import com.finaxis.platform.lifecycle.application.query.BranchSummary
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import org.springframework.stereotype.Service

/**
 * The hits of one global search (ADR 0039): at most [GlobalSearchQueryService.HITS_PER_TYPE] per
 * type, in the order each per-resource list already uses. A type the caller may not view is
 * `null`, which is different from an empty list (viewable, no match). No count is carried.
 */
data class GlobalSearchResult(
    val users: List<UserInTenantSummary>?,
    val branches: List<BranchSummary>?,
    val roles: List<RoleSummary>?,
    val memberships: List<MembershipSummary>?,
)

/**
 * Tenant global search: one `q`, four existing gated queries (users, branches, roles,
 * memberships), one bounded page of each (ADR 0039).
 *
 * This service holds no permission rule. Each type is read through the `@GatedRead` method that
 * already serves that resource's own list, so the permission code, the branch visibility and the
 * archived-role rule stay where they are decided; a type whose query refuses the caller is
 * omitted, and a caller refused by all four gets the first refusal (the users one, `user.view`).
 * `q` is validated before any query runs, and this class never logs it (the HTTP access log
 * redacts the route's query string; see `HttpAccessLogFilter`).
 */
@Service
class GlobalSearchQueryService(
    private val iamQueryService: IamQueryService,
    private val foundationQueryService: FoundationQueryService,
) {
    /**
     * Searches the caller's active organisation for [q]. Throws [InvalidPageRequestException]
     * naming `q` when it is missing, not 2 to 64 characters after trimming, or holds a control
     * character, before any query.
     * A type the caller cannot view is omitted from the result; when none can be viewed the
     * refusal of the first type, in the order users, branches, roles, memberships, is rethrown.
     * Any other exception propagates.
     */
    @GatedRead
    fun search(
        caller: TenantCaller,
        q: String?,
    ): GlobalSearchResult {
        val text = escapeLikePattern(requireValidSearchText(q))
        val users = attempt { searchUsers(caller, text) }
        val branches = attempt { searchBranches(caller, text) }
        val roles = attempt { searchRoles(caller, text) }
        val memberships = attempt { searchMemberships(caller, text) }
        if (listOf(users.hits, branches.hits, roles.hits, memberships.hits).all { it == null }) {
            throw checkNotNull(users.refusal)
        }
        return GlobalSearchResult(users.hits, branches.hits, roles.hits, memberships.hits)
    }

    @GatedRead
    private fun searchUsers(
        caller: TenantCaller,
        text: String,
    ): List<UserInTenantSummary> =
        iamQueryService
            .searchUsers(
                caller.activeOrganisationId,
                UserInTenantFilter(q = text, page = 0, size = HITS_PER_TYPE),
                caller,
            ).items

    @GatedRead
    private fun searchBranches(
        caller: TenantCaller,
        text: String,
    ): List<BranchSummary> =
        foundationQueryService
            .searchBranches(
                caller.activeOrganisationId,
                BranchFilter(q = text, page = 0, size = HITS_PER_TYPE),
                caller,
            ).items

    @GatedRead
    private fun searchRoles(
        caller: TenantCaller,
        text: String,
    ): List<RoleSummary> =
        iamQueryService
            .searchRoles(
                caller.activeOrganisationId,
                RoleFilter(q = text, page = 0, size = HITS_PER_TYPE),
                caller,
            ).items

    @GatedRead
    private fun searchMemberships(
        caller: TenantCaller,
        text: String,
    ): List<MembershipSummary> =
        iamQueryService
            .searchMemberships(
                caller.activeOrganisationId,
                MembershipFilter(q = text, page = 0, size = HITS_PER_TYPE),
                caller,
            ).items

    /**
     * Runs one type's read. Only a [ForbiddenOperationException] is caught, because that is the
     * base of the named refusal and of the unnamed one a branch-scoped caller with no grant gets;
     * it wraps the delegate call alone, so every other failure still propagates.
     */
    private inline fun <T> attempt(read: () -> List<T>): Attempt<T> =
        try {
            Attempt(read(), null)
        } catch (refusal: ForbiddenOperationException) {
            Attempt(null, refusal)
        }

    private class Attempt<T>(
        val hits: List<T>?,
        val refusal: ForbiddenOperationException?,
    )

    private fun requireValidSearchText(q: String?): String {
        val text = q?.trim().orEmpty()
        if (text.length !in MIN_QUERY_LENGTH..MAX_QUERY_LENGTH) {
            throw InvalidPageRequestException(
                "q",
                "q must be between $MIN_QUERY_LENGTH and $MAX_QUERY_LENGTH characters.",
            )
        }
        // A NUL (and the other control characters) is not text: PostgreSQL refuses a NUL in a text
        // parameter, which would surface as a 500 instead of this 400.
        if (text.codePoints().anyMatch(Character::isISOControl)) {
            throw InvalidPageRequestException("q", "q must not contain control characters.")
        }
        return text
    }

    /** Public bounds and cap of the search, published in the OpenAPI document. */
    companion object {
        /** Shortest accepted `q`, after trimming. */
        const val MIN_QUERY_LENGTH = 2

        /** Longest accepted `q`, after trimming. */
        const val MAX_QUERY_LENGTH = 64

        /** Hits returned per type: page 0, size 10 of that type's own list. */
        const val HITS_PER_TYPE = 10
    }
}

/**
 * Escapes the characters `ILIKE` treats specially (`\`, `%`, `_`) with PostgreSQL's default
 * backslash escape, so the text matches only itself. The backslash goes first so the escapes
 * added for the other two are not themselves doubled.
 */
internal fun escapeLikePattern(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
