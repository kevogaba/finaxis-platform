package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Full-stack proof for issue #170: duplicate assignment rows never block the automatic branch
 * choice or inflate the discovery lists, and `/auth/me` lists only ACTIVE branches and roles.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ContextDiscoveryIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var contextService: ActiveOrganisationContextService

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var cacheManager: CacheManager

    private val createdAssignmentIds = mutableListOf<UUID>()
    private val createdRoleAssignmentIds = mutableListOf<UUID>()
    private val createdRoleIds = mutableListOf<UUID>()
    private var withheldPermissionId: UUID? = null

    @BeforeEach
    fun resetSeedState() {
        // Other integration classes share this database and leave the seed mutated.
        restoreSeedBranches()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
    }

    @AfterEach
    fun removeCreatedRows() {
        createdAssignmentIds.forEach { id ->
            dsl.deleteFrom(USER_BRANCH_ASSIGNMENT).where(USER_BRANCH_ASSIGNMENT.ID.eq(id)).execute()
        }
        createdAssignmentIds.clear()
        // Only the rows this test inserted are deleted; the seed assignments are left alone.
        createdRoleAssignmentIds.forEach { id ->
            dsl.deleteFrom(USER_ROLE_ASSIGNMENT).where(USER_ROLE_ASSIGNMENT.ID.eq(id)).execute()
        }
        createdRoleAssignmentIds.clear()
        createdRoleIds.forEach { roleId ->
            dsl
                .deleteFrom(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
                .execute()
            dsl.deleteFrom(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId)).execute()
            dsl.deleteFrom(ROLE).where(ROLE.ID.eq(roleId)).execute()
        }
        createdRoleIds.clear()
        restoreWithheldPermission()
        restoreSeedBranches()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
    }

    @Test
    fun `a branch assigned twice is still auto selected and listed once`() {
        deactivateAssignment(OPERATIONS_BRANCH_ID)
        // HOME is the seed row; a second ACTIVE row of another type is a legal duplicate.
        addBranchAssignment(HEAD_OFFICE_BRANCH_ID, "OPERATE")
        addBranchAssignment(HEAD_OFFICE_BRANCH_ID, "VIEW")

        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisation_id":"$ORGANISATION_ID"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.branch_id") { value(HEAD_OFFICE_BRANCH_ID.toString()) }
                jsonPath("$.requires_branch_selection") { value(false) }
                jsonPath(
                    "$.assigned_branch_ids",
                ) { value(contains(HEAD_OFFICE_BRANCH_ID.toString())) }
            }

        mockMvc
            .get("/api/v1/auth/branches") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, contextToken(null))
            }.andExpect {
                status { isOk() }
                jsonPath(
                    "$.items[*].branch_id",
                ) { value(contains(HEAD_OFFICE_BRANCH_ID.toString())) }
                jsonPath("$.page.total_items") { value(1) }
            }
    }

    @Test
    fun `duplicates do not hide a genuine multi branch choice`() {
        addBranchAssignment(HEAD_OFFICE_BRANCH_ID, "OPERATE")
        addBranchAssignment(OPERATIONS_BRANCH_ID, "VIEW")

        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisation_id":"$ORGANISATION_ID"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.branch_id") { value(null) }
                jsonPath("$.requires_branch_selection") { value(true) }
                jsonPath("$.assigned_branch_ids") {
                    value(
                        containsInAnyOrder(
                            HEAD_OFFICE_BRANCH_ID.toString(),
                            OPERATIONS_BRANCH_ID.toString(),
                        ),
                    )
                }
            }

        mockMvc
            .get("/api/v1/auth/branches") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, contextToken(null))
            }.andExpect {
                status { isOk() }
                jsonPath("$.page.total_items") { value(2) }
            }
    }

    @Test
    fun `a suspended branch is not a choice and the other branch is still auto selected`() {
        setBranchStatus(OPERATIONS_BRANCH_ID, "SUSPENDED")

        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisation_id":"$ORGANISATION_ID"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.branch_id") { value(HEAD_OFFICE_BRANCH_ID.toString()) }
                jsonPath("$.requires_branch_selection") { value(false) }
            }

        profile(HEAD_OFFICE_BRANCH_ID).andExpect {
            jsonPath("$.branches[*].id") { value(contains(HEAD_OFFICE_BRANCH_ID.toString())) }
            jsonPath("$.selected_branch.id") { value(HEAD_OFFICE_BRANCH_ID.toString()) }
        }
    }

    @Test
    fun `a context pinned to a branch that is later suspended is refused`() {
        // The pin was valid when issued; the branch is suspended afterwards.
        val token = contextToken(OPERATIONS_BRANCH_ID)
        setBranchStatus(OPERATIONS_BRANCH_ID, "SUSPENDED")

        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, token)
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("invalid_active_tenant_context") }
            }
    }

    @Test
    fun `a closed branch is absent from the profile`() {
        setBranchStatus(OPERATIONS_BRANCH_ID, "CLOSED")

        profile(HEAD_OFFICE_BRANCH_ID).andExpect {
            jsonPath("$.branches[*].id") { value(contains(HEAD_OFFICE_BRANCH_ID.toString())) }
        }
    }

    @Test
    fun `a role assigned at tenant and branch scope is listed once`() {
        createdRoleAssignmentIds +=
            requireNotNull(
                dsl
                    .insertInto(USER_ROLE_ASSIGNMENT)
                    .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, ORGANISATION_ID)
                    .set(USER_ROLE_ASSIGNMENT.USER_ID, USER_ID)
                    .set(USER_ROLE_ASSIGNMENT.ROLE_ID, LOCAL_ADMIN_ROLE_ID)
                    .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, HEAD_OFFICE_BRANCH_ID)
                    .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, "BRANCH")
                    .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
                    .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, OffsetDateTime.now())
                    .set(USER_ROLE_ASSIGNMENT.CREATED_AT, OffsetDateTime.now())
                    .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, OffsetDateTime.now())
                    .returning(USER_ROLE_ASSIGNMENT.ID)
                    .fetchOne()
                    ?.id,
            )

        profile(HEAD_OFFICE_BRANCH_ID).andExpect {
            jsonPath("$.roles[*].code") { value(contains("local-admin")) }
        }
    }

    @Test
    fun `a disabled role is neither listed nor contributes permissions`() {
        // Tenant administrators hold everything, so the probe code is taken off local-admin and
        // given only to a second role; the active run proves the probe is visible at all.
        withholdFromLocalAdmin(PROBE_PERMISSION)
        val roleId = createRoleWithPermission("probe-role", PROBE_PERMISSION, "ACTIVE")
        profile(HEAD_OFFICE_BRANCH_ID).andExpect {
            jsonPath("$.roles[*].code") { value(hasItem(startsWith("probe-role"))) }
            jsonPath("$.permissions") { value(hasItem(PROBE_PERMISSION)) }
        }

        dsl
            .update(ROLE)
            .set(ROLE.STATUS, "DISABLED")
            .where(ROLE.ID.eq(roleId))
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()

        profile(HEAD_OFFICE_BRANCH_ID).andExpect {
            jsonPath("$.roles[*].code") { value(contains("local-admin")) }
            jsonPath("$.roles[*].status") { value(not(hasItem("DISABLED"))) }
            jsonPath("$.permissions") { value(not(hasItem(PROBE_PERMISSION))) }
        }
    }

    private fun profile(branchId: UUID?): ResultActionsDsl =
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, contextToken(branchId))
            }.andExpect { status { isOk() } }

    private fun setBranchStatus(
        branchId: UUID,
        status: String,
    ) {
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, status)
            .where(BRANCH.ID.eq(branchId))
            .execute()
    }

    /** Puts the seeded branches and the seed user's assignments on them back to ACTIVE. */
    private fun restoreSeedBranches() {
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, "ACTIVE")
            .where(BRANCH.ID.`in`(HEAD_OFFICE_BRANCH_ID, OPERATIONS_BRANCH_ID))
            .execute()
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
            .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(USER_ID))
            .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.`in`(HEAD_OFFICE_BRANCH_ID, OPERATIONS_BRANCH_ID))
            .execute()
    }

    private fun deactivateAssignment(branchId: UUID) {
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "INACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
            .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(branchId))
            .execute()
    }

    private fun addBranchAssignment(
        branchId: UUID,
        type: String,
    ) {
        val now = OffsetDateTime.now()
        val id =
            requireNotNull(
                dsl
                    .insertInto(USER_BRANCH_ASSIGNMENT)
                    .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, ORGANISATION_ID)
                    .set(USER_BRANCH_ASSIGNMENT.USER_ID, USER_ID)
                    .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
                    .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, type)
                    .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
                    .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, now)
                    .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, now)
                    .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
                    .returning(USER_BRANCH_ASSIGNMENT.ID)
                    .fetchOne()
                    ?.id,
            )
        createdAssignmentIds += id
    }

    private fun withholdFromLocalAdmin(code: String) {
        val permissionId =
            requireNotNull(
                dsl
                    .select(PERMISSION.ID)
                    .from(PERMISSION)
                    .where(PERMISSION.PERMISSION_CODE.eq(code))
                    .fetchOne(PERMISSION.ID),
            )
        withheldPermissionId = permissionId
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ROLE_ID.eq(LOCAL_ADMIN_ROLE_ID))
            .and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId))
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
    }

    private fun restoreWithheldPermission() {
        val permissionId = withheldPermissionId ?: return
        val now = OffsetDateTime.now()
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
        withheldPermissionId = null
    }

    private fun createRoleWithPermission(
        code: String,
        permissionCode: String,
        status: String,
    ): UUID {
        val now = OffsetDateTime.now()
        val roleId =
            requireNotNull(
                dsl
                    .insertInto(ROLE)
                    .set(ROLE.ORGANISATION_ID, ORGANISATION_ID)
                    .set(ROLE.ROLE_CODE, "$code-${uuidV7().toString().takeLast(SHORT_ID)}")
                    .set(ROLE.ROLE_NAME, code)
                    .set(ROLE.STATUS, status)
                    .set(ROLE.CREATED_AT, now)
                    .set(ROLE.UPDATED_AT, now)
                    .returning(ROLE.ID)
                    .fetchOne()
                    ?.id,
            )
        createdRoleIds += roleId
        val permissionId =
            requireNotNull(
                dsl
                    .select(PERMISSION.ID)
                    .from(PERMISSION)
                    .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                    .fetchOne(PERMISSION.ID),
            )
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .execute()
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, ORGANISATION_ID)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, USER_ID)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, "TENANT")
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
        return roleId
    }

    private fun contextToken(branchId: UUID?): String =
        contextService.issue(
            ActiveOrganisationContext(USER_ID, ORGANISATION_ID, MEMBERSHIP_ID, branchId),
        )

    private fun localJwt() = jwt().jwt { token -> token.subject(USER_ID.toString()) }

    private companion object {
        const val SHORT_ID = 8
        const val PROBE_PERMISSION = "branch.suspend"
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val OPERATIONS_BRANCH_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val LOCAL_ADMIN_ROLE_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
    }
}
