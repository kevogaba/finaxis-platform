package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.iam.FixedViewRequirements
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.PermissionEffect
import com.finaxis.platform.iam.domain.UserStatus
import com.finaxis.platform.lifecycle.BranchVisibility
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins the per-branch projection of the effective permission rule (ADR 0030, decision 5). */
class AuthorizationServiceBranchVisibilityTests {
    private val organisationId = uuidV7()
    private val userId = uuidV7()
    private val membershipId = uuidV7()
    private val branchA = uuidV7()
    private val branchB = uuidV7()
    private val queries = VisibilityQueries()
    private var organisationStatus = OrganisationStatus.ACTIVE
    private var hasMembership = true

    private val service: AuthorizationService
        get() =
            AuthorizationService(
                lookup(),
                RequestPermissionCache(
                    EffectivePermissionResolver(
                        queries,
                        ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME),
                    ),
                    FixedViewRequirements(),
                ),
                queries,
                FixedViewRequirements(),
            )

    @Test
    fun `a tenant scope grant is visible on every branch`() {
        queries.tenantCodes = setOf(VIEW)
        queries.branchGrants = mapOf(VIEW to setOf(branchA))

        assertEquals(BranchVisibility.AllBranches, visibility())
    }

    @Test
    fun `a direct allow is a tenant wide grant`() {
        queries.direct = listOf(PermissionEffectAssignment(VIEW, PermissionEffect.ALLOW))

        assertEquals(BranchVisibility.AllBranches, visibility())
    }

    @Test
    fun `branch scope grants are visible on exactly their branches`() {
        queries.branchGrants =
            mapOf(VIEW to setOf(branchA, branchB), "other.view" to setOf(uuidV7()))

        assertEquals(BranchVisibility.Branches(setOf(branchA, branchB)), visibility())
    }

    @Test
    fun `a direct deny removes the grant on every branch`() {
        queries.branchGrants = mapOf(VIEW to setOf(branchA))
        queries.direct = listOf(PermissionEffectAssignment(VIEW, PermissionEffect.DENY))

        assertEquals(NONE, visibility())
    }

    @Test
    fun `a direct deny also removes a tenant scope grant`() {
        queries.tenantCodes = setOf(VIEW)
        queries.direct = listOf(PermissionEffectAssignment(VIEW, PermissionEffect.DENY))

        assertEquals(NONE, visibility())
    }

    @Test
    fun `no grant anywhere is no branches`() {
        assertEquals(NONE, visibility())
    }

    @Test
    fun `an inactive membership an inactive organisation and a stranger see nothing`() {
        queries.tenantCodes = setOf(VIEW)
        queries.branchGrants = mapOf(VIEW to setOf(branchA))

        queries.membershipStatus = MembershipStatus.SUSPENDED
        assertEquals(NONE, visibility())

        queries.membershipStatus = MembershipStatus.ACTIVE
        organisationStatus = OrganisationStatus.SUSPENDED
        assertEquals(NONE, visibility())

        organisationStatus = OrganisationStatus.ACTIVE
        hasMembership = false
        assertEquals(NONE, visibility())
    }

    @Test
    fun `a system actor sees every branch`() {
        assertEquals(
            BranchVisibility.AllBranches,
            service.branchVisibility(SystemActor.ID, organisationId, VIEW),
        )
    }

    @Test
    fun `visibility agrees with the target branch check at each branch`() {
        queries.branchGrants = mapOf(VIEW to setOf(branchA))
        val visibility = visibility()

        listOf(branchA, branchB).forEach { branchId ->
            assertEquals(
                visibility.canSee(branchId),
                service.hasPermission(userId, organisationId, branchId, VIEW),
            )
        }
    }

    /**
     * Issue #242: the target-branch check is the visibility, not the effective set of a selected
     * branch, so a branch the projection leaves out (one that is not ACTIVE) is refused although
     * a role assignment on it still grants the code.
     */
    @Test
    fun `the target branch check refuses a branch the visibility leaves out`() {
        queries.selectedBranchOnlyGrants = mapOf(VIEW to setOf(branchA))
        queries.branchGrants = mapOf(VIEW to setOf(branchB))

        assertFalse(service.hasPermission(userId, organisationId, branchA, VIEW))
        assertTrue(service.hasPermission(userId, organisationId, branchB, VIEW))
        assertFailsWith<MissingPermissionException> {
            service.requirePermissionWithViews(userId, organisationId, branchA, VIEW)
        }
    }

    @Test
    fun `a target branch check and a later read of one request decide from one answer`() {
        queries.branchGrants = mapOf(VIEW to setOf(branchA))
        val perRequest = service

        assertTrue(perRequest.hasPermission(userId, organisationId, branchA, VIEW))
        // The branch leaving the projection mid-request (say the mutation suspended it) does not
        // refuse the read-back of the same request.
        queries.branchGrants = emptyMap()
        assertTrue(perRequest.hasPermission(userId, organisationId, branchA, VIEW))
        val visibility = perRequest.branchVisibility(userId, organisationId, VIEW)
        assertEquals(BranchVisibility.Branches(setOf(branchA)), visibility)
        // A new request decides afresh.
        assertFalse(service.hasPermission(userId, organisationId, branchA, VIEW))
    }

    @Test
    fun `one request reads the database once for the same membership and view`() {
        queries.branchGrants = mapOf(VIEW to setOf(branchA))
        val perRequest = service

        val first = perRequest.branchVisibility(userId, organisationId, VIEW)
        val statusReadsAfterFirst = queries.statusReads
        // The grant changing mid-request must not change what the request already decided.
        queries.branchGrants = mapOf(VIEW to setOf(branchB))
        val second = perRequest.branchVisibility(userId, organisationId, VIEW)

        assertEquals(BranchVisibility.Branches(setOf(branchA)), first)
        assertEquals(first, second)
        assertEquals(1, queries.branchReads)
        assertEquals(statusReadsAfterFirst, queries.statusReads)

        perRequest.branchVisibility(userId, organisationId, "other.view")
        assertEquals(2, queries.branchReads)
    }

    private fun visibility() = service.branchVisibility(userId, organisationId, VIEW)

    private fun lookup() =
        object : MembershipSelectionLookup {
            override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = null

            override fun userStatus(userId: UUID): UserStatus? = null

            override fun findMembership(
                userId: UUID,
                organisationId: UUID,
            ): MembershipSelection? =
                MembershipSelection(
                    membershipId,
                    userId,
                    organisationId,
                    MembershipStatus.ACTIVE,
                ).takeIf { hasMembership }

            override fun organisationStatus(organisationId: UUID) = organisationStatus

            override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = emptyList()

            override fun hasAssignedBranch(
                membershipId: UUID,
                branchId: UUID,
            ) = false
        }

    private class VisibilityQueries : PermissionResolutionQueries {
        var membershipStatus = MembershipStatus.ACTIVE
        var tenantCodes: Set<String> = emptySet()
        var branchGrants: Map<String, Set<UUID>> = emptyMap()
        var direct: List<PermissionEffectAssignment> = emptyList()

        /** Grants the selected-branch set still carries that the projection has left out. */
        var selectedBranchOnlyGrants: Map<String, Set<UUID>> = emptyMap()
        var branchReads = 0
        var statusReads = 0

        override fun membershipStatus(membershipId: UUID): MembershipStatus {
            statusReads++
            return membershipStatus
        }

        override fun rolePermissionCodes(
            membershipId: UUID,
            branchId: UUID?,
        ): Set<String> =
            tenantCodes +
                branchGrants.filterValues { branchId in it }.keys +
                selectedBranchOnlyGrants.filterValues { branchId in it }.keys

        override fun directPermissionEffects(membershipId: UUID) = direct

        override fun branchIdsGranting(
            membershipId: UUID,
            permissionCode: String,
        ): Set<UUID> {
            branchReads++
            return branchGrants[permissionCode].orEmpty()
        }

        override fun lockedBreakGlassGrant(
            membershipId: UUID,
            permissionCode: String,
        ) = false
    }

    private companion object {
        const val VIEW = "branch.view"
        val NONE = BranchVisibility.Branches(emptySet())
    }
}
