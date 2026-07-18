package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasItem
import org.jooq.DSLContext
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

private const val LOCAL_USER_SUBJECT = "11111111-1111-1111-1111-111111111111"
private const val LOCAL_ORGANISATION_ID = "22222222-2222-2222-2222-222222222222"
private const val HEAD_OFFICE_BRANCH_ID = "33333333-3333-3333-3333-333333333333"
private const val OPERATIONS_BRANCH_ID = "44444444-4444-4444-4444-444444444444"
private const val LOCAL_MEMBERSHIP_ID = "55555555-5555-5555-5555-555555555555"

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var contextService: ActiveOrganisationContextService

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var cacheManager: CacheManager

    @BeforeEach
    fun resetSeedState() {
        dsl
            .update(USER_ACCOUNT)
            .set(USER_ACCOUNT.STATUS, ACTIVE)
            .set(USER_ACCOUNT.LAST_LOGIN_AT, null as java.time.OffsetDateTime?)
            .where(USER_ACCOUNT.ID.eq(uuid(LOCAL_USER_SUBJECT)))
            .execute()
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, ACTIVE)
            .where(ORGANISATION.ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .execute()
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, ACTIVE)
            .where(BRANCH.ORGANISATION_ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .execute()
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, ACTIVE)
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .execute()
        restoreProfilePermission()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()
    }

    @Test
    fun `smoke auth flow selects tenant context and returns current profile`() {
        val organisationContextToken = selectOrganisation()
        val branchContextToken = selectBranch(organisationContextToken)

        assertCurrentProfile(branchContextToken)
    }

    @Test
    fun `runtime resolution rejects suspended app user`() {
        dsl
            .update(USER_ACCOUNT)
            .set(USER_ACCOUNT.STATUS, SUSPENDED)
            .where(USER_ACCOUNT.ID.eq(uuid(LOCAL_USER_SUBJECT)))
            .execute()

        assertCurrentProfileDenied(branchContextToken())
    }

    @Test
    fun `runtime resolution rejects suspended organisation`() {
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, SUSPENDED)
            .where(ORGANISATION.ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .execute()

        assertCurrentProfileDenied(branchContextToken())
    }

    @Test
    fun `runtime resolution rejects deprovisioned organisation`() {
        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, DEPROVISIONED)
            .where(ORGANISATION.ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .execute()

        assertCurrentProfileDenied(branchContextToken())
    }

    @Test
    fun `runtime resolution rejects missing active branch assignment`() {
        dsl
            .update(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, INACTIVE)
            .where(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(uuid(LOCAL_USER_SUBJECT)))
            .and(USER_BRANCH_ASSIGNMENT.BRANCH_ID.eq(uuid(HEAD_OFFICE_BRANCH_ID)))
            .execute()

        assertCurrentProfileDenied(branchContextToken())
    }

    @Test
    fun `runtime resolution rejects context for another organisation`() {
        val otherOrganisationContext =
            contextService.issue(
                ActiveOrganisationContext(
                    userId = uuid(LOCAL_USER_SUBJECT),
                    organisationId = uuidV7(),
                    membershipId = uuid(LOCAL_MEMBERSHIP_ID),
                    branchId = null,
                ),
            )

        assertCurrentProfileDenied(otherOrganisationContext)
    }

    @Test
    fun `method security rejects role without concrete permission`() {
        dsl
            .deleteFrom(ROLE_PERMISSION)
            .where(ROLE_PERMISSION.ORGANISATION_ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .and(ROLE_PERMISSION.PERMISSION_ID.eq(uuid(PROFILE_PERMISSION_ID)))
            .execute()
        cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.clear()

        assertCurrentProfileDenied(branchContextToken())
    }

    @Test
    fun `runtime resolution rejects inactive branch`() {
        dsl
            .update(BRANCH)
            .set(BRANCH.STATUS, CLOSED)
            .where(BRANCH.ORGANISATION_ID.eq(uuid(LOCAL_ORGANISATION_ID)))
            .and(BRANCH.ID.eq(uuid(HEAD_OFFICE_BRANCH_ID)))
            .execute()

        assertCurrentProfileDenied(branchContextToken())
    }

    @Test
    fun `runtime resolution activates invited user on first login`() {
        dsl
            .update(USER_ACCOUNT)
            .set(USER_ACCOUNT.STATUS, INVITED)
            .set(USER_ACCOUNT.LAST_LOGIN_AT, null as java.time.OffsetDateTime?)
            .where(USER_ACCOUNT.ID.eq(uuid(LOCAL_USER_SUBJECT)))
            .execute()

        assertCurrentProfile(branchContextToken())

        val userRecord =
            dsl
                .select(USER_ACCOUNT.STATUS, USER_ACCOUNT.LAST_LOGIN_AT)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(uuid(LOCAL_USER_SUBJECT)))
                .fetchOne()
        org.assertj.core.api.Assertions
            .assertThat(userRecord?.value1())
            .isEqualTo(ACTIVE)
        org.assertj.core.api.Assertions
            .assertThat(userRecord?.value2())
            .isNotNull()
        val auditRecorded =
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ACTION.eq("user.first_login_activation"))
                    .and(AUDIT_EVENT.ENTITY_ID.eq(uuid(LOCAL_USER_SUBJECT))),
            )
        org.assertj.core.api.Assertions
            .assertThat(auditRecorded)
            .isTrue()
    }

    @Test
    fun `public controllers reject unauthenticated requests`() {
        mockMvc
            .get("/api/v1/auth/me")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `profile endpoint requires active organisation context`() {
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `invalid active organisation context fails closed`() {
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, "invalid-context")
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `selection endpoint returns validation errors through api exception handler`() {
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.violations[0].field") { value("organisationId") }
            }
    }

    @Test
    fun `selection endpoint returns invalid json errors through api exception handler`() {
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisationId":"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid_json") }
                jsonPath("$.violations") { doesNotExist() }
            }
    }

    @Test
    fun `selection endpoint rejects unknown organisation membership`() {
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisation_id":"${uuidV7()}"}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
            }
    }

    private fun selectOrganisation(): String =
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                with(localJwt())
                contentType = MediaType.APPLICATION_JSON
                content = """{"organisation_id":"$LOCAL_ORGANISATION_ID"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.organisation_id") { value(LOCAL_ORGANISATION_ID) }
                jsonPath("$.membership_id") { value(LOCAL_MEMBERSHIP_ID) }
                jsonPath("$.context_header") { value(ActiveOrganisationContextService.HEADER) }
                jsonPath("$.branch_id") { doesNotExist() }
                jsonPath("$.requires_branch_selection") { value(true) }
                jsonPath("$.assigned_branch_ids") {
                    value(containsInAnyOrder(HEAD_OFFICE_BRANCH_ID, OPERATIONS_BRANCH_ID))
                }
            }.andReturn()
            .response
            .jsonContextToken()

    private fun selectBranch(organisationContextToken: String): String =
        mockMvc
            .post("/api/v1/auth/select-branch") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, organisationContextToken)
                contentType = MediaType.APPLICATION_JSON
                content = """{"branch_id":"$HEAD_OFFICE_BRANCH_ID"}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.organisation_id") { value(LOCAL_ORGANISATION_ID) }
                jsonPath("$.membership_id") { value(LOCAL_MEMBERSHIP_ID) }
                jsonPath("$.branch_id") { value(HEAD_OFFICE_BRANCH_ID) }
                jsonPath("$.context_header") { value(ActiveOrganisationContextService.HEADER) }
            }.andReturn()
            .response
            .jsonContextToken()

    private fun assertCurrentProfile(branchContextToken: String) {
        mockMvc
            .get("/api/v1/auth/me") {
                with(localJwt())
                header(ActiveOrganisationContextService.HEADER, branchContextToken)
            }.andExpect {
                status { isOk() }
                jsonPath("$.user_id") { value(LOCAL_USER_SUBJECT) }
                jsonPath("$.keycloak_subject") { value(LOCAL_USER_SUBJECT) }
                jsonPath("$.email") { value("admin@finaxis.local") }
                jsonPath("$.organisation.id") { value(LOCAL_ORGANISATION_ID) }
                jsonPath("$.organisation.code") { value("FINAXIS-LOCAL") }
                jsonPath("$.membership.id") { value(LOCAL_MEMBERSHIP_ID) }
                jsonPath("$.selected_branch.id") { value(HEAD_OFFICE_BRANCH_ID) }
                jsonPath("$.selected_branch.code") { value("HQ") }
                jsonPath("$.branches[*].id") {
                    value(containsInAnyOrder(HEAD_OFFICE_BRANCH_ID, OPERATIONS_BRANCH_ID))
                }
                jsonPath("$.roles[*].code") { value(hasItem("local-admin")) }
                jsonPath("$.permissions") {
                    value(
                        containsInAnyOrder(
                            "iam.profile.read",
                            "iam.user.invite",
                            "logistics.shipment.approve",
                        ),
                    )
                }
            }
    }

    private fun assertCurrentProfileDenied(contextToken: String) {
        val response =
            mockMvc
                .get("/api/v1/auth/me") {
                    with(localJwt())
                    header(ActiveOrganisationContextService.HEADER, contextToken)
                }.andExpect {
                    status { isForbidden() }
                }.andReturn()
                .response

        response.errorMessage?.let { message ->
            org.assertj.core.api.Assertions
                .assertThat(message)
                .doesNotContain(SUSPENDED)
                .doesNotContain(DEPROVISIONED)
                .doesNotContain(CLOSED)
        }
    }

    private fun branchContextToken(): String =
        contextService.issue(
            ActiveOrganisationContext(
                userId = uuid(LOCAL_USER_SUBJECT),
                organisationId = uuid(LOCAL_ORGANISATION_ID),
                membershipId = uuid(LOCAL_MEMBERSHIP_ID),
                branchId = uuid(HEAD_OFFICE_BRANCH_ID),
            ),
        )

    private fun localJwt() =
        jwt().jwt { token ->
            token.subject(LOCAL_USER_SUBJECT)
        }

    private fun restoreProfilePermission() {
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ID, uuid(PROFILE_ROLE_PERMISSION_ID))
            .set(ROLE_PERMISSION.ORGANISATION_ID, uuid(LOCAL_ORGANISATION_ID))
            .set(ROLE_PERMISSION.ROLE_ID, uuid(LOCAL_ADMIN_ROLE_ID))
            .set(ROLE_PERMISSION.PERMISSION_ID, uuid(PROFILE_PERMISSION_ID))
            .set(ROLE_PERMISSION.GRANTED_AT, java.time.OffsetDateTime.parse(SEED_TIME))
            .set(ROLE_PERMISSION.CREATED_AT, java.time.OffsetDateTime.parse(SEED_TIME))
            .set(ROLE_PERMISSION.UPDATED_AT, java.time.OffsetDateTime.parse(SEED_TIME))
            .onConflictDoNothing()
            .execute()
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private fun org.springframework.mock.web.MockHttpServletResponse.jsonContextToken(): String =
        com.jayway.jsonpath.JsonPath
            .read(contentAsString, "$.context_token")

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val INVITED = "INVITED"
        const val SUSPENDED = "SUSPENDED"
        const val DEPROVISIONED = "DEPROVISIONED"
        const val CLOSED = "CLOSED"
        const val INACTIVE = "INACTIVE"
        const val PROFILE_PERMISSION_ID = "66666666-6666-6666-6666-666666666601"
        const val PROFILE_ROLE_PERMISSION_ID = "88888888-8888-8888-8888-888888888801"
        const val LOCAL_ADMIN_ROLE_ID = "77777777-7777-7777-7777-777777777777"
        const val SEED_TIME = "2026-07-04T00:00:00Z"
    }
}
