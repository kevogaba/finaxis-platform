package com.finaxis.platform.iam.application.security

import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
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
}

private data class PermissionSelection(
    val membershipId: UUID,
    val branchId: UUID?,
)
