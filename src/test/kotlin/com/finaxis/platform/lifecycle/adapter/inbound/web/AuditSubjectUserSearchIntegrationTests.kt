package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The #186 acceptance query, through the real routes. One user goes through a platform
 * suspension and reactivation, an assignment of a branch and a role, a membership suspension and
 * reactivation, and a membership revocation that ends those assignments. Another user goes through
 * the same steps, so a filter that matched by anything but the subject would show it.
 *
 * The platform user lifecycle rows are written under the reserved PLATFORM organisation (a
 * platform action), so the tenant log cannot hold them: the tenant route returns the six tenant
 * rows, and the two account rows are found on the platform log. Both logs answer
 * `subject_user_id`, and the platform route for the tenant answers it with the tenant rows.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AuditSubjectUserSearchIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val objectMapper = ObjectMapper()
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("subject-query", admin)
        private val platformAdmin =
            seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
        private val reason = """{"reason":"Regulatory compliance review."}"""

        @Test
        fun `the tenant subject query returns each tenant row about the user once`() {
            val user = seedUser("subject")
            val other = seedUser("other")
            runLifecycle(user)
            runLifecycle(other)

            val page = search(ApiPaths.AUDIT_EVENTS, tenantToken(), user)

            assertEquals(TENANT_ACTIONS, actionsOf(page))
            assertEquals(TENANT_ACTIONS.size.toLong(), page["page"]["total_items"].asLong())
            assertTrue(page["items"].all { it["subject_user_id"].asText() == user.toString() })
            assertEquals(TENANT_ACTIONS.size, page["items"].map { it["id"].asText() }.toSet().size)
            // The other user's identical steps are absent, and so are the tenant-wide rows.
            val otherPage = search(ApiPaths.AUDIT_EVENTS, tenantToken(), other)
            assertEquals(TENANT_ACTIONS, actionsOf(otherPage))
            assertTrue(
                otherPage["items"].all { it["subject_user_id"].asText() == other.toString() },
            )
        }

        @Test
        fun `the subject query pages through every row once`() {
            val user = seedUser("paged")
            runLifecycle(user)

            // Six rows in pages of four: 4, 2, then an empty page past the end.
            val pages = (0..2).map { search(ApiPaths.AUDIT_EVENTS, tenantToken(), user, it, 4) }

            assertEquals(listOf(4, 2, 0), pages.map { it["items"].size() })
            val ids = pages.flatMap { page -> page["items"].map { it["id"].asText() } }
            assertEquals(TENANT_ACTIONS.size, ids.toSet().size)
            val total = TENANT_ACTIONS.size.toLong()
            assertTrue(pages.all { it["page"]["total_items"].asLong() == total })
        }

        @Test
        fun `the platform route for the tenant answers the same subject filter`() {
            val user = seedUser("platform-tenant")
            runLifecycle(user)

            val page =
                search(
                    "${ApiPaths.PLATFORM_TENANTS}/$organisationId/audit-events",
                    platformToken(),
                    user,
                )

            assertEquals(TENANT_ACTIONS, actionsOf(page))
            assertTrue(page["items"].all { it["subject_user_id"].asText() == user.toString() })
        }

        @Test
        fun `the platform log holds the user's account rows once and nothing about another user`() {
            val user = seedUser("account")
            val other = seedUser("account-other")
            runLifecycle(user)
            runLifecycle(other)

            val page = search(ApiPaths.PLATFORM_AUDIT_EVENTS, platformToken(), user)

            assertEquals(ACCOUNT_ACTIONS, actionsOf(page))
            assertTrue(page["items"].all { it["subject_user_id"].asText() == user.toString() })
        }

        /**
         * Runs the user's steps through the real routes, in an order that keeps every step legal:
         * the assignments are made while the membership is ACTIVE, and the revocation comes last.
         */
        private fun runLifecycle(userId: UUID) {
            fixture.grantTenantPermissionsWithViews(organisationId, userId, "user.view")
            linkKeycloakIdentity(userId)
            platformPost("${ApiPaths.PLATFORM_USERS}/$userId/suspend", reason, "user.suspend")
                .andExpect { status { isOk() } }
            platformPost("${ApiPaths.PLATFORM_USERS}/$userId/reactivate", null, "user.activate")
                .andExpect { status { isOk() } }
            tenantPost(
                ApiPaths.BRANCH_ASSIGNMENTS,
                """{"user_id":"$userId","branch_id":"${headOfficeId()}",""" +
                    """"assignment_type":"OPERATE"}""",
                "user.assign_branch",
            ).andExpect { status { isCreated() } }
            tenantPost(
                ApiPaths.ROLE_ASSIGNMENTS,
                """{"user_id":"$userId","role_id":"${adminRoleId()}","scope_type":"TENANT"}""",
                "user.assign_role",
            ).andExpect { status { isCreated() } }
            val membershipId = membershipId(userId)
            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/suspend",
                reason,
                "membership.suspend",
            ).andExpect { status { isOk() } }
            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/reactivate",
                null,
                "membership.reactivate",
            ).andExpect { status { isOk() } }
            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/revoke",
                reason,
                "membership.revoke",
            ).andExpect { status { isOk() } }
        }

        private fun search(
            path: String,
            token: AppPrincipalAuthenticationToken,
            subject: UUID,
            page: Int = 0,
            size: Int = 100,
        ): JsonNode =
            objectMapper.readTree(
                mockMvc
                    .get(path) {
                        param("subject_user_id", subject.toString())
                        param("page", page.toString())
                        param("size", size.toString())
                        with(authentication(token))
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response
                    .contentAsString,
            )

        /** The actions of a page's items, sorted, so the lists compare as multisets. */
        private fun actionsOf(page: JsonNode): List<String> =
            page["items"].map { it["action"].asText() }.sorted()

        private fun platformPost(
            path: String,
            body: String?,
            permission: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                with(
                    authentication(
                        token(platformAdmin, PlatformOrganisation.ID, setOf(permission)),
                    ),
                )
            }

        private fun tenantPost(
            path: String,
            body: String?,
            permission: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
                with(authentication(tenantToken(setOf(permission))))
            }

        private fun tenantToken(permissions: Set<String> = setOf("audit.view")) =
            token(admin, organisationId, permissions)

        private fun platformToken() =
            token(platformAdmin, PlatformOrganisation.ID, setOf("audit.view"))

        private fun adminRoleId(): UUID =
            requireNotNull(
                dsl
                    .select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.ORGANISATION_ID.eq(organisationId))
                    .and(ROLE.ROLE_CODE.eq("TENANT_ADMIN"))
                    .fetchOne(ROLE.ID),
            )

        private fun headOfficeId(): UUID =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .fetchAny(BRANCH.ID),
            )

        private fun membershipId(userId: UUID): UUID =
            requireNotNull(
                dsl
                    .select(USER_ORGANISATION_MEMBERSHIP.ID)
                    .from(USER_ORGANISATION_MEMBERSHIP)
                    .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                    .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(userId))
                    .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID),
            )

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
                email = "user@subject-query.test",
                fullName = "Subject Query User",
                permissions = permissions,
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@subject-query.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            /** The six rows about the user in the tenant log, sorted (one per transition). */
            val TENANT_ACTIONS =
                listOf(
                    "branch.assign_user",
                    "membership.assignments_revoked",
                    "membership.reactivate",
                    "membership.revoke",
                    "membership.suspend",
                    "user.assign_role",
                )

            /** The two rows about the user in the platform log, sorted. */
            val ACCOUNT_ACTIONS = listOf("user_account.reactivate", "user_account.suspend")
        }
    }
