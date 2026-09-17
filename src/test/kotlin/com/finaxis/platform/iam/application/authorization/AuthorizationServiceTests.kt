package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
import org.junit.jupiter.api.assertThrows
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizationServiceTests {
    private val organisationId = uuidV7()
    private val userId = uuidV7()
    private val membershipId = uuidV7()
    private val principal =
        AppPrincipal(
            userId = userId,
            keycloakSubject = "keycloak-user-1",
            organisationId = organisationId,
            membershipId = membershipId,
            email = "user@example.com",
            fullName = "Example User",
            permissions = setOf("logistics.shipment.approve"),
        )

    @Test
    fun `hasPermission evaluates individual permission codes`() {
        val service = authorizationService()

        assertTrue(service.hasPermission(principal, "logistics.shipment.approve"))
        assertFalse(service.hasPermission(principal, "accounting.journal.post"))
    }

    @Test
    fun `requirePermission throws when principal lacks permission`() {
        val service = authorizationService()

        assertThrows<AccessDeniedException> {
            service.requirePermission(principal, "accounting.journal.post")
        }
    }

    @Test
    fun `resource authorization blocks access to another organisation`() {
        val service = authorizationService()
        val resourceRef =
            ResourceRef(
                resourceType = "shipment",
                resourceId = uuidV7(),
                organisationId = uuidV7(),
            )

        assertFalse(service.can(principal, "logistics.shipment.approve", resourceRef))
        assertThrows<AccessDeniedException> {
            service.require(principal, "logistics.shipment.approve", resourceRef)
        }
    }

    @Test
    fun `resource authorization allows same organisation with permission`() {
        val service = authorizationService()
        val branchId = uuidV7()
        val warehouseId = uuidV7()
        val ownerId = uuidV7()
        val resourceRef =
            ResourceRef(
                resourceType = "shipment",
                resourceId = uuidV7(),
                organisationId = organisationId,
                branchId = branchId,
                warehouseId = warehouseId,
                ownerId = ownerId,
            )

        assertTrue(service.can(principal, "logistics.shipment.approve", resourceRef))
        assertTrue(resourceRef.branchId == branchId)
        assertTrue(resourceRef.warehouseId == warehouseId)
        assertTrue(resourceRef.ownerId == ownerId)
    }

    @Test
    fun `missing membership has no effective permissions`() {
        val service = authorizationService()

        assertEquals(emptySet(), service.listEffectivePermissions(userId, organisationId))
        assertFalse(service.hasPermission(userId, organisationId, "branch.create"))
    }

    @Test
    fun `present membership resolves effective permissions through request cache`() {
        val service =
            authorizationService(
                memberships = mapOf(userId to selection()),
                permissions = mapOf(PermissionKey(membershipId, null) to setOf("branch.create")),
            )

        assertEquals(
            setOf("branch.create"),
            service.listEffectivePermissions(userId, organisationId),
        )
        assertTrue(service.hasPermission(userId, organisationId, "branch.create"))
    }

    @Test
    fun `branch permissions resolve with branch cache key`() {
        val branchId = uuidV7()
        val service =
            authorizationService(
                memberships = mapOf(userId to selection()),
                permissions =
                    mapOf(
                        PermissionKey(membershipId, null) to setOf("organisation.read"),
                        PermissionKey(membershipId, branchId) to setOf("branch.create"),
                    ),
            )

        assertEquals(
            setOf("branch.create"),
            service.listEffectiveBranchPermissions(userId, organisationId, branchId),
        )
        assertTrue(service.hasPermission(userId, organisationId, branchId, "branch.create"))
        assertFalse(service.hasPermission(userId, organisationId, branchId, "organisation.read"))
    }

    @Test
    fun `system actors bypass tenant and branch permission lookups`() {
        val service = authorizationService()
        val branchId = uuidV7()

        assertTrue(service.hasPermission(SystemActor.ID, organisationId, "branch.create"))
        assertTrue(service.hasPermission(UUID(0L, 0L), organisationId, branchId, "branch.activate"))
    }

    @Test
    fun `listEffectivePermissions returns empty when organisation is not active`() {
        val service =
            authorizationService(
                memberships = mapOf(userId to selection()),
                permissions = mapOf(PermissionKey(membershipId, null) to setOf("branch.create")),
                organisationStatus = OrganisationStatus.SUSPENDED,
            )

        assertEquals(emptySet(), service.listEffectivePermissions(userId, organisationId))
        assertFalse(service.hasPermission(userId, organisationId, "branch.create"))
    }

    @Test
    fun `listEffectiveBranchPermissions returns empty when organisation is not active`() {
        val branchId = uuidV7()
        val service =
            authorizationService(
                memberships = mapOf(userId to selection()),
                permissions =
                    mapOf(PermissionKey(membershipId, branchId) to setOf("branch.create")),
                organisationStatus = OrganisationStatus.DEPROVISIONED,
            )

        assertEquals(
            emptySet(),
            service.listEffectiveBranchPermissions(userId, organisationId, branchId),
        )
        assertFalse(service.hasPermission(userId, organisationId, branchId, "branch.create"))
    }

    @Test
    fun `requirePermission for arbitrary user throws when permission is absent`() {
        val service =
            authorizationService(
                memberships = mapOf(userId to selection()),
                permissions = mapOf(PermissionKey(membershipId, null) to setOf("branch.read")),
            )

        assertThrows<AccessDeniedException> {
            service.requirePermission(userId, organisationId, "branch.create")
        }
    }

    @Test
    fun `requirePermission for arbitrary user returns when permission is present`() {
        val branchId = uuidV7()
        val service =
            authorizationService(
                memberships = mapOf(userId to selection()),
                permissions =
                    mapOf(
                        PermissionKey(membershipId, null) to setOf("organisation.read"),
                        PermissionKey(membershipId, branchId) to setOf("branch.create"),
                    ),
            )

        service.requirePermission(userId, organisationId, "organisation.read")
        service.requirePermission(userId, organisationId, branchId, "branch.create")
    }

    @Test
    fun `break-glass authority is read under a lock and never from the cached resolver`() {
        val queries = FakePermissionResolutionQueries(mapOf(tenantKey() to setOf(BREAK_GLASS)))
        val service = authorizationService(queries, memberships = tenantMembership())

        service.requireBreakGlassPermission(userId, organisationId, BREAK_GLASS)

        assertEquals(1, queries.lockedReads, "the gate must go through the locking read")
        assertEquals(
            0,
            queries.cachedResolutions,
            "the gate reached the cache-first resolver: a cached answer is by construction a " +
                "pre-revocation answer, and a break-glass control that a cache can satisfy is " +
                "not a control",
        )
    }

    @Test
    fun `break-glass authority is denied when the locking read finds no grant`() {
        val service = authorizationService(memberships = tenantMembership())

        assertThrows<AccessDeniedException> {
            service.requireBreakGlassPermission(userId, organisationId, BREAK_GLASS)
        }
    }

    @Test
    fun `break-glass authority is not granted to a system actor`() {
        val service = authorizationService(memberships = tenantMembership())

        assertThrows<AccessDeniedException> {
            service.requireBreakGlassPermission(SystemActor.ID, organisationId, BREAK_GLASS)
        }
    }

    @Test
    fun `break-glass authority is denied in an organisation that is not active`() {
        val service =
            authorizationService(
                FakePermissionResolutionQueries(mapOf(tenantKey() to setOf(BREAK_GLASS))),
                memberships = tenantMembership(),
                organisationStatus = OrganisationStatus.SUSPENDED,
            )

        assertThrows<AccessDeniedException> {
            service.requireBreakGlassPermission(userId, organisationId, BREAK_GLASS)
        }
    }

    private fun tenantKey() = PermissionKey(membershipId, null)

    private fun tenantMembership() = mapOf(userId to selection())

    private fun authorizationService(
        memberships: Map<UUID, MembershipSelection> = emptyMap(),
        permissions: Map<PermissionKey, Set<String>> = emptyMap(),
        organisationStatus: OrganisationStatus = OrganisationStatus.ACTIVE,
    ): AuthorizationService =
        authorizationService(
            FakePermissionResolutionQueries(permissions),
            memberships,
            organisationStatus,
        )

    /**
     * The same wiring with the caller holding the fake, for the assertions that count its reads.
     *
     * One fake serves both the cached resolver and the locking break-glass read, which is what
     * makes "the gate never touched the cached path" assertable at all.
     */
    private fun authorizationService(
        queries: FakePermissionResolutionQueries,
        memberships: Map<UUID, MembershipSelection> = emptyMap(),
        organisationStatus: OrganisationStatus = OrganisationStatus.ACTIVE,
    ): AuthorizationService {
        val resolver =
            EffectivePermissionResolver(
                queries,
                ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME),
            )
        return AuthorizationService(
            FakeMembershipSelectionLookup(memberships, organisationStatus),
            RequestPermissionCache(resolver),
            queries,
        )
    }

    private fun selection(): MembershipSelection =
        MembershipSelection(
            membershipId = membershipId,
            userId = userId,
            organisationId = organisationId,
            status = MembershipStatus.ACTIVE,
        )

    private companion object {
        /** A real break-glass code, so the test reads as the control it stands for. */
        const val BREAK_GLASS = "journal.post_prior_period"
    }
}

