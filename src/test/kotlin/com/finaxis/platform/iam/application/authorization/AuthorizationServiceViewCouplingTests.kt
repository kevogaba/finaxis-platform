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
import org.junit.jupiter.api.assertThrows
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ADR 0030, decision 4: the central check requires a mutation code and every view code the
 * catalogue pairs with it, at the same scope, and names the first missing code.
 */
class AuthorizationServiceViewCouplingTests {
    private val organisationId = uuidV7()
    private val userId = uuidV7()
    private val membershipId = uuidV7()
    private val branchId = uuidV7()
    private val queries = Queries()
    private val requirements =
        FixedViewRequirements(
            mapOf(
                "branch.suspend" to listOf("branch.view"),
                "user.invite" to listOf("membership.view", "user.view"),
                BREAK_GLASS to listOf("journal.view"),
            ),
        )

    @Test
    fun `a mutation with its view passes`() {
        queries.tenantCodes = setOf("branch.suspend", "branch.view")

        service().requirePermissionWithViews(userId, organisationId, "branch.suspend")
    }

    @Test
    fun `a mutation without its view names the view`() {
        queries.tenantCodes = setOf("branch.suspend")

        val refusal = missing { tenant("branch.suspend") }

        assertEquals("branch.view", refusal.permissionCode)
        assertEquals("Missing permission: branch.view.", refusal.safeDetail)
    }

    @Test
    fun `the mutation code is named before its views`() {
        assertEquals("branch.suspend", firstMissing { tenant("branch.suspend") })
    }

    @Test
    fun `the first missing of several views is named`() {
        queries.tenantCodes = setOf("user.invite", "user.view")
        assertEquals("membership.view", firstMissing { tenant("user.invite") })

        queries.tenantCodes = setOf("user.invite", "membership.view")
        assertEquals("user.view", firstMissing { tenant("user.invite") })
    }

    @Test
    fun `a view or context code is checked alone`() {
        queries.tenantCodes = setOf("branch.view")

        service().requirePermissionWithViews(userId, organisationId, "branch.view")
    }

    @Test
    fun `a direct deny of the view refuses the mutation`() {
        queries.tenantCodes = setOf("branch.suspend", "branch.view")
        queries.direct = listOf(PermissionEffectAssignment("branch.view", PermissionEffect.DENY))

        assertEquals("branch.view", firstMissing { tenant("branch.suspend") })
    }

    @Test
    fun `a system actor is exempt and never reads the pairing`() {
        service().requirePermissionWithViews(SystemActor.ID, organisationId, "branch.suspend")

        assertEquals(0, requirements.reads)
    }

    @Test
    fun `the pairing is read once per request however many checks it makes`() {
        queries.tenantCodes =
            setOf("branch.suspend", "branch.view", "user.invite", "membership.view", "user.view")
        val perRequest = service()

        perRequest.requirePermissionWithViews(userId, organisationId, "branch.suspend")
        perRequest.requirePermissionWithViews(userId, organisationId, "user.invite")
        perRequest.requirePermissionWithViews(userId, organisationId, "branch.view")

        assertEquals(1, requirements.reads)
    }

    @Test
    fun `at a target branch the mutation and the view are both asked at that branch`() {
        queries.branchGrants =
            mapOf("branch.suspend" to setOf(branchId), "branch.view" to setOf(branchId))
        branch("branch.suspend")

        queries.branchGrants =
            mapOf("branch.suspend" to setOf(uuidV7()), "branch.view" to setOf(branchId))
        assertEquals("branch.suspend", firstMissing { branch("branch.suspend") })
    }

    @Test
    fun `a tenant wide view serves a branch mutation and a foreign branch view does not`() {
        queries.tenantCodes = setOf("branch.view")
        queries.branchGrants = mapOf("branch.suspend" to setOf(branchId))
        branch("branch.suspend")

        queries.tenantCodes = emptySet()
        queries.branchGrants =
            mapOf("branch.suspend" to setOf(branchId), "branch.view" to setOf(uuidV7()))
        assertEquals("branch.view", firstMissing { branch("branch.suspend") })
    }

    @Test
    fun `the view check and a later read decide from one answer per request`() {
        queries.tenantCodes = setOf("branch.suspend", "branch.view")
        val perRequest = service()
        perRequest.requirePermissionWithViews(userId, organisationId, "branch.suspend")
        val readsAfterPreCheck = queries.roleReads

        // The grant is revoked between the pre-check and the read-back.
        queries.tenantCodes = setOf("branch.suspend")
        perRequest.requirePermission(userId, organisationId, "branch.view")

        assertEquals(readsAfterPreCheck, queries.roleReads)
    }

    @Test
    fun `break glass names the code and then its view from the locked read`() {
        queries.lockedCodes = setOf(BREAK_GLASS)
        assertEquals("journal.view", firstMissing { breakGlass(userId) })

        queries.lockedCodes = setOf(BREAK_GLASS, "journal.view")
        breakGlass(userId)
    }

    @Test
    fun `break glass is not exempt for a system actor`() {
        assertEquals(BREAK_GLASS, firstMissing { breakGlass(SystemActor.ID) })
    }

