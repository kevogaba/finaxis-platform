package com.finaxis.platform.iam.application.port.outbound

import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import java.util.UUID

/**
 * Direct permission assignment read by the effective-permission resolver.
 */
data class PermissionEffectAssignment(
    val code: String,
    val effect: PermissionEffect,
)

/**
 * Outbound application port for resolving membership-scoped permissions.
 */
interface PermissionResolutionQueries {
    /**
     * Reads the current membership status.
     */
    fun membershipStatus(membershipId: UUID): MembershipStatus?

    /**
     * Reads active permission codes granted through assigned roles.
     */
    fun rolePermissionCodes(membershipId: UUID): Set<String>

    /**
     * Reads direct allow and deny permission assignments.
     */
    fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment>
}
