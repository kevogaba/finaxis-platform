package com.finaxis.platform.iam.application.query

import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** Who sees a role's counts (#191): the permission count always, the user count when listable. */
class IamQueryServiceCountVisibilityTests {
    private val queries = FakeIamAdministrationQueries()
    private val permissionGuard = FakePermissionGuard()
    private val service = IamQueryService(queries, queries, queries, queries, permissionGuard)

    private val tenantId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()

    @Test
    fun `role counts show permissions always and users only with a tenant wide assignment view`() {
        val caller = TenantCaller(actorId, tenantId)

        val everything = service.searchRoles(tenantId, RoleFilter(), caller).items.single()
        assertEquals(3, everything.permissionCount)
        assertEquals(2, everything.activeUserCount)

        permissionGuard.visibleOnly(tenantId, "role_assignment.view", UUID.randomUUID())
        val branchScoped = service.searchRoles(tenantId, RoleFilter(), caller).items.single()
        assertEquals(3, branchScoped.permissionCount)
        assertEquals(null, branchScoped.activeUserCount, "a branch-scoped grant lists only some")
    }

    @Test
    fun `a platform caller sees role user counts only with the platform assignment view`() {
        val platformOrganisationId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val caller = PlatformCaller(actorId, platformOrganisationId)

        assertEquals(
            2,
            service
                .searchRoles(tenantId, RoleFilter(), caller)
                .items
                .single()
                .activeUserCount,
        )

        permissionGuard.deny(platformOrganisationId, "role_assignment.view")
        assertEquals(
            null,
            service
                .searchRoles(tenantId, RoleFilter(), caller)
                .items
                .single()
                .activeUserCount,
        )
    }
}
