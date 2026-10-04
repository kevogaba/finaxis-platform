package com.finaxis.platform.iam.adapter.inbound.startup

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.adapter.outbound.cache.EffectivePermissionCacheNamespaceSweeper
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.authorization.PermissionCacheInvalidator
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.domain.MembershipStatus
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.cache.Cache
import org.springframework.cache.CacheManager
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.data.redis.RedisConnectionFailureException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EffectivePermissionCacheStartupClearerTests {
    private val membershipId = uuidV7()

    @Test
    fun `starting clears every cached effective permission set`() {
        val cacheManager = ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME)
        val cache = cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)!!
        cache.put("a:none", setOf("branch.approve"))
        cache.put("b:none", setOf("branch.create"))

        clearer(cacheManager).afterSingletonsInstantiated()

        assertNull(cache.get("a:none"))
        assertNull(cache.get("b:none"))
    }

    @Test
    fun `a resolution after the clear reads the database again`() {
        val queries = CountingQueries()
        val cacheManager = ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME)
        val resolver = EffectivePermissionResolver(queries, cacheManager)
        queries.codes = setOf("branch.approve")
        assertEquals(setOf("branch.approve"), resolver.effectivePermissions(membershipId))
        queries.codes = emptySet()
        assertEquals(setOf("branch.approve"), resolver.effectivePermissions(membershipId))

        EffectivePermissionCacheStartupClearer(
            PermissionCacheInvalidator(cacheManager, resolver),
            mock(),
        ).afterSingletonsInstantiated()

        assertEquals(emptySet(), resolver.effectivePermissions(membershipId))
    }

    @Test
    fun `an unreachable cache is logged and does not fail the boot`() {
        val cache = mock<Cache>()
        whenever(cache.invalidate()).doThrow(RedisConnectionFailureException("redis is down"))
        val cacheManager = mock<CacheManager>()
        whenever(cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)).thenReturn(cache)

        clearer(cacheManager).afterSingletonsInstantiated()
    }

    @Test
    fun `starting also sweeps the namespaces of every other schema version`() {
        val sweeper = mock<EffectivePermissionCacheNamespaceSweeper>()
        val cacheManager = ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME)

        clearer(cacheManager, sweeper).afterSingletonsInstantiated()

        verify(sweeper).sweepAllVersions()
    }

    @Test
    fun `an unreachable Redis during the sweep is logged and does not fail the boot`() {
        val sweeper = mock<EffectivePermissionCacheNamespaceSweeper>()
        whenever(sweeper.sweepAllVersions())
            .doThrow(RedisConnectionFailureException("redis is down"))

        clearer(ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME), sweeper)
            .afterSingletonsInstantiated()
    }

    private fun clearer(
        cacheManager: CacheManager,
        sweeper: EffectivePermissionCacheNamespaceSweeper = mock(),
    ) = EffectivePermissionCacheStartupClearer(
        PermissionCacheInvalidator(
            cacheManager,
            EffectivePermissionResolver(CountingQueries(), cacheManager),
        ),
        sweeper,
    )

    private class CountingQueries : PermissionResolutionQueries {
        var codes: Set<String> = emptySet()

        override fun membershipStatus(membershipId: UUID) = MembershipStatus.ACTIVE

        override fun rolePermissionCodes(
            membershipId: UUID,
            branchId: UUID?,
        ): Set<String> = codes

        override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> =
            emptyList()

        override fun lockedBreakGlassGrant(
            membershipId: UUID,
            permissionCode: String,
        ) = false
    }
}
