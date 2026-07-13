package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Resolves effective membership permissions from role bundles and direct grants.
 */
@Service
class EffectivePermissionResolver(
    private val queries: PermissionResolutionQueries,
    private val cacheManager: CacheManager,
) {
    private val cachedBranchesByMembership = ConcurrentHashMap<UUID, MutableSet<UUID?>>()

    /**
     * Resolves and caches effective permission codes for a membership in [branchId]'s context.
     * Branch-scoped role grants only apply while that branch is selected, so the cache key must
     * include it to avoid serving another branch's permissions after a branch switch.
     */
    fun effectivePermissions(
        membershipId: UUID,
        branchId: UUID? = null,
    ): Set<String> {
        val cache = cacheManager.getCache(CACHE_NAME)
        val cacheKey = cacheKey(membershipId, branchId)
        val cached = cache?.get(cacheKey)?.get()
        if (cached is Set<*> && cached.all { permission -> permission is String }) {
            return cached.filterIsInstance<String>().toSet()
        }

        val resolved = resolve(membershipId, branchId)
        cache?.put(cacheKey, resolved)
        cachedBranchesByMembership
            .computeIfAbsent(membershipId) {
                CopyOnWriteArraySet()
            }.add(branchId)
        return resolved
    }

    /**
     * Returns the branch selections previously cached for [membershipId], for eviction.
     */
    internal fun cachedBranchSelections(membershipId: UUID): Set<UUID?> =
        cachedBranchesByMembership.remove(membershipId).orEmpty()

    private fun resolve(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String> {
        if (queries.membershipStatus(membershipId) != MembershipStatus.ACTIVE) {
            return emptySet()
        }

        val allowed = queries.rolePermissionCodes(membershipId, branchId).toMutableSet()
        val denied = mutableSetOf<String>()

        for (assignment in queries.directPermissionEffects(membershipId)) {
            when (assignment.effect) {
                PermissionEffect.ALLOW -> allowed += assignment.code
                PermissionEffect.DENY -> denied += assignment.code
            }
        }

        return allowed.minus(denied)
    }

    /**
     * Cache constants used by permission resolution and invalidation.
     */
    companion object {
        const val CACHE_NAME = "iam.effective-permissions"

        internal fun cacheKey(
            membershipId: UUID,
            branchId: UUID?,
        ): String = "$membershipId:${branchId ?: "none"}"
    }
}

/**
 * Cache invalidation facade for membership permission changes.
 */
@Service
class PermissionCacheInvalidator(
    private val cacheManager: CacheManager,
    private val resolver: EffectivePermissionResolver,
) {
    /**
     * Evicts every branch-scoped cache entry previously resolved for [membershipId]. Entries are
     * keyed by membership and selected branch, so each cached branch selection is evicted in
     * turn instead of only the no-branch entry.
     */
    fun evictMembership(membershipId: UUID) {
        val cache = cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME) ?: return
        resolver.cachedBranchSelections(membershipId).forEach { branchId ->
            cache.evict(EffectivePermissionResolver.cacheKey(membershipId, branchId))
        }
    }

    /**
     * Clears the effective-permission cache.
     */
    fun clearAll() {
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }
}
