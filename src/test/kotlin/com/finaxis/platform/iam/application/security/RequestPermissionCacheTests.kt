package com.finaxis.platform.iam.application.security

import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.domain.MembershipStatus
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class RequestPermissionCacheTests {
    private val membershipId = UUID.randomUUID()

    @Test
    fun `same membership and branch resolve once per request cache`() {
        val queries =
            CountingPermissionQueries(
                permissions = mapOf(PermissionKey(membershipId, null) to setOf("branch.create")),
            )
        val cache = requestCache(queries)

        assertEquals(setOf("branch.create"), cache.effectivePermissions(membershipId, null))
        assertEquals(setOf("branch.create"), cache.effectivePermissions(membershipId, null))

        assertEquals(1, queries.resolveCount(PermissionKey(membershipId, null)))
    }

    @Test
    fun `different branch keys resolve separately`() {
        val branchId = UUID.randomUUID()
        val queries =
            CountingPermissionQueries(
                permissions =
                    mapOf(
                        PermissionKey(membershipId, null) to setOf("organisation.read"),
                        PermissionKey(membershipId, branchId) to setOf("branch.create"),
                    ),
            )
        val cache = requestCache(queries)

        assertEquals(setOf("organisation.read"), cache.effectivePermissions(membershipId, null))
        assertEquals(setOf("branch.create"), cache.effectivePermissions(membershipId, branchId))
        assertEquals(setOf("branch.create"), cache.effectivePermissions(membershipId, branchId))

        assertEquals(1, queries.resolveCount(PermissionKey(membershipId, null)))
        assertEquals(1, queries.resolveCount(PermissionKey(membershipId, branchId)))
    }

    private fun requestCache(queries: CountingPermissionQueries): RequestPermissionCache =
        RequestPermissionCache(
            EffectivePermissionResolver(
                queries,
                ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME),
            ),
        )
}

private data class PermissionKey(
    val membershipId: UUID,
    val branchId: UUID?,
)

private class CountingPermissionQueries(
    private val permissions: Map<PermissionKey, Set<String>>,
) : PermissionResolutionQueries {
    private val calls = mutableMapOf<PermissionKey, Int>()

    override fun membershipStatus(membershipId: UUID): MembershipStatus? = MembershipStatus.ACTIVE

    override fun rolePermissionCodes(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String> {
        val key = PermissionKey(membershipId, branchId)
        calls[key] = calls.getOrDefault(key, 0) + 1
        return permissions[key].orEmpty()
    }

    override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> =
        emptyList()

    fun resolveCount(key: PermissionKey): Int = calls.getOrDefault(key, 0)
}