    @Test
    fun `mutation visibility is every branch when the mutation and its views are tenant wide`() {
        queries.tenantCodes = setOf("branch.suspend", "branch.view")

        assertEquals(BranchVisibility.AllBranches, mutationVisibility())
    }

    @Test
    fun `mutation visibility is the branches where the mutation and its view are both held`() {
        val other = uuidV7()
        queries.branchGrants =
            mapOf(
                "branch.suspend" to setOf(branchId, other),
                "branch.view" to setOf(branchId, uuidV7()),
            )

        assertEquals(BranchVisibility.Branches(setOf(branchId)), mutationVisibility())

        // A tenant-wide view serves every branch of the mutation's own grants.
        queries.tenantCodes = setOf("branch.view")
        assertEquals(BranchVisibility.Branches(setOf(branchId, other)), mutationVisibility())
    }

    @Test
    fun `mutation visibility agrees with the target branch check at every branch`() {
        queries.branchGrants =
            mapOf("branch.suspend" to setOf(branchId), "branch.view" to setOf(branchId))
        val visibility = mutationVisibility()

        listOf(branchId, uuidV7()).forEach { target ->
            val allowed =
                runCatching {
                    service().requirePermissionWithViews(
                        userId,
                        organisationId,
                        target,
                        "branch.suspend",
                    )
                }.isSuccess
            assertEquals(allowed, visibility.canSee(target))
        }
    }

    @Test
    fun `mutation visibility names the first code held nowhere and never depends on a row`() {
        assertEquals("branch.suspend", firstMissing { mutationVisibility() })

        queries.branchGrants = mapOf("branch.suspend" to setOf(branchId))
        assertEquals("branch.view", firstMissing { mutationVisibility() })

        // Held somewhere but never together: no branch, and no refusal until a row is asked for.
        queries.branchGrants =
            mapOf("branch.suspend" to setOf(branchId), "branch.view" to setOf(uuidV7()))
        assertEquals(BranchVisibility.Branches(emptySet()), mutationVisibility())
    }

    @Test
    fun `mutation visibility honours a direct deny and exempts a system actor`() {
        queries.tenantCodes = setOf("branch.suspend", "branch.view")
        queries.direct = listOf(PermissionEffectAssignment("branch.view", PermissionEffect.DENY))
        assertEquals("branch.view", firstMissing { mutationVisibility() })

        assertEquals(
            BranchVisibility.AllBranches,
            service().mutationBranchVisibility(SystemActor.ID, organisationId, "branch.suspend"),
        )
    }

    private fun mutationVisibility() =
        service().mutationBranchVisibility(userId, organisationId, "branch.suspend")

    private fun tenant(code: String) =
        service().requirePermissionWithViews(userId, organisationId, code)

    private fun branch(code: String) =
        service().requirePermissionWithViews(userId, organisationId, branchId, code)

    private fun breakGlass(actor: UUID) =
        service().requireBreakGlassPermissionWithViews(actor, organisationId, BREAK_GLASS)

    private fun firstMissing(block: () -> Unit): String = missing(block).permissionCode

    private fun missing(block: () -> Unit): MissingPermissionException =
        assertThrows<MissingPermissionException> { block() }

    private fun service(): AuthorizationService {
        val resolver =
            EffectivePermissionResolver(
                queries,
                ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME),
            )
        return AuthorizationService(
            ActiveMembership(),
            RequestPermissionCache(resolver, requirements),
            queries,
            requirements,
        )
    }

    private inner class ActiveMembership : MembershipSelectionLookup {
        override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = null

        override fun userStatus(userId: UUID): UserStatus? = null

        override fun findMembership(
            userId: UUID,
            organisationId: UUID,
        ): MembershipSelection =
            MembershipSelection(membershipId, userId, organisationId, MembershipStatus.ACTIVE)

        override fun organisationStatus(organisationId: UUID) = OrganisationStatus.ACTIVE

        override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = emptyList()

        override fun hasAssignedBranch(
            membershipId: UUID,
            branchId: UUID,
        ) = false
    }

    private class Queries : PermissionResolutionQueries {
        var tenantCodes: Set<String> = emptySet()
        var branchGrants: Map<String, Set<UUID>> = emptyMap()
        var direct: List<PermissionEffectAssignment> = emptyList()
        var lockedCodes: Set<String> = emptySet()
        var roleReads = 0

        override fun membershipStatus(membershipId: UUID) = MembershipStatus.ACTIVE

        override fun rolePermissionCodes(
            membershipId: UUID,
            branchId: UUID?,
        ): Set<String> {
            roleReads++
            return tenantCodes + branchGrants.filterValues { branchId in it }.keys
        }

        override fun directPermissionEffects(membershipId: UUID) = direct

        override fun branchIdsGranting(
            membershipId: UUID,
            permissionCode: String,
        ): Set<UUID> = branchGrants[permissionCode].orEmpty()

        override fun lockedBreakGlassGrant(
            membershipId: UUID,
            permissionCode: String,
        ) = permissionCode in lockedCodes
    }

    private companion object {
        const val BREAK_GLASS = "journal.post_prior_period"
    }
}
