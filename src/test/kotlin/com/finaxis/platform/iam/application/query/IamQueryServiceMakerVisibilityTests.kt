package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Who is shown as the inviter of a member (#156): a tenant caller sees one of the tenant's own
 * members, never the platform operator who acted for it; a platform caller sees every inviter.
 */
class IamQueryServiceMakerVisibilityTests {
    private val queries = FakeIamAdministrationQueries()
    private val service =
        IamQueryService(queries, queries, queries, queries, FakePermissionGuard())

    private val tenantId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val member = ActorRef(UUID.randomUUID(), "Mary Member", member = true)
    private val operator = ActorRef(UUID.randomUUID(), "Platform Operator", member = false)

    /** The inviter each of the four reads reports to [caller], in a fixed order. */
    private fun inviterSeenBy(caller: FoundationCaller): List<ActorRef?> =
        listOf(
            service
                .searchUsers(tenantId, UserInTenantFilter(), caller)
                .items
                .single()
                .createdBy,
            service.getUserInTenant(tenantId, UUID.randomUUID(), caller).createdBy,
            service
                .searchMemberships(
                    tenantId,
                    MembershipFilter(),
                    caller,
                ).items
                .single()
                .createdBy,
            service.getMembership(tenantId, UUID.randomUUID(), caller).createdBy,
        )

    @Test
    fun `a tenant caller sees a member who invited`() {
        queries.maker = member

        assertEquals(List(4) { member }, inviterSeenBy(TenantCaller(actorId, tenantId)))
    }

    @Test
    fun `a tenant caller is not shown the platform operator who invited`() {
        queries.maker = operator

        assertEquals(List(4) { null }, inviterSeenBy(TenantCaller(actorId, tenantId)))
    }

    @Test
    fun `a platform caller sees the operator and the member alike`() {
        val platform =
            PlatformCaller(actorId, UUID.fromString("00000000-0000-0000-0000-000000000000"))

        queries.maker = operator
        assertEquals(List(4) { operator }, inviterSeenBy(platform))
        queries.maker = member
        assertEquals(List(4) { member }, inviterSeenBy(platform))
    }

    @Test
    fun `no inviter stays none for everyone`() {
        queries.maker = null

        assertEquals(List(4) { null }, inviterSeenBy(TenantCaller(actorId, tenantId)))
    }
}
