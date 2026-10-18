package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.query.BranchAssignmentFilter
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.iam.application.query.RoleFilter
import com.finaxis.platform.iam.application.query.UserInTenantFilter
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.impl.DSL
import org.jooq.impl.DefaultExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Store-level proof of the richer list items (#189) and the role counts (#191) on real rows: the
 * names and dates the summaries embed, what the counts include and exclude, and that a list costs
 * the same number of statements whatever its page size.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqIamSummaryEnrichmentTests(
    private val dsl: DSLContext,
    private val queries: JooqIamAdministrationQueries,
) {
    private val org = insertOrganisation()
    private val westlands = insertBranch(org, "Westlands")
    private val alice = insertUser("alice", "Alice Wanjiru")
    private val bob = insertUser("bob", "Bob Otieno")
    private val carol = insertUser("carol", "Carol Mwangi")
    private val aliceMembership = insertMembership(org, alice, "ACTIVE", "ADMIN", westlands, T1)
    private val bobMembership = insertMembership(org, bob, "ACTIVE", "STAFF", null, T2)
    private val carolMembership = insertMembership(org, carol, "SUSPENDED", "STAFF", null, T3)

    @Test
    fun `tenant user items carry the membership id type and both dates`() {
        val item =
            queries
                .searchUsers(org, UserInTenantFilter(q = "alice"))
                .items
                .single()

        assertEquals(alice, item.id)
        assertEquals(aliceMembership, item.membershipId)
        assertEquals("ADMIN", item.membershipType)
        assertEquals(T0.toInstant(), item.createdAt)
        assertEquals(T1.toInstant(), item.membershipCreatedAt)

        val detail = requireNotNull(queries.findUserInTenant(org, alice))
        assertEquals(aliceMembership, detail.membershipId)
        assertEquals("ADMIN", detail.membershipType)
        assertEquals(T1.toInstant(), detail.membershipCreatedAt)
    }

    @Test
    fun `membership items carry the user names and the primary branch name`() {
        val byId = queries.searchMemberships(org, MembershipFilter()).items.associateBy { it.id }

        val withBranch = requireNotNull(byId[aliceMembership])
        assertEquals("alice-$alice", withBranch.username)
        assertEquals("alice-$alice@t.test", withBranch.email)
        assertEquals("Alice Wanjiru", withBranch.displayName)
        assertEquals("Westlands", withBranch.primaryBranchName)
        assertEquals(T1.toInstant(), withBranch.createdAt)

        val withoutBranch = requireNotNull(byId[bobMembership])
        assertNull(withoutBranch.primaryBranchId)
        assertNull(withoutBranch.primaryBranchName)
        assertEquals(3, byId.size, "the left join must not drop or duplicate memberships")
    }

    @Test
    fun `branch assignment items carry the user and branch names and the assignment date`() {
        val assignmentId = insertAssignment(org, alice, westlands, "ACTIVE", T2)

        val item = queries.searchBranchAssignments(org, BranchAssignmentFilter()).items.single()
        assertEquals(assignmentId, item.id)
        assertEquals("alice-$alice", item.username)
        assertEquals("Alice Wanjiru", item.displayName)
        assertEquals("Westlands", item.branchName)
        assertEquals(T2.toInstant(), item.assignedAt)

        val detail = requireNotNull(queries.findBranchAssignmentById(org, assignmentId))
        assertEquals("Westlands", detail.branchName)
        assertEquals("alice-$alice", detail.username)
        assertEquals(T2.toInstant(), detail.assignedAt)
    }

    @Test
    fun `role counts include granted permissions and distinct active holders only`() {
        val busy = insertRole(org, "BUSY")
        val idle = insertRole(org, "IDLE")
        grant(org, busy, "branch.view", "user.view")
        // alice holds BUSY twice (tenant and branch scope) and counts once; bob counts; carol was
        // revoked; the second role has nothing.
        insertRoleAssignment(org, alice, busy, null, "TENANT", "ACTIVE")
        insertRoleAssignment(org, alice, busy, westlands, "BRANCH", "ACTIVE")
        insertRoleAssignment(org, bob, busy, null, "TENANT", "ACTIVE")
        insertRoleAssignment(org, carol, busy, null, "TENANT", "REVOKED")

        val byId = queries.searchRoles(org, RoleFilter()).items.associateBy { it.id }

        assertEquals(2, byId.getValue(busy).permissionCount)
        assertEquals(2, byId.getValue(busy).activeUserCount)
        assertEquals(0, byId.getValue(idle).permissionCount)
        assertEquals(0, byId.getValue(idle).activeUserCount)
    }

    @Test
    fun `role counts never reach another organisation`() {
        val role = insertRole(org, "SHARED_CODE")
        val otherOrg = insertOrganisation()
        val otherRole = insertRole(otherOrg, "SHARED_CODE")
        val otherUser = insertUser("dave", "Dave Kamau")
        insertMembership(otherOrg, otherUser, "ACTIVE", "STAFF", null, T1)
        grant(otherOrg, otherRole, "branch.view")
        insertRoleAssignment(otherOrg, otherUser, otherRole, null, "TENANT", "ACTIVE")

        val item = queries.searchRoles(org, RoleFilter()).items.single { it.id == role }

        assertEquals(0, item.permissionCount)
        assertEquals(0, item.activeUserCount)
    }

    @Test
    fun `lists cost the same statements whatever the page size`() {
        repeat(6) { insertRole(org, "R$it") }
        insertRoleAssignment(org, alice, insertRole(org, "HELD"), null, "TENANT", "ACTIVE")
        val counter = AtomicInteger()
        val counting =
            JooqIamAdministrationQueries(
                DSL.using(
                    dsl.configuration().derive(
                        DefaultExecuteListenerProvider(
                            object : DefaultExecuteListener() {
                                override fun executeStart(ctx: ExecuteContext) {
                                    counter.incrementAndGet()
                                }
                            },
                        ),
                    ),
                ),
            )

        val lists: Map<String, (Int) -> Unit> =
            mapOf(
                "roles" to { size -> counting.searchRoles(org, RoleFilter(size = size)) },
                "memberships" to { size ->
                    counting.searchMemberships(org, MembershipFilter(size = size))
                },
                "users" to { size -> counting.searchUsers(org, UserInTenantFilter(size = size)) },
                "assignments" to { size ->
                    counting.searchBranchAssignments(org, BranchAssignmentFilter(size = size))
                },
            )
        lists.forEach { (name, run) ->
            counter.set(0)
            run(1)
            val small = counter.get()
            counter.set(0)
            run(50)
            assertEquals(small, counter.get(), "$name list statements, page size 1 vs 50")
        }
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "org-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Summary Org")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, T0)
            .set(ORGANISATION.UPDATED_AT, T0)
            .execute()
        return id
    }

    private fun insertBranch(
        organisationId: UUID,
        name: String,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, name)
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, T0)
            .set(BRANCH.UPDATED_AT, T0)
            .execute()
        return id
    }

    private fun insertUser(
        username: String,
        displayName: String,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "$username-$id")
            .set(USER_ACCOUNT.EMAIL, "$username-$id@t.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, T0)
            .set(USER_ACCOUNT.UPDATED_AT, T0)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
        status: String,
        type: String,
        primaryBranchId: UUID?,
        at: OffsetDateTime,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, type)
            .set(USER_ORGANISATION_MEMBERSHIP.PRIMARY_BRANCH_ID, primaryBranchId)
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, at)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, at)
            .execute()
        return id
    }

    private fun insertAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
        status: String,
        at: OffsetDateTime,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ID, id)
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, "VIEW")
            .set(USER_BRANCH_ASSIGNMENT.STATUS, status)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, at)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, at)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, at)
            .execute()
        return id
    }

    private fun insertRole(
        organisationId: UUID,
        code: String,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(ROLE)
            .set(ROLE.ID, id)
            .set(ROLE.ORGANISATION_ID, organisationId)
            .set(ROLE.ROLE_CODE, code)
            .set(ROLE.ROLE_NAME, code)
            .set(ROLE.SYSTEM_ROLE, false)
            .set(ROLE.STATUS, "ACTIVE")
            .set(ROLE.CREATED_AT, T0)
            .set(ROLE.UPDATED_AT, T0)
            .execute()
        return id
    }

    private fun grant(
        organisationId: UUID,
        roleId: UUID,
        vararg codes: String,
    ) {
        codes.forEach { code ->
            dsl
                .insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
                .set(ROLE_PERMISSION.ROLE_ID, roleId)
                .set(
                    ROLE_PERMISSION.PERMISSION_ID,
                    dsl
                        .select(PERMISSION.ID)
                        .from(PERMISSION)
                        .where(PERMISSION.PERMISSION_CODE.eq(code))
                        .fetchSingle(PERMISSION.ID),
                ).set(ROLE_PERMISSION.GRANTED_AT, T0)
                .set(ROLE_PERMISSION.CREATED_AT, T0)
                .set(ROLE_PERMISSION.UPDATED_AT, T0)
                .execute()
        }
    }

    private fun insertRoleAssignment(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        branchId: UUID?,
        scope: String,
        status: String,
    ) {
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, uuidV7())
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, userId)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, scope)
            .set(USER_ROLE_ASSIGNMENT.STATUS, status)
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, T0)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, T0)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, T0)
            .execute()
    }

    private companion object {
        val T0: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC)
        val T1: OffsetDateTime = T0.plusDays(1)
        val T2: OffsetDateTime = T0.plusDays(2)
        val T3: OffsetDateTime = T0.plusDays(3)
    }
}
