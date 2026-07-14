package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import org.junit.jupiter.api.AfterEach
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises method-security permission checks against the active application principal. */
class MethodSecurityAuthorizerTests {
    private val authorizer = MethodSecurityAuthorizer()
    private val organisationId = UUID.randomUUID()
    private val branchId = UUID.randomUUID()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    /** Grants organisation-scoped permission only in the active organisation. */
    @Test
    fun `organisation permission requires matching organisation and permission code`() {
        installPrincipal(permissionCodes = setOf("branch.create"))

        assertTrue(authorizer.hasPermission(organisationId, "branch.create"))
        assertFalse(authorizer.hasPermission(organisationId, "branch.delete"))
        assertFalse(authorizer.hasPermission(UUID.randomUUID(), "branch.create"))
    }

    /** Grants branch-scoped permission only when organisation and branch context match. */
    @Test
    fun `branch permission requires matching organisation branch and permission code`() {
        installPrincipal(branchId = branchId, permissionCodes = setOf("cashier.open"))

        assertTrue(authorizer.hasPermission(organisationId, branchId, "cashier.open"))
        assertFalse(authorizer.hasPermission(organisationId, branchId, "cashier.close"))
        assertFalse(authorizer.hasPermission(UUID.randomUUID(), branchId, "cashier.open"))
        assertFalse(authorizer.hasPermission(organisationId, UUID.randomUUID(), "cashier.open"))
    }

    /** Denies access when no AppPrincipal is installed in the security context. */
    @Test
    fun `permission checks deny missing or non application principal`() {
        assertFalse(authorizer.hasPermission(organisationId, "branch.create"))
        assertFalse(authorizer.hasPermission(organisationId, branchId, "branch.create"))

        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken("subject", "credentials")

        assertFalse(authorizer.hasPermission(organisationId, "branch.create"))
        assertFalse(authorizer.hasPermission(organisationId, branchId, "branch.create"))
    }

    private fun installPrincipal(
        branchId: UUID? = null,
        permissionCodes: Set<String>,
    ) {
        val principal =
            AppPrincipal(
                userId = UUID.randomUUID(),
                keycloakSubject = "kc-subject",
                organisationId = organisationId,
                membershipId = UUID.randomUUID(),
                branchId = branchId,
                email = "admin@example.test",
                fullName = "Admin User",
                permissions = permissionCodes,
            )
        SecurityContextHolder.getContext().authentication =
            AppPrincipalAuthenticationToken(principal)
    }
}
