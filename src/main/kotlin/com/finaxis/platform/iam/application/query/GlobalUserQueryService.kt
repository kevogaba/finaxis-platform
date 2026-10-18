package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.requireValidPage
import com.finaxis.platform.common.web.api.requireValidSort
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The platform-wide user directory (#160): reads of user accounts across every organisation, for
 * a platform caller holding `user.view` in the platform organisation. A tenant caller never
 * reaches it, and the permission is checked before the request is judged or any row is read.
 */
@Service
class GlobalUserQueryService(
    private val queries: GlobalUserQueries,
    private val permissionGuard: PermissionGuard,
) {
    /** Pages the user accounts that match [filter]. */
    @GatedRead
    fun searchUsers(
        filter: GlobalUserFilter,
        caller: PlatformCaller,
    ): ApiPage<GlobalUserSummary> {
        permissionGuard.requirePlatformPermission(caller.actorId, VIEW_PERMISSION)
        requireValidPage(filter.page, filter.size)
        requireValidSort(filter.sortBy, filter.sortDir, ALLOWED_SORTS)
        return queries.searchUsers(filter)
    }

    /** The user account [userId] with its memberships. */
    @GatedRead
    fun getUser(
        userId: UUID,
        caller: PlatformCaller,
    ): GlobalUserProfile {
        permissionGuard.requirePlatformPermission(caller.actorId, VIEW_PERMISSION)
        return queries.findUser(userId)
            ?: throw ResourceNotFoundException(safeDetail = "User account was not found.")
    }

    internal companion object {
        /** The `sort_by` values the directory accepts; the store must order by each. */
        val ALLOWED_SORTS = setOf("createdAt", "username", "displayName", "email", "userStatus")
        const val VIEW_PERMISSION = "user.view"
    }
}
