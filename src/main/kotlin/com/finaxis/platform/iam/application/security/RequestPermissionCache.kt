package com.finaxis.platform.iam.application.security

import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.lifecycle.BranchVisibility
import org.springframework.stereotype.Component
import org.springframework.web.context.annotation.RequestScope
import java.util.UUID

/**
 * Request-scoped memoizer for effective permission lookups.
 *
 * This deliberately keeps only per-request entries in front of the app-scoped permission cache, so
 * authorization decisions are not held in a long-lived request-independent cache.
 */
@Component
@RequestScope
class RequestPermissionCache(
    private val resolver: EffectivePermissionResolver,
) {
    private val permissionsBySelection = mutableMapOf<PermissionSelection, Set<String>>()
    private val visibilityByView = mutableMapOf<VisibilitySelection, BranchVisibility>()

    /**
     * Resolves effective permission codes for [membershipId] and [branchId] once per request.
     */
    fun effectivePermissions(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String> =
        permissionsBySelection.getOrPut(PermissionSelection(membershipId, branchId)) {
            resolver.effectivePermissions(membershipId, branchId)
        }

    /**
     * Resolves where [userId] holds [permissionCode] in [organisationId] once per request, by
     * [resolve]. A request that reads twice (a revoke's pre-read and its read-back) therefore
     * decides both reads from one answer, and they cannot disagree.
     */
    fun branchVisibility(
        userId: UUID,
        organisationId: UUID,
        permissionCode: String,
        resolve: () -> BranchVisibility,
    ): BranchVisibility =
        visibilityByView.getOrPut(VisibilitySelection(userId, organisationId, permissionCode)) {
            resolve()
        }
}

private data class VisibilitySelection(
    val userId: UUID,
    val organisationId: UUID,
    val permissionCode: String,
)

private data class PermissionSelection(
    val membershipId: UUID,
    val branchId: UUID?,
)
