package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.BRANCH
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import kotlin.test.assertEquals

/**
 * Full-stack proof for issue #242: a branch-scope grant confers visibility of a branch **only
 * while that branch is ACTIVE**. A caller holding the three branch views through roles scoped to
 * the head office and to the operations branch stops seeing the operations branch, its branch
 * assignments and its role assignments once it is suspended or closed (403 by id, as for any
 * branch it cannot see, and absent from every list), keeps seeing the head office, and sees the
 * operations branch again once it is reactivated. A tenant-wide holder sees every branch whatever
 * its status. Because a mutation implies its view at the same branch (ADR 0030, decision 4), a
 * branch-scoped holder may suspend its ACTIVE branch and read it back, but can no longer act on
 * it once it is suspended.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class NonActiveBranchVisibilityIntegrationTests {
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

    @BeforeEach
    fun activateSeedBranches() {
        setStatus(OPERATIONS_BRANCH_ID, "ACTIVE")
        setStatus(HEAD_OFFICE_BRANCH_ID, "ACTIVE")
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
    }

    @AfterEach
    fun restore() {
        createdRoleIds.forEach { roleId ->
            dsl
                .deleteFrom(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
                .execute()
            dsl.deleteFrom(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId)).execute()
            dsl.deleteFrom(ROLE).where(ROLE.ID.eq(roleId)).execute()
        }
        createdRoleIds.clear()
        restoreLocalAdmin()
        activateSeedBranches()
    }

    @Test
    fun `a branch scoped holder suspends its branch reads it back and then no longer sees it`() {
        holdViewsOnlyAtBothBranches()
        val pinned = contextToken()
        val opsAssignment = branchAssignmentId(OPERATIONS_BRANCH_ID)
        val opsRoleRow = roleAssignmentId(OPERATIONS_BRANCH_ID)

        // The suspend's read-back decides from the answer its pre-check made, while ACTIVE.
        post("suspend", pinned).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUSPENDED") }
        }

        assertOnlyHeadOfficeVisible(pinned, opsAssignment, opsRoleRow)
    }

    @ParameterizedTest
    @ValueSource(strings = ["SUSPENDED", "CLOSED"])
    fun `a branch scoped holder sees no branch that is not ACTIVE`(status: String) {
        holdViewsOnlyAtBothBranches()
        val pinned = contextToken()
        val opsAssignment = branchAssignmentId(OPERATIONS_BRANCH_ID)
        val opsRoleRow = roleAssignmentId(OPERATIONS_BRANCH_ID)
        setStatus(OPERATIONS_BRANCH_ID, status)

        assertOnlyHeadOfficeVisible(pinned, opsAssignment, opsRoleRow)
    }

    @Test
    fun `a tenant wide holder still sees a suspended branch and everything on it`() {
        grantRole("ops-row", setOf("branch.view"), OPERATIONS_BRANCH_ID)
        val pinned = contextToken()
        val opsAssignment = branchAssignmentId(OPERATIONS_BRANCH_ID)
        val opsRoleRow = roleAssignmentId(OPERATIONS_BRANCH_ID)
        post("suspend", pinned).andExpect { status { isOk() } }

        get(ApiPaths.BRANCHES, pinned).andExpect {
            status { isOk() }
            jsonPath("$.items[*].id") { value(hasItem(OPERATIONS_BRANCH_ID.toString())) }
        }
        get("${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID", pinned).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SUSPENDED") }
        }
        get(ApiPaths.BRANCH_ASSIGNMENTS, pinned, "branch_id" to OPERATIONS_BRANCH_ID.toString())
            .andExpect { status { isOk() } }
        get("${ApiPaths.BRANCH_ASSIGNMENTS}/$opsAssignment", pinned)
            .andExpect { status { isOk() } }
        get("${ApiPaths.ROLE_ASSIGNMENTS}/$opsRoleRow", pinned).andExpect { status { isOk() } }
        get("${ApiPaths.BRANCHES}/${uuidV7()}", pinned).andExpect { status { isNotFound() } }
    }

    @Test
    fun `a reactivated branch is visible again to its branch scoped holder`() {
        holdViewsOnlyAtBothBranches()
        val pinned = contextToken()
        post("suspend", pinned).andExpect { status { isOk() } }
        get("${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID", pinned)
            .andExpect { status { isForbidden() } }

        // The scoped holder no longer holds branch.view there, so it cannot reactivate it either.
        post("reactivate", pinned).andExpect {
            status { isForbidden() }
            jsonPath("$.detail") { value("Missing permission: branch.view.") }
        }
        assertEquals("SUSPENDED", status(OPERATIONS_BRANCH_ID))

        // A tenant-wide holder reactivates it, and the scoped holder sees it again.
        restoreLocalAdmin()
        post("reactivate", pinned).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACTIVE") }
        }
        withholdViewsFromLocalAdmin()
        get("${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID", pinned).andExpect { status { isOk() } }
        get(ApiPaths.BRANCHES, pinned).andExpect {
            status { isOk() }
            jsonPath("$.page.total_items") { value(2) }
        }
    }

    private fun assertOnlyHeadOfficeVisible(
        pinned: String,
        opsAssignment: UUID,
        opsRoleRow: UUID,
    ) {
        get(ApiPaths.BRANCHES, pinned).andExpect {
            status { isOk() }
            jsonPath("$.page.total_items") { value(1) }
            jsonPath("$.items[0].id") { value(HEAD_OFFICE_BRANCH_ID.toString()) }
        }
        get("${ApiPaths.BRANCHES}/$HEAD_OFFICE_BRANCH_ID", pinned).andExpect { status { isOk() } }
        // 403, never 404: exactly as for a branch the caller holds nothing on.
        get("${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID", pinned)
            .andExpect { status { isForbidden() } }
        get("${ApiPaths.BRANCHES}/${uuidV7()}", pinned).andExpect { status { isForbidden() } }
        get(ApiPaths.BRANCH_ASSIGNMENTS, pinned).andExpect {
            status { isOk() }
            jsonPath("$.items[*].branch_id") {
                value(everyItem(equalTo(HEAD_OFFICE_BRANCH_ID.toString())))
            }
        }
        get(ApiPaths.BRANCH_ASSIGNMENTS, pinned, "branch_id" to OPERATIONS_BRANCH_ID.toString())
            .andExpect { status { isForbidden() } }
        get("${ApiPaths.BRANCH_ASSIGNMENTS}/$opsAssignment", pinned)
            .andExpect { status { isForbidden() } }
        get(ApiPaths.ROLE_ASSIGNMENTS, pinned).andExpect {
            status { isOk() }
            jsonPath("$.items[*].branch_id") {
                value(everyItem(equalTo(HEAD_OFFICE_BRANCH_ID.toString())))
            }
            jsonPath("$.items[*].id") { value(not(hasItem(opsRoleRow.toString()))) }
        }
        get(ApiPaths.ROLE_ASSIGNMENTS, pinned, "branch_id" to OPERATIONS_BRANCH_ID.toString())
            .andExpect { status { isForbidden() } }
        get("${ApiPaths.ROLE_ASSIGNMENTS}/$opsRoleRow", pinned)
            .andExpect { status { isForbidden() } }
    }

    /** Leaves the caller holding the three views only through roles scoped to both branches. */
    private fun holdViewsOnlyAtBothBranches() {
        withholdViewsFromLocalAdmin()
        grantRole("hq-viewer", VIEW_CODES, HEAD_OFFICE_BRANCH_ID)
        grantRole("ops-viewer", VIEW_CODES, OPERATIONS_BRANCH_ID)
    }

    private fun withholdViewsFromLocalAdmin() {
        val permissionIds =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.`in`(VIEW_CODES))
                .fetch(PERMISSION.ID)
                .filterNotNull()
        withheldPermissionIds += permissionIds
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ROLE_ID.eq(LOCAL_ADMIN_ROLE_ID))
            .and(ROLE_PERMISSION.PERMISSION_ID.`in`(permissionIds))
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
    }

    private fun restoreLocalAdmin() {
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
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
    }

    private fun grantRole(
        code: String,
        permissionCodes: Set<String>,
        branchId: UUID,
    ) {
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
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
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
                .where(USER_ROLE_ASSIGNMENT.BRANCH_ID.eq(branchId))
                .and(USER_ROLE_ASSIGNMENT.ROLE_ID.`in`(createdRoleIds))
                .limit(1)
                .fetchOne(USER_ROLE_ASSIGNMENT.ID),
        )

    private fun setStatus(
        branchId: UUID,
        status: String,
    ) {
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, status)
            .where(BRANCH.ID.eq(branchId))
            .execute()
    }

    private fun status(branchId: UUID): String? =
        dsl.fetchValue(BRANCH.STATUS, BRANCH.ID.eq(branchId))

    private fun post(
        action: String,
        contextToken: String,
    ): ResultActionsDsl =
        mockMvc.post("${ApiPaths.BRANCHES}/$OPERATIONS_BRANCH_ID/$action") {
            with(jwt().jwt { token -> token.subject(USER_ID.toString()) })
            header(ActiveOrganisationContextService.HEADER, contextToken)
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"Issue 242 visibility check"}"""
        }

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

    private fun contextToken(): String =
        contextService.issue(
            ActiveOrganisationContext(
                USER_ID,
                ORGANISATION_ID,
                MEMBERSHIP_ID,
                HEAD_OFFICE_BRANCH_ID,
            ),
        )

    private companion object {
        const val SHORT_ID = 8
        val VIEW_CODES = setOf("branch.view", "branch_assignment.view", "role_assignment.view")
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val OPERATIONS_BRANCH_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val LOCAL_ADMIN_ROLE_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
    }
}
