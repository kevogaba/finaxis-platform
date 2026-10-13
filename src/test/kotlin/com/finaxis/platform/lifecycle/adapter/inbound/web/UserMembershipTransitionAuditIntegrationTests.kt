package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
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
 * One audit row per user or membership transition, through the real routes (#186, ADR 0031).
 *
 * Each scenario lists every audit row its resource carries after one call, so a duplicate service
 * row next to the FSM row fails here as a longer list. The FSM row is keyed by the aggregate it
 * acts on (`USER_ACCOUNT` or `MEMBERSHIP`). The revoke cascade is its own row,
 * `membership.assignments_revoked`, which carries the number of branch and role assignments the
 * revoke ended.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class UserMembershipTransitionAuditIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val objectMapper = ObjectMapper()
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("audit-rows", admin)
        private val platformAdmin =
            seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
        private val reason = """{"reason":"Regulatory compliance review."}"""

        @Test
        fun `suspending a user writes one row, the FSM row keyed by the account`() {
            val userId = seedUser("suspend")

            platformPost("${ApiPaths.PLATFORM_USERS}/$userId/suspend", reason, "user.suspend")
                .andExpect { status { isOk() } }

            assertEquals(listOf("user_account.suspend USER_ACCOUNT"), auditRows(userId))
            assertEquals(platformAdmin, actorOf(userId, "user_account.suspend"))
            assertEquals(listOf<UUID?>(userId), subjectsOf(userId, "user_account.suspend"))
        }

        @Test
        fun `reactivating a user writes one row, the FSM row keyed by the account`() {
            val userId = seedUser("reactivate", "SUSPENDED")
            linkKeycloakIdentity(userId)

            platformPost("${ApiPaths.PLATFORM_USERS}/$userId/reactivate", null, "user.activate")
                .andExpect { status { isOk() } }

            assertEquals(listOf("user_account.reactivate USER_ACCOUNT"), auditRows(userId))
            assertEquals(platformAdmin, actorOf(userId, "user_account.reactivate"))
            assertEquals(listOf<UUID?>(userId), subjectsOf(userId, "user_account.reactivate"))
        }

        @Test
        fun `deactivating a user writes its two FSM rows and one row per revoked assignment`() {
            val userId = seedUser("deactivate")
            fixture.grantTenantPermissionsWithViews(organisationId, userId, "user.view")
            assignToBranch(userId, headOfficeId())
            val assignments = activeAssignments(userId)

            platformPost("${ApiPaths.PLATFORM_USERS}/$userId/deactivate", reason, "user.deactivate")
                .andExpect { status { isOk() } }

            assertEquals(
                listOf(
                    "user_account.complete_deactivation USER_ACCOUNT",
                    "user_account.start_deactivation USER_ACCOUNT",
                ),
                auditRows(userId),
            )
            assertEquals(
                assignments.first + assignments.second,
                dsl.fetchCount(
                    AUDIT_EVENT,
                    AUDIT_EVENT.ORGANISATION_ID
                        .eq(organisationId)
                        .and(AUDIT_EVENT.ACTION.eq("user.deactivation_assignment_revoked")),
                ),
            )
            // The deactivated user is the subject of every row about the account and of every
            // revoked assignment, and the platform administrator is only the actor.
            assertEquals(
                listOf<UUID?>(userId, userId),
                subjectsOf(userId, "user_account.start_deactivation") +
                    subjectsOf(userId, "user_account.complete_deactivation"),
            )
            assertEquals(
                assignments.first + assignments.second,
                dsl.fetchCount(
                    AUDIT_EVENT,
                    AUDIT_EVENT.ORGANISATION_ID
                        .eq(organisationId)
                        .and(AUDIT_EVENT.ACTION.eq("user.deactivation_assignment_revoked"))
                        .and(AUDIT_EVENT.SUBJECT_USER_ID.eq(userId)),
                ),
            )
        }

        @Test
        fun `a membership suspension writes one row, keyed by the membership`() {
            val memberId = seedUser("member-suspend")
            fixture.grantTenantPermissionsWithViews(organisationId, memberId, "user.view")
            val membershipId = membershipId(memberId)

            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/suspend",
                reason,
                "membership.suspend",
            ).andExpect { status { isOk() } }

            assertEquals(listOf("membership.suspend MEMBERSHIP"), auditRows(membershipId))
            assertEquals(emptyList(), auditRows(memberId).filter { it.startsWith("membership.") })
            // Keyed by the membership, but about the member: the subject is the member's user.
            assertEquals(listOf<UUID?>(memberId), subjectsOf(membershipId, "membership.suspend"))
        }

        @Test
        fun `a membership reactivation writes one row, keyed by the membership`() {
            val memberId = seedUser("member-reactivate")
            fixture.grantTenantPermissionsWithViews(organisationId, memberId, "user.view")
            val membershipId = membershipId(memberId)
            dsl.execute(
                "UPDATE user_organisation_membership " +
                    "SET membership_status = 'SUSPENDED' WHERE id = ?",
                membershipId,
            )

            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/reactivate",
                null,
                "membership.reactivate",
            ).andExpect { status { isOk() } }

            assertEquals(listOf("membership.reactivate MEMBERSHIP"), auditRows(membershipId))
            assertEquals(emptyList(), auditRows(memberId).filter { it.startsWith("membership.") })
            assertEquals(listOf<UUID?>(memberId), subjectsOf(membershipId, "membership.reactivate"))
        }

        @Test
        fun `revoking an active membership writes its FSM row and the cascade counts`() {
            val memberId = seedUser("member-revoke")
            fixture.grantTenantPermissionsWithViews(organisationId, memberId, "user.view")
            assignToBranch(memberId, headOfficeId())
            val membershipId = membershipId(memberId)
            val assignments = activeAssignments(memberId)

            tenantPost("${ApiPaths.MEMBERSHIPS}/$membershipId/revoke", reason, "membership.revoke")
                .andExpect { status { isOk() } }

            assertEquals(
                listOf(
                    "membership.assignments_revoked MEMBERSHIP",
                    "membership.revoke MEMBERSHIP",
                ),
                auditRows(membershipId),
            )
            assertEquals(admin, actorOf(membershipId, "membership.revoke"))
            assertEquals(
                assignments,
                cascadeCounts(membershipId),
            )
            assertEquals(listOf<UUID?>(memberId), subjectsOf(membershipId, "membership.revoke"))
            assertEquals(
                listOf<UUID?>(memberId),
                subjectsOf(membershipId, "membership.assignments_revoked"),
            )
        }

        @Test
        fun `revoking a pending membership writes its FSM row and the cascade counts`() {
            val inviteeEmail = "pending-${uuidV7()}@audit-rows.test"
            val headOffice = headOfficeId()
            val adminRole = adminRoleId()
            tenantPost(
                ApiPaths.TENANT_USERS,
                """{"email":"$inviteeEmail","username":"pending-${uuidV7()}",""" +
                    """"display_name":"Pending","membership_type":"STAFF",""" +
                    """"primary_branch_id":"$headOffice",""" +
                    """"branch_assignments":[{"branch_id":"$headOffice",""" +
                    """"assignment_type":"HOME"}],""" +
                    """"role_assignments":[{"role_id":"$adminRole","scope_type":"TENANT"}]}""",
                "user.invite",
            ).andExpect { status { isCreated() } }
            val inviteeId =
                requireNotNull(
                    dsl
                        .select(USER_ACCOUNT.ID)
                        .from(USER_ACCOUNT)
                        .where(USER_ACCOUNT.EMAIL.eq(inviteeEmail))
                        .fetchOne(USER_ACCOUNT.ID),
                )
            val membershipId = membershipId(inviteeId)
            val assignments = activeAssignments(inviteeId)

            tenantPost("${ApiPaths.MEMBERSHIPS}/$membershipId/revoke", reason, "membership.revoke")
                .andExpect { status { isOk() } }

            assertEquals(
                listOf(
                    "membership.assignments_revoked MEMBERSHIP",
                    "membership.revoke_pending MEMBERSHIP",
                ),
                auditRows(membershipId),
            )
            assertEquals(assignments, cascadeCounts(membershipId))
            assertEquals(
                listOf<UUID?>(inviteeId),
                subjectsOf(membershipId, "membership.assignments_revoked"),
            )
        }

        @Test
        fun `inviting a user makes the invitee the subject, the administrator only the actor`() {
            val inviteeEmail = "invitee-${uuidV7()}@audit-rows.test"
            val headOffice = headOfficeId()
            val adminRole = adminRoleId()
            tenantPost(
                ApiPaths.TENANT_USERS,
                """{"email":"$inviteeEmail","username":"invitee-${uuidV7()}",""" +
                    """"display_name":"Invitee","membership_type":"STAFF",""" +
                    """"primary_branch_id":"$headOffice",""" +
                    """"branch_assignments":[{"branch_id":"$headOffice",""" +
                    """"assignment_type":"HOME"}],""" +
                    """"role_assignments":[{"role_id":"$adminRole","scope_type":"TENANT"}]}""",
                "user.invite",
            ).andExpect { status { isCreated() } }
            val inviteeId =
                requireNotNull(
                    dsl
                        .select(USER_ACCOUNT.ID)
                        .from(USER_ACCOUNT)
                        .where(USER_ACCOUNT.EMAIL.eq(inviteeEmail))
                        .fetchOne(USER_ACCOUNT.ID),
                )

            assertEquals(listOf<UUID?>(inviteeId), subjectsOf(inviteeId, "user.invite"))
            assertEquals(admin, actorOf(inviteeId, "user.invite"))
            // The branch assignment the invitation made names the invitee, not the administrator.
            assertEquals(
                listOf(admin to inviteeId),
                dsl
                    .select(AUDIT_EVENT.ACTOR_USER_ID, AUDIT_EVENT.SUBJECT_USER_ID)
                    .from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ACTION.eq("branch.assign_user"))
                    .and(AUDIT_EVENT.SUBJECT_USER_ID.eq(inviteeId))
                    .fetch { it.value1() to it.value2() },
            )
        }

        /** The `action entity_type` of every audit row whose entity is [entityId], sorted. */
        private fun auditRows(entityId: UUID): List<String> =
            dsl
                .select(AUDIT_EVENT.ACTION, AUDIT_EVENT.ENTITY_TYPE)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ENTITY_ID.eq(entityId))
                .fetch()
                .map { "${it.get(AUDIT_EVENT.ACTION)} ${it.get(AUDIT_EVENT.ENTITY_TYPE)}" }
                .sorted()

        private fun actorOf(
            entityId: UUID,
            action: String,
        ): UUID? =
            dsl
                .select(AUDIT_EVENT.ACTOR_USER_ID)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ENTITY_ID.eq(entityId))
                .and(AUDIT_EVENT.ACTION.eq(action))
                .fetchOne(AUDIT_EVENT.ACTOR_USER_ID)

        /** The subject of every row of [action] whose entity is [entityId], in row order. */
        private fun subjectsOf(
            entityId: UUID,
            action: String,
        ): List<UUID?> =
            dsl
                .select(AUDIT_EVENT.SUBJECT_USER_ID)
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ENTITY_ID.eq(entityId))
                .and(AUDIT_EVENT.ACTION.eq(action))
                .fetch(AUDIT_EVENT.SUBJECT_USER_ID)

        /** The branch and role assignment counts the cascade row of [membershipId] carries. */
        private fun cascadeCounts(membershipId: UUID): Pair<Int, Int> {
            val json =
                requireNotNull(
                    dsl
                        .select(AUDIT_EVENT.METADATA_JSONB)
                        .from(AUDIT_EVENT)
                        .where(AUDIT_EVENT.ENTITY_ID.eq(membershipId))
                        .and(AUDIT_EVENT.ACTION.eq("membership.assignments_revoked"))
                        .fetchOne(AUDIT_EVENT.METADATA_JSONB),
                ).data()
            val node = objectMapper.readTree(json)
            return node["branchAssignmentsRevoked"].asText().toInt() to
                node["roleAssignmentsRevoked"].asText().toInt()
        }

        /** Active branch and role assignments of [userId] in this tenant, as the cascade counts. */
        private fun activeAssignments(userId: UUID): Pair<Int, Int> {
            val branches =
                dsl.fetchCount(
                    USER_BRANCH_ASSIGNMENT,
                    USER_BRANCH_ASSIGNMENT.ORGANISATION_ID
                        .eq(organisationId)
                        .and(USER_BRANCH_ASSIGNMENT.USER_ID.eq(userId))
                        .and(USER_BRANCH_ASSIGNMENT.STATUS.eq("ACTIVE")),
                )
            val roles =
                dsl.fetchCount(
                    USER_ROLE_ASSIGNMENT,
                    USER_ROLE_ASSIGNMENT.ORGANISATION_ID
                        .eq(organisationId)
                        .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
                        .and(USER_ROLE_ASSIGNMENT.STATUS.eq("ACTIVE")),
                )
            return branches to roles
        }

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
                with(authentication(token(admin, organisationId, setOf(permission))))
            }

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
                email = "user@audit-rows.test",
                fullName = "Audit Rows User",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@audit-rows.test")
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
