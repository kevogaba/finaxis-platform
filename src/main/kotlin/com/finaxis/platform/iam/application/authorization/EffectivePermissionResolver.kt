package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import java.util.UUID
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service

/**
 * Resolves effective membership permissions from role bundles and direct grants.
 */
@Service
class EffectivePermissionResolver(
    private val queries: PermissionResolutionQueries,
    private val cacheManager: CacheManager,
) {
    fun effectivePermissions(membershipId: UUID): Set<String> {
        val cache = cacheManager.getCache(CACHE_NAME)
        cache?.get(membershipId, Set::class.java)?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            return cached as Set<String>
        }

        val resolved = resolve(membershipId)
        cache?.put(membershipId, resolved)
        return resolved
    }

    private fun resolve(membershipId: UUID): Set<String> {
        if (queries.membershipStatus(membershipId) != MembershipStatus.ACTIVE) {
            return emptySet()
        }

        val allowed = queries.rolePermissionCodes(membershipId).toMutableSet()
        val denied = mutableSetOf<String>()

        for (assignment in queries.directPermissionEffects(membershipId)) {
            when (assignment.effect) {
                PermissionEffect.ALLOW -> allowed += assignment.code
                PermissionEffect.DENY -> denied += assignment.code
            }
        }

        return allowed.minus(denied)
    }

    companion object {
        const val CACHE_NAME = "iam.effective-permissions"
    }
}

/**
 * Cache invalidation facade for membership permission changes.
 */
@Service
class PermissionCacheInvalidator(
    private val cacheManager: CacheManager,
) {
    fun evictMembership(membershipId: UUID) {
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.evict(membershipId)
    }

    fun clearAll() {
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }
}
