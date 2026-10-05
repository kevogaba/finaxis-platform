package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack proof of #207: four client mistakes that used to answer 500 now answer the status
 * that names them. A second revoke of a revoked membership is a 409; a BRANCH role assignment
 * without a branch is a 400 attributed to `branch_id`; an unknown aggregate id on a platform
 * lifecycle route is a 404 (and an unauthorised caller still gets the 403 first); and the platform
 * user reactivate no longer insists on a body whose only field is optional.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ClientErrorStatusIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("client-error", admin)

        @Test
        fun `revoking an already revoked membership is a 409 and changes nothing`() {
            val memberId = seedUser("member")
            fixture.grantTenantPermissionsWithViews(organisationId, memberId, "user.view")
            val membershipId = membershipId(memberId)
            val revoke = "${ApiPaths.MEMBERSHIPS}/$membershipId/revoke"
            val body = """{"reason":"Access no longer required"}"""

            tenantPost(revoke, body, "membership.revoke").andExpect { status { isOk() } }
            val revokedAt = membershipColumn(membershipId, "updated_at")
            val transitions = transitionCount(membershipId)
            val audits = auditCount("membership.revoke")

            tenantPost(revoke, body, "membership.revoke").andExpect {
                status { isConflict() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("conflict") }
                jsonPath("$.detail") { value("This membership has already been revoked.") }
            }

            assertEquals("REVOKED", membershipColumn(membershipId, "membership_status"))
            assertEquals(revokedAt, membershipColumn(membershipId, "updated_at"))
            assertEquals(transitions, transitionCount(membershipId))
            assertEquals(audits, auditCount("membership.revoke"))
        }

        @Test
        fun `a branch scope role assignment without a branch is a 400 naming branch_id`() {
            val memberId = seedUser("member")
            fixture.grantTenantPermissionsWithViews(organisationId, memberId, "user.view")
            val before = assignmentCount(memberId)

            tenantPost(
                ApiPaths.ROLE_ASSIGNMENTS,
                assignmentBody(memberId, adminRoleId(), "BRANCH", null),
                "user.assign_role",
            ).andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.violations[0].field") { value("branch_id") }
            }

            assertEquals(before, assignmentCount(memberId))
        }

        @Test
        fun `valid branch and tenant scope role assignments still succeed`() {
            val memberId = seedUser("member")
            fixture.grantTenantPermissionsWithViews(organisationId, memberId, "user.view")
            val branchId = headOfficeId()
            assignToBranch(memberId, branchId)

            tenantPost(
                ApiPaths.ROLE_ASSIGNMENTS,
                assignmentBody(memberId, adminRoleId(), "BRANCH", branchId),
                "user.assign_role",
            ).andExpect {
                status { isCreated() }
                jsonPath("$.scope_type") { value("BRANCH") }
                jsonPath("$.branch_id") { value(branchId.toString()) }
            }
            tenantPost(
                ApiPaths.ROLE_ASSIGNMENTS,
                assignmentBody(memberId, adminRoleId(), "TENANT", null),
                "user.assign_role",
            ).andExpect {
                status { isCreated() }
                jsonPath("$.scope_type") { value("TENANT") }
            }
        }

        @Test
        fun `a tenant scope role assignment naming a branch keeps its 422 from the service`() {
            val memberId = seedUser("member")
            fixture.grantTenantPermissionsWithViews(organisationId, memberId, "user.view")

            tenantPost(
                ApiPaths.ROLE_ASSIGNMENTS,
                assignmentBody(memberId, adminRoleId(), "TENANT", headOfficeId()),
                "user.assign_role",
            ).andExpect {
                status { isUnprocessableContent() }
                jsonPath("$.code") { value("invalid_operation") }
            }
        }

        @Test
        fun `inviting a user with a branch scope role and no branch is a 400 naming the entry`() {
            val body =
                """{"email":"invitee-${uuidV7()}@client-error.test","username":"invitee",""" +
                    """"display_name":"Invitee","membership_type":"STAFF","role_assignments":""" +
                    """[{"role_id":"${adminRoleId()}","scope_type":"BRANCH"}]}"""

            tenantPost(ApiPaths.TENANT_USERS, body, "user.invite").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.violations[0].field") { value("role_assignments[0].branch_id") }
            }
        }

        @Test
        fun `inviting a user with a tenant scope role naming a branch is a 422`() {
            val body =
                """{"email":"invitee-${uuidV7()}@client-error.test","username":"invitee",""" +
                    """"display_name":"Invitee","membership_type":"STAFF","role_assignments":""" +
                    """[{"role_id":"${adminRoleId()}","scope_type":"TENANT",""" +
                    """"branch_id":"${headOfficeId()}"}]}"""

            tenantPost(ApiPaths.TENANT_USERS, body, "user.invite").andExpect {
                status { isUnprocessableContent() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("invalid_operation") }
            }
        }

        @Test
        fun `unknown aggregate ids on the platform lifecycle routes are 404 problems`() {
            val platformAdmin = seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
            val unknown = uuidV7()
            val reason = """{"reason":"Regulatory compliance review."}"""

            listOf(
                Triple("${ApiPaths.PLATFORM_TENANTS}/$unknown/suspend", "tenant.suspend", reason),
                Triple(
                    "${ApiPaths.PLATFORM_TENANTS}/$unknown/reactivate",
                    "tenant.reactivate",
                    null,
                ),
                Triple(
                    "${ApiPaths.PLATFORM_TENANTS}/$unknown/deprovision",
                    "tenant.deprovision",
                    reason,
                ),
                Triple("${ApiPaths.PLATFORM_USERS}/$unknown/suspend", "user.suspend", reason),
                Triple("${ApiPaths.PLATFORM_USERS}/$unknown/reactivate", "user.activate", null),
                Triple("${ApiPaths.PLATFORM_USERS}/$unknown/deactivate", "user.deactivate", reason),
            ).forEach { (path, permission, body) ->
                platformPost(path, body, platformAdmin, permission).andExpect {
                    status { isNotFound() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.code") { value("resource_not_found") }
                    jsonPath("$.instance") { value(path) }
                }
            }
        }

        @Test
        fun `the platform organisation is still a 409 on tenant reactivate and deprovision`() {
            val platformAdmin = seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
            val reason = """{"reason":"Regulatory compliance review."}"""

            listOf("reactivate" to "tenant.reactivate", "deprovision" to "tenant.deprovision")
                .forEach { (action, permission) ->
                    val path = "${ApiPaths.PLATFORM_TENANTS}/${PlatformOrganisation.ID}/$action"
                    platformPost(path, reason, platformAdmin, permission).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("lifecycle.platform_organisation_protected") }
                    }
                }
        }

        @Test
        fun `an unauthorised caller gets 403 before any 404 on those routes`() {
            val unauthorised = seedUser("unauthorised")
            val unknown = uuidV7()
            val reason = """{"reason":"Regulatory compliance review."}"""

            listOf(
                "${ApiPaths.PLATFORM_TENANTS}/$unknown/suspend" to "tenant.suspend",
                "${ApiPaths.PLATFORM_TENANTS}/$unknown/reactivate" to "tenant.reactivate",
                "${ApiPaths.PLATFORM_TENANTS}/$unknown/deprovision" to "tenant.deprovision",
                "${ApiPaths.PLATFORM_USERS}/$unknown/suspend" to "user.suspend",
                "${ApiPaths.PLATFORM_USERS}/$unknown/reactivate" to "user.activate",
                "${ApiPaths.PLATFORM_USERS}/$unknown/deactivate" to "user.deactivate",
            ).forEach { (path, permission) ->
                // No authority at all: refused at the method gate.
                platformPost(path, reason, unauthorised, null).andExpect {
                    status { isForbidden() }
                }
                // The authority on the token but no grant behind it: refused by the guard.
                platformPost(path, reason, unauthorised, permission).andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value("forbidden") }
                }
            }
        }

        @Test
        fun `platform user reactivate accepts no body an empty body or a reason`() {
            val platformAdmin = seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
            val userId = seedUser("suspended", "SUSPENDED")
            linkKeycloakIdentity(userId)
            val path = "${ApiPaths.PLATFORM_USERS}/$userId/reactivate"

            listOf(null, "{}", """{"reason":"Investigation complete"}""").forEach { body ->
                platformPost(path, body, platformAdmin, "user.activate").andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("ACTIVE") }
                }
                assertEquals("ACTIVE", userStatus(userId))
                dsl.execute("UPDATE user_account SET status = 'SUSPENDED' WHERE id = ?", userId)
            }
        }

        private fun assignmentBody(
            userId: UUID,
            roleId: UUID,
            scope: String,
            branchId: UUID?,
        ): String =
            """{"user_id":"$userId","role_id":"$roleId","scope_type":"$scope"""" +
                (branchId?.let { ""","branch_id":"$it"""" } ?: "") +
                "}"

        private fun tenantPost(
            path: String,
            body: String,
            permission: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token(admin, organisationId, setOf(permission))))
            }

        private fun platformPost(
            path: String,
            body: String?,
            userId: UUID,
            permission: String?,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                with(
                    authentication(
                        token(userId, PlatformOrganisation.ID, setOfNotNull(permission)),
                    ),
                )
            }

        private fun adminRoleId(): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = 'TENANT_ADMIN'",
                    organisationId,
                )!!
                .get(0, UUID::class.java)

        private fun headOfficeId(): UUID =
            dsl
                .fetch("SELECT id FROM branch WHERE organisation_id = ?", organisationId)
                .first()
                .get(0, UUID::class.java)

        private fun membershipId(userId: UUID): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM user_organisation_membership " +
                        "WHERE organisation_id = ? AND user_id = ?",
                    organisationId,
                    userId,
                )!!
                .get(0, UUID::class.java)

        private fun membershipColumn(
            membershipId: UUID,
            column: String,
        ): String =
            dsl
                .fetchOne(
                    "SELECT $column::text FROM user_organisation_membership WHERE id = ?",
                    membershipId,
                )!!
                .get(0, String::class.java)

        private fun transitionCount(membershipId: UUID): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM user_organisation_membership_transition_log " +
                        "WHERE entity_id = ?",
                    membershipId,
                )!!
                .get(0, Int::class.java)

        private fun auditCount(action: String): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? AND action = ?",
                    organisationId,
                    action,
                )!!
                .get(0, Int::class.java)

        private fun assignmentCount(userId: UUID): Int =
            dsl
                .fetchOne("SELECT COUNT(*) FROM user_role_assignment WHERE user_id = ?", userId)!!
                .get(0, Int::class.java)

        private fun userStatus(userId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        // Reactivation is guarded: only a user whose Keycloak identity is linked may be activated.
        private fun linkKeycloakIdentity(userId: UUID) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(KEYCLOAK_IDENTITY_LINK)
                .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
                .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, "subject-$userId")
                .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
                .execute()
        }

        private fun assignToBranch(
            userId: UUID,
            branchId: UUID,
        ) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_BRANCH_ASSIGNMENT)
                .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, organisationId)
                .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
                .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
                .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, "HOME")
                .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
                .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, now)
                .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, now)
                .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
                .execute()
        }

        private fun token(
            userId: UUID,
            tenantId: UUID,
            permissions: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@client-error.test",
                fullName = "Client Error User",
                permissions = permissions,
            ),
        )

        private fun seedUser(
            label: String,
            status: String = "ACTIVE",
        ): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@client-error.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, status)
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }
    }
