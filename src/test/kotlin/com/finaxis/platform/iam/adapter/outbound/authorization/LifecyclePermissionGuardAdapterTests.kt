package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LifecyclePermissionGuardAdapterTests {
    @Test
    fun `delegates denial to an AccessDeniedException`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val authorizationService = AuthorizationService(NoMemberships, denyingCache())
        val adapter = LifecyclePermissionGuardAdapter(authorizationService)

        assertFailsWith<AccessDeniedException> {
            adapter.requirePermission(userId, organisationId, "business_date.advance")
        }
    }

    private fun denyingCache(): RequestPermissionCache =
        RequestPermissionCache(
            EffectivePermissionResolver(NoPermissions, ConcurrentMapCacheManager()),
        )

    private object NoMemberships : MembershipSelectionLookup {
        override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = null

        override fun userStatus(userId: UUID): UserStatus? = null

        override fun findMembership(
            userId: UUID,
            organisationId: UUID,
        ): MembershipSelection? = null

        override fun organisationStatus(organisationId: UUID): OrganisationStatus? = null

        override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = emptyList()

        override fun hasAssignedBranch(
            membershipId: UUID,
            branchId: UUID,
        ): Boolean = false
    }

    private object NoPermissions : PermissionResolutionQueries {
        override fun membershipStatus(membershipId: UUID): MembershipStatus? = null

        override fun rolePermissionCodes(
            membershipId: UUID,
            branchId: UUID?,
        ): Set<String> = emptySet()

        override fun directPermissionEffects(
            membershipId: UUID,
        ): List<PermissionEffectAssignment> = emptyList()
    }
}
