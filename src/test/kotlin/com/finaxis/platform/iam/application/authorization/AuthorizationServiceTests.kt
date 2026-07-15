package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
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

    private fun authorizationService(
        memberships: Map<UUID, MembershipSelection> = emptyMap(),
        permissions: Map<PermissionKey, Set<String>> = emptyMap(),
        organisationStatus: OrganisationStatus = OrganisationStatus.ACTIVE,
    ): AuthorizationService =
        AuthorizationService(
            FakeMembershipSelectionLookup(memberships, organisationStatus),
            requestPermissionCache(permissions),
        )

    private fun requestPermissionCache(
        permissions: Map<PermissionKey, Set<String>>,
    ): RequestPermissionCache =
        RequestPermissionCache(
            EffectivePermissionResolver(
                FakePermissionResolutionQueries(permissions),
                ConcurrentMapCacheManager(EffectivePermissionResolver.CACHE_NAME),
            ),
        )

    private fun selection(): MembershipSelection =
        MembershipSelection(
            membershipId = membershipId,
            userId = userId,
            organisationId = organisationId,
            status = MembershipStatus.ACTIVE,
        )
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
    override fun membershipStatus(membershipId: UUID): MembershipStatus? = MembershipStatus.ACTIVE

    override fun rolePermissionCodes(
        membershipId: UUID,
        branchId: UUID?,
    ): Set<String> = permissions[PermissionKey(membershipId, branchId)].orEmpty()

    override fun directPermissionEffects(
        membershipId: UUID,
    ): List<com.finaxis.platform.iam.application.port.outbound.PermissionEffectAssignment> =
        emptyList()
}
