package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.cache.support.NoOpCacheManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class EffectivePermissionResolverTests {
    private val membershipId = UUID.randomUUID()

    @Test
    fun `user with one organisation gets role and direct allow permissions`() {
        val queries =
            FakePermissionQueries(
                membershipStatuses = mapOf(membershipId to MembershipStatus.ACTIVE),
                rolePermissions =
                    mapOf(
                        membershipId to setOf("logistics.shipment.approve"),
                    ),
                directPermissions =
                    mapOf(
                        membershipId to
                            listOf(
                                PermissionEffectAssignment(
                                    "iam.user.invite",
                                    PermissionEffect.ALLOW,
                                ),
                            ),
                    ),
            )
        val resolver = resolver(queries)

        assertEquals(
            setOf("logistics.shipment.approve", "iam.user.invite"),
            resolver.effectivePermissions(membershipId),
        )
    }

    @Test
    fun `same user with two organisations receives different active organisation permissions`() {
        val secondMembershipId = UUID.randomUUID()
        val resolver =
            resolver(
                FakePermissionQueries(
                    membershipStatuses =
                        mapOf(
                            membershipId to MembershipStatus.ACTIVE,
                            secondMembershipId to MembershipStatus.ACTIVE,
                        ),
                    rolePermissions =
                        mapOf(
                            membershipId to setOf("logistics.shipment.approve"),
                            secondMembershipId to setOf("accounting.journal.post"),
                        ),
                ),
            )

        assertEquals(
            setOf("logistics.shipment.approve"),
            resolver.effectivePermissions(membershipId),
        )
        assertEquals(
            setOf("accounting.journal.post"),
            resolver.effectivePermissions(secondMembershipId),
        )
    }

    @Test
    fun `direct deny overrides role allow`() {
        val resolver =
            resolver(
                FakePermissionQueries(
                    membershipStatuses = mapOf(membershipId to MembershipStatus.ACTIVE),
                    rolePermissions = mapOf(membershipId to setOf("logistics.shipment.approve")),
                    directPermissions =
                        mapOf(
                            membershipId to
                                listOf(
                                    PermissionEffectAssignment(
                                        "logistics.shipment.approve",
                                        PermissionEffect.DENY,
                                    ),
                                ),
                        ),
                ),
            )

        assertEquals(emptySet(), resolver.effectivePermissions(membershipId))
    }

    @Test
    fun `suspended membership denies all permissions`() {
        val resolver =
            resolver(
                FakePermissionQueries(
                    membershipStatuses = mapOf(membershipId to MembershipStatus.SUSPENDED),
                    rolePermissions = mapOf(membershipId to setOf("logistics.shipment.approve")),
                    directPermissions =
                        mapOf(
                            membershipId to
                                listOf(
                                    PermissionEffectAssignment(
                                        "iam.user.invite",
                                        PermissionEffect.ALLOW,
                                    ),
                                ),
                        ),
                ),
            )

        assertEquals(emptySet(), resolver.effectivePermissions(membershipId))
    }

    @Test
    fun `permission cache invalidation reloads changed role and direct permissions`() {
        val approvalPermission = setOf("logistics.shipment.approve")
        val queries =
            FakePermissionQueries(
                membershipStatuses = mapOf(membershipId to MembershipStatus.ACTIVE),
                rolePermissions = mapOf(membershipId to approvalPermission),
            )
        val cacheManager = ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME)
        val resolver = EffectivePermissionResolver(queries, cacheManager)
        val invalidator = PermissionCacheInvalidator(cacheManager)

        assertEquals(
            setOf("logistics.shipment.approve"),
            resolver.effectivePermissions(membershipId),
        )
        assertEquals(
            setOf("logistics.shipment.approve"),
            resolver.effectivePermissions(membershipId),
        )

        queries.rolePermissions = mapOf(membershipId to setOf("accounting.journal.post"))
        queries.directPermissions =
            mapOf(
                membershipId to
                    listOf(PermissionEffectAssignment("iam.user.invite", PermissionEffect.ALLOW)),
            )
        invalidator.evictMembership(membershipId)

        assertEquals(
            setOf("accounting.journal.post", "iam.user.invite"),
            resolver.effectivePermissions(membershipId),
        )

        queries.rolePermissions = mapOf(membershipId to setOf("iam.user.invite"))
        invalidator.clearAll()

        assertEquals(setOf("iam.user.invite"), resolver.effectivePermissions(membershipId))
    }

    @Test
    fun `permission cache invalidator tolerates missing cache`() {
        val invalidator = PermissionCacheInvalidator(NoOpCacheManager())

        invalidator.evictMembership(UUID.randomUUID())
        invalidator.clearAll()
    }

    private fun resolver(queries: FakePermissionQueries): EffectivePermissionResolver =
        EffectivePermissionResolver(
            queries,
            ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME),
        )
}

private class FakePermissionQueries(
    var membershipStatuses: Map<UUID, MembershipStatus> = emptyMap(),
    var rolePermissions: Map<UUID, Set<String>> = emptyMap(),
    var directPermissions: Map<UUID, List<PermissionEffectAssignment>> = emptyMap(),
) : PermissionResolutionQueries {
    override fun membershipStatus(membershipId: UUID): MembershipStatus? =
        membershipStatuses[membershipId]

    override fun rolePermissionCodes(membershipId: UUID): Set<String> =
        rolePermissions[membershipId].orEmpty()

    override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> =
        directPermissions[membershipId].orEmpty()
}
