package com.finaxis.platform.iam.application.authorization

import com.finaxis.platform.iam.application.context.AppPrincipal
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthorizationServiceTests {
    private val organisationId = UUID.randomUUID()
    private val principal =
        AppPrincipal(
            userId = UUID.randomUUID(),
            keycloakSubject = "keycloak-user-1",
            organisationId = organisationId,
            membershipId = UUID.randomUUID(),
            email = "user@example.com",
            fullName = "Example User",
            permissions = setOf("logistics.shipment.approve"),
        )

    @Test
    fun `hasPermission evaluates individual permission codes`() {
        val service = AuthorizationService()

        assertTrue(service.hasPermission(principal, "logistics.shipment.approve"))
        assertFalse(service.hasPermission(principal, "accounting.journal.post"))
    }

    @Test
    fun `requirePermission throws when principal lacks permission`() {
        val service = AuthorizationService()

        assertThrows<AccessDeniedException> {
            service.requirePermission(principal, "accounting.journal.post")
        }
    }

    @Test
    fun `resource authorization blocks access to another organisation`() {
        val service = AuthorizationService()
        val resourceRef =
            ResourceRef(
                resourceType = "shipment",
                resourceId = UUID.randomUUID(),
                organisationId = UUID.randomUUID(),
            )

        assertFalse(service.can(principal, "logistics.shipment.approve", resourceRef))
        assertThrows<AccessDeniedException> {
            service.require(principal, "logistics.shipment.approve", resourceRef)
        }
    }

    @Test
    fun `resource authorization allows same organisation with permission`() {
        val service = AuthorizationService()
        val branchId = UUID.randomUUID()
        val warehouseId = UUID.randomUUID()
        val ownerId = UUID.randomUUID()
        val resourceRef =
            ResourceRef(
                resourceType = "shipment",
                resourceId = UUID.randomUUID(),
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
}
