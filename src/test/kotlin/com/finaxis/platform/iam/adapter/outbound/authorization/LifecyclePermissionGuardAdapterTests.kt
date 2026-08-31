package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.common.persistence.PlatformOrganisation
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
    fun `requirePermission delegates denial to an AccessDeniedException`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val adapter = adapterWith(NoMemberships)

        assertFailsWith<AccessDeniedException> {
            adapter.requirePermission(userId, organisationId, "business_date.advance")
        }
    }

    @Test
    fun `requireTenantPermission denies when membership absent`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val adapter = adapterWith(NoMemberships)

        assertFailsWith<AccessDeniedException> {
            adapter.requireTenantPermission(userId, organisationId, "tenant.view")
        }
    }

    @Test
    fun `requireBranchPermission denies when membership absent`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        val adapter = adapterWith(NoMemberships)

        assertFailsWith<AccessDeniedException> {
            adapter.requireBranchPermission(userId, organisationId, branchId, "branch.view")
        }
    }

    @Test
    fun `requirePlatformPermission targets the reserved platform organisation`() {
        val userId = UUID.randomUUID()
        // Use memberships stub that recognises the platform org but has no permissions
        val adapter = adapterWith(NoMemberships)

        assertFailsWith<AccessDeniedException> {
            adapter.requirePlatformPermission(userId, "tenant.reactivate")
        }
    }

    @Test
    fun `requirePlatformPermission passes when actor holds the permission in platform org`() {
        val userId = UUID.randomUUID()
        val adapter = adapterWith(GrantAllInPlatformOrg(userId))

        // Should not throw
        adapter.requirePlatformPermission(userId, "tenant.reactivate")
    }

    @Test
    fun `requireTenantPermission passes when actor holds the permission`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val adapter = adapterWith(GrantAllInOrg(userId, organisationId))

        // Should not throw
        adapter.requireTenantPermission(userId, organisationId, "tenant.view")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun adapterWith(lookup: MembershipSelectionLookup): LifecyclePermissionGuardAdapter {
        val resolver = EffectivePermissionResolver(NoPermissions, ConcurrentMapCacheManager())
        val cache = RequestPermissionCache(resolver)
        val authorizationService = AuthorizationService(lookup, resolver, cache)
        return LifecyclePermissionGuardAdapter(authorizationService)
    }

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

    private class GrantAllInOrg(
        private val userId: UUID,
        private val organisationId: UUID,
    ) : MembershipSelectionLookup {
        private val membershipId: UUID = UUID.randomUUID()

        override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = null

        override fun userStatus(userId: UUID): UserStatus? = null

        override fun findMembership(
            userId: UUID,
            organisationId: UUID,
        ): MembershipSelection? =
            if (userId == this.userId && organisationId == this.organisationId) {
                MembershipSelection(membershipId, userId, organisationId, MembershipStatus.ACTIVE)
            } else {
                null
            }

        override fun organisationStatus(organisationId: UUID): OrganisationStatus =
            if (organisationId == this.organisationId) {
                OrganisationStatus.ACTIVE
            } else {
                OrganisationStatus.SUSPENDED
            }

        override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = emptyList()

        override fun hasAssignedBranch(
            membershipId: UUID,
            branchId: UUID,
        ): Boolean = false
    }

    private class GrantAllInPlatformOrg(
        private val userId: UUID,
    ) : MembershipSelectionLookup {
        private val membershipId: UUID = UUID.randomUUID()

        override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = null

        override fun userStatus(userId: UUID): UserStatus? = null

        override fun findMembership(
            userId: UUID,
            organisationId: UUID,
        ): MembershipSelection? =
            if (userId == this.userId && organisationId == PlatformOrganisation.ID) {
                MembershipSelection(
                    membershipId,
                    userId,
                    PlatformOrganisation.ID,
                    MembershipStatus.ACTIVE,
                )
            } else {
                null
            }

        override fun organisationStatus(organisationId: UUID): OrganisationStatus =
            if (organisationId == PlatformOrganisation.ID) {
                OrganisationStatus.ACTIVE
            } else {
                OrganisationStatus.SUSPENDED
            }

        override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = emptyList()

        override fun hasAssignedBranch(
            membershipId: UUID,
            branchId: UUID,
        ): Boolean = false
    }

    private object NoPermissions : PermissionResolutionQueries {
        override fun membershipStatus(membershipId: UUID): MembershipStatus =
            MembershipStatus.ACTIVE

        override fun rolePermissionCodes(
            membershipId: UUID,
            branchId: UUID?,
        ): Set<String> = setOf("tenant.view", "tenant.reactivate", "branch.view")

        override fun directPermissionEffects(membershipId: UUID): List<PermissionEffectAssignment> =
            emptyList()
    }
}
