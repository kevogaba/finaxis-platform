package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Full-stack proof for ADR 0030 point 5 (rollout step 4): reads of branches, branch assignments
 * and BRANCH-scope role assignments are authorised at the branch they concern. A caller pinned to
 * branch A who holds a view only through a role scoped to branch B can list and get B's
 * resources and sees none of A's; a tenant-wide holder sees all and gets 404 for an unknown id; a
 * branch-scoped holder gets 403 for an unknown id and for any other branch (no existence oracle);
 * and a caller with no grant at all gets 403 on every route.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TargetAwareReadsIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var contextService: ActiveOrganisationContextService

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var cacheManager: CacheManager

    private val createdRoleIds = mutableListOf<UUID>()
    private val withheldPermissionIds = mutableListOf<UUID>()
    private val deniedPermissionIds = mutableListOf<UUID>()

    @BeforeEach
    fun resetSeedState() {
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, "ACTIVE")
            .where(BRANCH.ID.`in`(HEAD_OFFICE_BRANCH_ID, OPERATIONS_BRANCH_ID))
            .execute()
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    @AfterEach
    fun restore() {
        dsl
            .deleteFrom(MEMBERSHIP_PERMISSION)
            .where(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID.eq(MEMBERSHIP_ID))
            .and(MEMBERSHIP_PERMISSION.PERMISSION_ID.`in`(deniedPermissionIds))
            .execute()
        deniedPermissionIds.clear()
        createdRoleIds.forEach { roleId ->
            dsl
                .deleteFrom(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
                .execute()
            dsl.deleteFrom(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId)).execute()
            dsl.deleteFrom(ROLE).where(ROLE.ID.eq(roleId)).execute()
        }
        createdRoleIds.clear()
        val now = OffsetDateTime.now()
        withheldPermissionIds.forEach { permissionId ->
            dsl
                .insertInto(ROLE_PERMISSION)
                .set(ROLE_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
                .set(ROLE_PERMISSION.ROLE_ID, LOCAL_ADMIN_ROLE_ID)
                .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
                .set(ROLE_PERMISSION.GRANTED_AT, now)
                .set(ROLE_PERMISSION.CREATED_AT, now)
                .set(ROLE_PERMISSION.UPDATED_AT, now)
                .onConflictDoNothing()
                .execute()
        }
        withheldPermissionIds.clear()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    @Test
    fun `a caller pinned to one branch lists and gets the branch it holds the view on`() {
        holdViewsOnlyAt(OPERATIONS_BRANCH_ID)
        val pinnedToHeadOffice = contextToken(HEAD_OFFICE_BRANCH_ID)

        get(ApiPaths.BRANCHES, pinnedToHeadOffice).andExpect {
            status { isOk() }
            jsonPath("$.page.total_items") { value(1) }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(OPERATIONS_BRANCH_ID.toString()) }
        }
        get("${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID", pinnedToHeadOffice).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(OPERATIONS_BRANCH_ID.toString()) }
        }
        // The pinned branch itself is visible only if the view was granted on it.
        get("${ApiPaths.BRANCHES}/$HEAD_OFFICE_BRANCH_ID", pinnedToHeadOffice)
            .andExpect { status { isForbidden() } }
        // An unknown id answers exactly as a branch the caller cannot see: no existence oracle.
        get("${ApiPaths.BRANCHES}/${uuidV7()}", pinnedToHeadOffice)
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `branch list restriction applies in the query so totals and pages stay correct`() {
        holdViewsOnlyAt(OPERATIONS_BRANCH_ID)
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)

        get(ApiPaths.BRANCHES, pinned, "size" to "1", "page" to "1").andExpect {
            status { isOk() }
            jsonPath("$.page.total_items") { value(1) }
            jsonPath("$.page.total_pages") { value(1) }
            jsonPath("$.items.length()") { value(0) }
        }
        get(ApiPaths.BRANCHES, pinned, "q" to "no-such-branch-code").andExpect {
            status { isOk() }
            jsonPath("$.page.total_items") { value(0) }
        }
        get(ApiPaths.BRANCHES, pinned, "size" to "0")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a tenant wide holder sees every branch and gets not found for an unknown id`() {
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)

        get(ApiPaths.BRANCHES, pinned).andExpect {
            status { isOk() }
            jsonPath("$.items[*].id") {
                value(hasItem(HEAD_OFFICE_BRANCH_ID.toString()))
                value(hasItem(OPERATIONS_BRANCH_ID.toString()))
            }
        }
        get("${ApiPaths.BRANCHES}/$HEAD_OFFICE_BRANCH_ID", pinned)
            .andExpect { status { isOk() } }
        get("${ApiPaths.BRANCHES}/${uuidV7()}", pinned).andExpect { status { isNotFound() } }
        get("${ApiPaths.BRANCH_ASSIGNMENTS}/${uuidV7()}", pinned)
            .andExpect { status { isNotFound() } }
        get("${ApiPaths.ROLE_ASSIGNMENTS}/${uuidV7()}", pinned)
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `branch assignments are listed and read at the branch the view is held on`() {
        holdViewsOnlyAt(OPERATIONS_BRANCH_ID)
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)
        val opsAssignment = branchAssignmentId(OPERATIONS_BRANCH_ID)
        val headOfficeAssignment = branchAssignmentId(HEAD_OFFICE_BRANCH_ID)

        // No branch_id: every visible branch, not the pinned (invisible) one.
        get(ApiPaths.BRANCH_ASSIGNMENTS, pinned).andExpect {
            status { isOk() }
            jsonPath("$.items[*].branch_id") {
                value(everyItem(equalTo(OPERATIONS_BRANCH_ID.toString())))
            }
            jsonPath("$.items[*].id") { value(hasItem(opsAssignment.toString())) }
            jsonPath("$.page.total_items") { value(1) }
        }
        get(ApiPaths.BRANCH_ASSIGNMENTS, pinned, "branch_id" to OPERATIONS_BRANCH_ID.toString())
            .andExpect {
                status { isOk() }
                jsonPath("$.page.total_items") { value(1) }
            }
        get(ApiPaths.BRANCH_ASSIGNMENTS, pinned, "branch_id" to HEAD_OFFICE_BRANCH_ID.toString())
            .andExpect { status { isForbidden() } }
        get("${ApiPaths.BRANCH_ASSIGNMENTS}/$opsAssignment", pinned).andExpect {
            status { isOk() }
            jsonPath("$.branch_id") { value(OPERATIONS_BRANCH_ID.toString()) }
        }
        get("${ApiPaths.BRANCH_ASSIGNMENTS}/$headOfficeAssignment", pinned)
            .andExpect { status { isForbidden() } }
        get("${ApiPaths.BRANCH_ASSIGNMENTS}/${uuidV7()}", pinned)
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `branch scope role assignments are read at their branch and tenant rows are not`() {
        holdViewsOnlyAt(OPERATIONS_BRANCH_ID)
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)
        val opsRow = roleAssignmentId(OPERATIONS_BRANCH_ID)
        val tenantRow = tenantRoleAssignmentId()
        grantRole("hq-noise", setOf("branch.suspend"), HEAD_OFFICE_BRANCH_ID)
        val headOfficeRow = roleAssignmentId(HEAD_OFFICE_BRANCH_ID)

        get(ApiPaths.ROLE_ASSIGNMENTS, pinned).andExpect {
            status { isOk() }
            jsonPath("$.page.total_items") {
                value(branchRoleAssignmentCount(OPERATIONS_BRANCH_ID))
            }
            jsonPath("$.items[*].scope_type") { value(everyItem(equalTo("BRANCH"))) }
            jsonPath("$.items[*].branch_id") {
                value(everyItem(equalTo(OPERATIONS_BRANCH_ID.toString())))
            }
            jsonPath("$.items[*].id") {
                value(hasItem(opsRow.toString()))
                value(not(hasItem(tenantRow.toString())))
                value(not(hasItem(headOfficeRow.toString())))
            }
        }
        get(ApiPaths.ROLE_ASSIGNMENTS, pinned, "branch_id" to HEAD_OFFICE_BRANCH_ID.toString())
            .andExpect { status { isForbidden() } }
        // scope_type is matched like every enum-like filter (exact value, otherwise an empty page),
        // and the restriction to BRANCH scope is applied in the same query, so TENANT is empty.
        listOf("TENANT", "tenant", "NO_SUCH_SCOPE").forEach { scope ->
            get(ApiPaths.ROLE_ASSIGNMENTS, pinned, "scope_type" to scope).andExpect {
                status { isOk() }
                jsonPath("$.page.total_items") { value(0) }
            }
        }
        get("${ApiPaths.ROLE_ASSIGNMENTS}/$opsRow", pinned).andExpect {
            status { isOk() }
            jsonPath("$.branch_id") { value(OPERATIONS_BRANCH_ID.toString()) }
        }
        get("${ApiPaths.ROLE_ASSIGNMENTS}/$tenantRow", pinned)
            .andExpect { status { isForbidden() } }
        get("${ApiPaths.ROLE_ASSIGNMENTS}/$headOfficeRow", pinned)
            .andExpect { status { isForbidden() } }
        get("${ApiPaths.ROLE_ASSIGNMENTS}/${uuidV7()}", pinned)
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a caller with no grant at all is forbidden on every read route`() {
        withholdFromLocalAdmin(*VIEW_CODES)
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)

        readRoutes().forEach { route ->
            get(route, pinned).andExpect { status { isForbidden() } }
        }
    }

    @Test
    fun `an unauthenticated caller is unauthorised on every read route`() {
        readRoutes().forEach { route ->
            mockMvc.get(route).andExpect { status { isUnauthorized() } }
        }
    }

    @Test
    fun `a direct deny removes the view on every branch`() {
        holdViewsOnlyAt(OPERATIONS_BRANCH_ID)
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)
        denyDirectly(*VIEW_CODES)

        readRoutes().forEach { route -> get(route, pinned).andExpect { status { isForbidden() } } }
        get("${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID", pinned)
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `a direct deny also removes a tenant wide view`() {
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)
        denyDirectly("branch.view")

        get(ApiPaths.BRANCHES, pinned).andExpect { status { isForbidden() } }
        get("${ApiPaths.BRANCHES}/$HEAD_OFFICE_BRANCH_ID", pinned)
            .andExpect { status { isForbidden() } }
        // The other two views are untouched.
        get(ApiPaths.BRANCH_ASSIGNMENTS, pinned).andExpect { status { isOk() } }
    }

    /**
     * Reads feed the revoke pre-read, so the target-aware view also relaxes this mutation: a
     * branch-scoped revoker needs `role_assignment.view` at the row's branch, not tenant-wide.
     * It still needs `user.revoke_role` at that branch, and never touches a TENANT-scope row.
     */
    @Test
    fun `a branch scoped revoker revokes at its branch and only there`() {
        withholdFromLocalAdmin("user.revoke_role", "role_assignment.view")
        val revokerCodes = setOf("user.revoke_role", "role_assignment.view")
        grantRole("revoker", revokerCodes, OPERATIONS_BRANCH_ID)
        val targetCodes = setOf("branch.suspend")
        val opsTarget = assignmentOf(grantRole("ops-target", targetCodes, OPERATIONS_BRANCH_ID))
        val hqTarget = assignmentOf(grantRole("hq-target", targetCodes, HEAD_OFFICE_BRANCH_ID))
        val tenantRow = tenantRoleAssignmentId()
        val pinnedToOps = contextToken(OPERATIONS_BRANCH_ID)

        listOf(hqTarget, tenantRow).forEach { refused ->
            revoke(refused, pinnedToOps).andExpect { status { isForbidden() } }
            org.assertj.core.api.Assertions
                .assertThat(roleAssignmentStatus(refused))
                .isEqualTo("ACTIVE")
        }
        revoke(opsTarget, pinnedToOps).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("REVOKED") }
        }
        org.assertj.core.api.Assertions
            .assertThat(roleAssignmentStatus(opsTarget))
            .isEqualTo("REVOKED")
    }

    private fun revoke(
        assignmentId: UUID,
        contextToken: String,
    ): ResultActionsDsl =
        mockMvc.delete("${ApiPaths.ROLE_ASSIGNMENTS}/$assignmentId") {
            with(jwt().jwt { token -> token.subject(USER_ID.toString()) })
            header(ActiveOrganisationContextService.HEADER, contextToken)
        }

    private fun roleAssignmentStatus(assignmentId: UUID): String? =
        dsl.fetchValue(USER_ROLE_ASSIGNMENT.STATUS, USER_ROLE_ASSIGNMENT.ID.eq(assignmentId))

    private fun branchRoleAssignmentCount(branchId: UUID): Int =
        dsl.fetchCount(
            USER_ROLE_ASSIGNMENT,
            USER_ROLE_ASSIGNMENT.ORGANISATION_ID
                .eq(ORGANISATION_ID)
                .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq("BRANCH"))
                .and(USER_ROLE_ASSIGNMENT.BRANCH_ID.eq(branchId)),
        )

    private fun assignmentOf(roleId: UUID): UUID =
        requireNotNull(
            dsl.fetchValue(USER_ROLE_ASSIGNMENT.ID, USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId)),
        )

    private fun denyDirectly(vararg codes: String) {
        val now = OffsetDateTime.now()
        dsl
            .select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.`in`(codes.toList()))
            .fetch(PERMISSION.ID)
            .filterNotNull()
            .forEach { permissionId ->
                deniedPermissionIds += permissionId
                dsl
                    .insertInto(MEMBERSHIP_PERMISSION)
                    .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
                    .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, MEMBERSHIP_ID)
                    .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
                    .set(MEMBERSHIP_PERMISSION.EFFECT, "DENY")
                    .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
                    .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
                    .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
                    .execute()
            }
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    private fun readRoutes(): List<String> =
        listOf(
            ApiPaths.BRANCHES,
            "${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID",
            ApiPaths.BRANCH_ASSIGNMENTS,
            "${ApiPaths.BRANCH_ASSIGNMENTS}/${uuidV7()}",
            ApiPaths.ROLE_ASSIGNMENTS,
            "${ApiPaths.ROLE_ASSIGNMENTS}/${uuidV7()}",
        )

    private fun get(
        path: String,
        contextToken: String,
        vararg params: Pair<String, String>,
    ): ResultActionsDsl =
        mockMvc.get(path) {
            with(jwt().jwt { token -> token.subject(USER_ID.toString()) })
            header(ActiveOrganisationContextService.HEADER, contextToken)
            params.forEach { (name, value) -> param(name, value) }
        }

    /** Leaves the caller holding the three views only through a role scoped to [branchId]. */
    private fun holdViewsOnlyAt(branchId: UUID) {
        withholdFromLocalAdmin(*VIEW_CODES)
        grantRole("branch-viewer", VIEW_CODES.toSet(), branchId)
    }

    private fun withholdFromLocalAdmin(vararg codes: String) {
        val permissionIds =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.`in`(codes.toList()))
                .fetch(PERMISSION.ID)
                .filterNotNull()
        withheldPermissionIds += permissionIds
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ROLE_ID.eq(LOCAL_ADMIN_ROLE_ID))
            .and(ROLE_PERMISSION.PERMISSION_ID.`in`(permissionIds))
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    private fun branchAssignmentId(branchId: UUID): UUID =
        requireNotNull(
            dsl
                .select(USER_BRANCH_ASSIGNMENT.ID)
                .from(USER_BRANCH_ASSIGNMENT)
                .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
                .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(branchId))
                .orderBy(USER_BRANCH_ASSIGNMENT.ID)
                .limit(1)
                .fetchOne(USER_BRANCH_ASSIGNMENT.ID),
        )

    private fun roleAssignmentId(branchId: UUID): UUID =
        requireNotNull(
            dsl
                .select(USER_ROLE_ASSIGNMENT.ID)
                .from(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
                .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq("BRANCH"))
                .and(USER_ROLE_ASSIGNMENT.BRANCH_ID.eq(branchId))
                .and(USER_ROLE_ASSIGNMENT.ROLE_ID.`in`(createdRoleIds))
                .orderBy(USER_ROLE_ASSIGNMENT.ID)
                .limit(1)
                .fetchOne(USER_ROLE_ASSIGNMENT.ID),
        )

    private fun tenantRoleAssignmentId(): UUID =
        requireNotNull(
            dsl
                .select(USER_ROLE_ASSIGNMENT.ID)
                .from(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
                .and(USER_ROLE_ASSIGNMENT.SCOPE_TYPE.eq("TENANT"))
                .orderBy(USER_ROLE_ASSIGNMENT.ID)
                .limit(1)
                .fetchOne(USER_ROLE_ASSIGNMENT.ID),
        )

    private fun grantRole(
        code: String,
        permissionCodes: Set<String>,
        branchId: UUID,
    ): UUID {
        val now = OffsetDateTime.now()
        val roleId =
            requireNotNull(
                dsl
                    .insertInto(ROLE)
                    .set(ROLE.ORGANISATION_ID, ORGANISATION_ID)
                    .set(ROLE.ROLE_CODE, "$code-${uuidV7().toString().takeLast(SHORT_ID)}")
                    .set(ROLE.ROLE_NAME, code)
                    .set(ROLE.STATUS, "ACTIVE")
                    .set(ROLE.CREATED_AT, now)
                    .set(ROLE.UPDATED_AT, now)
                    .returning(ROLE.ID)
                    .fetchOne()
                    ?.id,
            )
        createdRoleIds += roleId
        dsl
            .select(PERMISSION.ID)
            .from(PERMISSION)
            .where(PERMISSION.PERMISSION_CODE.`in`(permissionCodes))
            .fetch(PERMISSION.ID)
            .forEach { permissionId ->
                dsl
                    .insertInto(ROLE_PERMISSION)
                    .set(ROLE_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
                    .set(ROLE_PERMISSION.ROLE_ID, roleId)
                    .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
                    .set(ROLE_PERMISSION.GRANTED_AT, now)
                    .set(ROLE_PERMISSION.CREATED_AT, now)
                    .set(ROLE_PERMISSION.UPDATED_AT, now)
                    .execute()
            }
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, ORGANISATION_ID)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, USER_ID)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, "BRANCH")
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
        return roleId
    }

    private fun contextToken(branchId: UUID?): String =
        contextService.issue(
            ActiveOrganisationContext(USER_ID, ORGANISATION_ID, MEMBERSHIP_ID, branchId),
        )

    private companion object {
        const val SHORT_ID = 8
        val VIEW_CODES = arrayOf("branch.view", "branch_assignment.view", "role_assignment.view")
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val OPERATIONS_BRANCH_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val LOCAL_ADMIN_ROLE_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
    }
}
