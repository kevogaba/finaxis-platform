package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
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
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Full-stack proof for issue #154: a selected branch narrows operational authority but no longer
 * hides tenant administration of other branches, and any user can clear the pin.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class BranchPinningIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var contextService: ActiveOrganisationContextService

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var cacheManager: CacheManager

    private val createdRoleIds = mutableListOf<UUID>()
    private val createdBranchIds = mutableListOf<UUID>()

    @BeforeEach
    fun resetSeedState() {
        // Other integration classes share this database and leave the seed mutated.
        dsl
            .update(USER_ACCOUNT)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .where(USER_ACCOUNT.ID.eq(USER_ID))
            .execute()
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, "ACTIVE")
            .where(ORGANISATION.ID.eq(ORGANISATION_ID))
            .execute()
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
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ID, PROFILE_ROLE_PERMISSION_ID)
            .set(ROLE_PERMISSION.ORGANISATION_ID, ORGANISATION_ID)
            .set(ROLE_PERMISSION.ROLE_ID, LOCAL_ADMIN_ROLE_ID)
            .set(ROLE_PERMISSION.PERMISSION_ID, PROFILE_PERMISSION_ID)
            .set(ROLE_PERMISSION.GRANTED_AT, OffsetDateTime.now())
            .set(ROLE_PERMISSION.CREATED_AT, OffsetDateTime.now())
            .set(ROLE_PERMISSION.UPDATED_AT, OffsetDateTime.now())
            .onConflictDoNothing()
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    @AfterEach
    fun removeCreatedRows() {
        createdRoleIds.forEach { roleId ->
            dsl
                .deleteFrom(USER_ROLE_ASSIGNMENT)
                .where(USER_ROLE_ASSIGNMENT.ROLE_ID.eq(roleId))
                .execute()
            dsl.deleteFrom(ROLE_PERMISSION).where(ROLE_PERMISSION.ROLE_ID.eq(roleId)).execute()
            dsl.deleteFrom(ROLE).where(ROLE.ID.eq(roleId)).execute()
        }
        createdRoleIds.clear()
        createdBranchIds.forEach { branchId ->
            dsl
                .deleteFrom(BRANCH_TRANSITION_LOG)
                .where(BRANCH_TRANSITION_LOG.BRANCH_ID.eq(branchId))
                .execute()
            dsl.deleteFrom(BRANCH).where(BRANCH.ID.eq(branchId)).execute()
        }
        createdBranchIds.clear()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    @Test
    fun `a pinned administrator administers another branch`() {
        grantRole("pin-admin", setOf("branch.create"), branchId = null)
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)

        val draftId = createDraftBranch(pinned)

        mockMvc
            .post("/api/v1/branches/$draftId/submit") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, pinned)
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(draftId.toString()) }
                jsonPath("$.status") { value("PENDING_APPROVAL") }
            }

        mockMvc
            .get("/api/v1/branches/$OPERATIONS_BRANCH_ID") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, pinned)
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(OPERATIONS_BRANCH_ID.toString()) }
            }
    }

    @Test
    fun `a pinned administrator searches another branch's assignments`() {
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)

        mockMvc
            .get(ApiPaths.BRANCH_ASSIGNMENTS) {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, pinned)
                param("branch_id", OPERATIONS_BRANCH_ID.toString())
            }.andExpect {
                status { isOk() }
                jsonPath("$.items") { value(hasSize<Any>(1)) }
                jsonPath("$.items[*].branch_id") {
                    value(everyItem(equalTo(OPERATIONS_BRANCH_ID.toString())))
                }
            }

        // Without an explicit branch_id the list still defaults to the selected branch.
        mockMvc
            .get(ApiPaths.BRANCH_ASSIGNMENTS) {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, pinned)
            }.andExpect {
                status { isOk() }
                jsonPath("$.items[*].branch_id") {
                    value(everyItem(equalTo(HEAD_OFFICE_BRANCH_ID.toString())))
                }
            }
    }

    private fun createDraftBranch(contextToken: String): UUID {
        val body =
            mockMvc
                .post("/api/v1/branches") {
                    with(localJwt())
                    header(ActiveOrganisationContextService.HEADER, contextToken)
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"branch_code":"PIN-${shortId()}",""" +
                        """"branch_name":"Pinning Draft",""" +
                        """"branch_type":"BRANCH","timezone":"Africa/Nairobi"}"""
                }.andExpect {
                    status { isCreated() }
                }.andReturn()
                .response.contentAsString
        return UUID.fromString(JsonPath.read<String>(body, "$.branch_id")).also {
            createdBranchIds += it
        }
    }

    @Test
    fun `a single branch user clears the automatic pin without a branch id`() {
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "INACTIVE")
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(ORGANISATION_ID))
            .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(OPERATIONS_BRANCH_ID))
            .execute()

        val autoPinned =
            mockMvc
                .post("/api/v1/auth/select-organisation") {
                    with(localJwt())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"organisation_id":"$ORGANISATION_ID"}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.branch_id") { value(HEAD_OFFICE_BRANCH_ID.toString()) }
                    jsonPath("$.requires_branch_selection") { value(false) }
                }.andReturn()
                .response
                .contextToken()

        val cleared =
            mockMvc
                .post("/api/v1/auth/select-branch") {
                    with(localJwt())
                    header(ActiveOrganisationContextService.HEADER, autoPinned)
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.organisation_id") { value(ORGANISATION_ID.toString()) }
                    // Explicit null, not omission: doesNotExist() also passes for a JSON null.
                    jsonPath("$.branch_id") { value(null) }
                    content { string(containsString("\"branch_id\":null")) }
                }.andReturn()
                .response
                .contextToken()

        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, cleared)
            }.andExpect {
                status { isOk() }
                jsonPath("$.selected_branch") { doesNotExist() }
            }
        mockMvc
            .get(ApiPaths.BRANCH_ASSIGNMENTS) {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, cleared)
            }.andExpect {
                status { isOk() }
                jsonPath(
                    "$.items[*].branch_id",
                ) { value(hasItem(HEAD_OFFICE_BRANCH_ID.toString())) }
            }
    }

    @Test
    fun `an explicit null branch id also clears the pin for a multi branch user`() {
        mockMvc
            .post("/api/v1/auth/select-branch") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, contextToken(HEAD_OFFICE_BRANCH_ID))
                contentType = MediaType.APPLICATION_JSON
                content = """{"branch_id":null}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.branch_id") { value(null) }
                content { string(containsString("\"branch_id\":null")) }
            }
    }

    @Test
    fun `operational authority stays narrowed to the selected branch`() {
        grantRole("ops-only-suspender", setOf("branch.suspend"), branchId = OPERATIONS_BRANCH_ID)

        // The branch-scoped grant is effective only while that branch is the selected one.
        permissionsFor(HEAD_OFFICE_BRANCH_ID).andExpectPermission("branch.suspend", present = false)
        permissionsFor(null).andExpectPermission("branch.suspend", present = false)
        permissionsFor(OPERATIONS_BRANCH_ID).andExpectPermission("branch.suspend", present = true)

        // And the permission gate in front of an administrative endpoint follows it.
        mockMvc
            .post("/api/v1/branches/$OPERATIONS_BRANCH_ID/suspend") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, contextToken(HEAD_OFFICE_BRANCH_ID))
                contentType = MediaType.APPLICATION_JSON
                content = """{"reason":"narrowing probe"}"""
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `a branch scoped grant on the selected branch does not reach another branch`() {
        grantRole("ops-suspender", setOf("branch.suspend"), branchId = OPERATIONS_BRANCH_ID)
        val pinnedToOps = contextToken(OPERATIONS_BRANCH_ID)

        // The coarse gate passes (the grant is effective while OPS is selected); the
        // application layer must still refuse HQ, which the grant does not cover.
        mockMvc
            .post("/api/v1/branches/$HEAD_OFFICE_BRANCH_ID/suspend") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, pinnedToOps)
                contentType = MediaType.APPLICATION_JSON
                content = """{"reason":"must be refused"}"""
            }.andExpect {
                status { isForbidden() }
            }

        org.assertj.core.api.Assertions
            .assertThat(dsl.fetchValue(BRANCH.STATUS, BRANCH.ID.eq(HEAD_OFFICE_BRANCH_ID)))
            .isEqualTo("ACTIVE")
    }

    @Test
    fun `a branch scoped role grant cannot assign roles on another branch`() {
        grantRole("ops-role-assigner", setOf("user.assign_role"), branchId = OPERATIONS_BRANCH_ID)
        val roleId = createdRoleIds.first()
        val before = dsl.fetchCount(USER_ROLE_ASSIGNMENT)

        mockMvc
            .post(ApiPaths.ROLE_ASSIGNMENTS) {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, contextToken(OPERATIONS_BRANCH_ID))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"user_id":"$USER_ID","role_id":"$roleId","scope_type":"BRANCH",""" +
                    """"branch_id":"$HEAD_OFFICE_BRANCH_ID"}"""
            }.andExpect {
                status { isForbidden() }
            }

        org.assertj.core.api.Assertions
            .assertThat(dsl.fetchCount(USER_ROLE_ASSIGNMENT))
            .isEqualTo(before)
    }

    @Test
    fun `administering a branch that does not exist answers not found while pinned`() {
        grantRole(
            "pin-lifecycle",
            setOf("branch.activate", "branch.suspend", "branch.reactivate", "branch.close"),
            branchId = null,
        )
        val pinned = contextToken(HEAD_OFFICE_BRANCH_ID)
        val missing = uuidV7()

        listOf("activate" to "{}", "suspend" to REASON, "reactivate" to "{}", "close" to REASON)
            .forEach { (action, body) ->
                mockMvc
                    .post("/api/v1/branches/$missing/$action") {
                        with(localJwt())
                        header(ActiveOrganisationContextService.HEADER, pinned)
                        contentType = MediaType.APPLICATION_JSON
                        content = body
                    }.andExpect {
                        status { isNotFound() }
                    }
            }
        mockMvc
            .post(ApiPaths.BRANCH_ASSIGNMENTS) {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, pinned)
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"user_id":"$USER_ID","branch_id":"$missing","assignment_type":"OPERATE"}"""
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `a blank or malformed branch id is rejected instead of clearing the pin`() {
        listOf(
            """{"branch_id":""}""",
            """{"branch_id":"  "}""",
            """{"branch_id":"not-a-uuid"}""",
        ).forEach { body ->
            mockMvc
                .post("/api/v1/auth/select-branch") {
                    with(localJwt())
                    header(
                        ActiveOrganisationContextService.HEADER,
                        contextToken(HEAD_OFFICE_BRANCH_ID),
                    )
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect {
                    status { isBadRequest() }
                }
        }
    }

    private fun permissionsFor(branchId: UUID?) =
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, contextToken(branchId))
            }.andExpect { status { isOk() } }

    private fun org.springframework.test.web.servlet.ResultActionsDsl.andExpectPermission(
        code: String,
        present: Boolean,
    ) {
        andExpect {
            jsonPath("$.permissions") {
                value(if (present) hasItem(code) else not(hasItem(code)))
            }
        }
    }

    private fun grantRole(
        code: String,
        permissionCodes: Set<String>,
        branchId: UUID?,
    ) {
        val now = OffsetDateTime.now()
        val roleId =
            requireNotNull(
                dsl
                    .insertInto(ROLE)
                    .set(ROLE.ORGANISATION_ID, ORGANISATION_ID)
                    .set(ROLE.ROLE_CODE, "$code-${shortId()}")
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
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, if (branchId == null) "TENANT" else "BRANCH")
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    private fun contextToken(branchId: UUID?): String =
        contextService.issue(
            ActiveOrganisationContext(USER_ID, ORGANISATION_ID, MEMBERSHIP_ID, branchId),
        )

    private fun localJwt() = jwt().jwt { token -> token.subject(USER_ID.toString()) }

    private fun shortId(): String = uuidV7().toString().takeLast(SHORT_ID_LENGTH).uppercase()

    private fun MockHttpServletResponse.contextToken(): String =
        JsonPath.read(contentAsString, "$.context_token")

    private companion object {
        const val SHORT_ID_LENGTH = 8
        const val REASON = """{"reason":"probe"}"""
        val USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val HEAD_OFFICE_BRANCH_ID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val OPERATIONS_BRANCH_ID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val LOCAL_ADMIN_ROLE_ID: UUID = UUID.fromString("77777777-7777-7777-7777-777777777777")
        val PROFILE_PERMISSION_ID: UUID = UUID.fromString("66666666-6666-6666-6666-666666666601")
        val PROFILE_ROLE_PERMISSION_ID: UUID =
            UUID.fromString("88888888-8888-8888-8888-888888888801")
    }
}