private data class PermissionKey(
    val membershipId: UUID,
    val branchId: UUID?,
)

private class FakeMembershipSelectionLookup(
    private val membershipsByUser: Map<UUID, MembershipSelection>,
    private val organisationStatus: OrganisationStatus = OrganisationStatus.ACTIVE,
) : MembershipSelectionLookup {
    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID? = null

    override fun userStatus(userId: UUID): UserStatus? = null

    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? =
        membershipsByUser[userId]
            ?.takeIf { membership -> membership.organisationId == organisationId }

    override fun organisationStatus(organisationId: UUID): OrganisationStatus? = organisationStatus

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = emptyList()

    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean = false
}

private class FakePermissionResolutionQueries(
    private val permissions: Map<PermissionKey, Set<String>>,
) : PermissionResolutionQueries {
    /** How often the *cached* resolution path read this fake, as opposed to the locking one. */
    var cachedResolutions = 0

    /** How many times the locking break-glass read was issued. */
    var lockedReads = 0

    override fun membershipStatus(membershipId: UUID): MembershipStatus? = MembershipStatus.ACTIVE

    override fun rolePermissionCodes(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String> {
        cachedResolutions++
        return permissions[PermissionKey(membershipId, branchId)].orEmpty()
    }

    override fun directPermissionEffects(
        membershipId: UUID,
    ): List<com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment> =
        emptyList()

    /**
     * The locking break-glass read, answered from the same map the cached resolver reads.
     *
     * Deliberately the *same* source: these tests are about the decision rule, and a fake that
     * answered from somewhere else would let the two drift without a failure.
     */
    override fun lockedBreakGlassGrant(
        membershipId: UUID,
        permissionCode: String,
    ): Boolean {
        lockedReads++
        return permissionCode in permissions[PermissionKey(membershipId, null)].orEmpty()
    }
}
