package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
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
 * Full-stack proof of the self-assignment guard of #342 (ADR 0044). Assigning a role or a branch to
 * yourself needs `user.assign_self` held tenant-wide, which only the seeded administrator roles
 * hold: the tenant's `TENANT_ADMIN` may, a role manager (`IAM_ADMIN`) may not, and a holder of the
 * permission at one branch only may not. Assigning someone else is unchanged.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SelfAssignmentIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("self-assign", admin)

        @Test
        fun `a role manager cannot assign a role or a branch to themselves`() {
            val manager = seedUser("manager")
            fixture.grantSeededTenantRole(organisationId, manager, "IAM_ADMIN")

            assignRole(manager, manager).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("Missing permission: user.assign_self.") }
            }
            assignBranch(manager, manager).andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: user.assign_self.") }
            }

            assertEquals(0, roleAssignments(manager, auditorRoleId()))
            assertEquals(0, branchAssignments(manager))
            // One DENIED api.access_denied row per refusal, naming the permission (ADR 0030).
            assertEquals(2, deniedRows(manager, "user.assign_self"))
        }

        @Test
        fun `the tenant administrator may assign a role and a branch to themselves`() {
            assignRole(admin, admin).andExpect { status { isCreated() } }
            assignBranch(admin, admin).andExpect { status { isCreated() } }

            assertEquals(1, roleAssignments(admin, auditorRoleId()))
        }

        @Test
        fun `a role manager still assigns someone else`() {
            val manager = seedUser("manager")
            fixture.grantSeededTenantRole(organisationId, manager, "IAM_ADMIN")
            val member = seedUser("member")
            fixture.grantSeededTenantRole(organisationId, member, "BRANCH_OPERATOR")

            assignRole(manager, member).andExpect { status { isCreated() } }

            assertEquals(1, roleAssignments(member, auditorRoleId()))
        }

        @Test
        fun `user assign self held at one branch only does not count`() {
            val narrow = seedUser("narrow")
            fixture.grantTenantPermissionsWithViews(
                organisationId,
                narrow,
                "user.assign_role",
                "user.assign_branch",
            )
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                headOfficeId(),
                narrow,
                "user.assign_self",
            )

            assignRole(narrow, narrow).andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: user.assign_self.") }
            }
            // The head office is the branch the grant names, so a target-branch check would pass.
            assignBranch(narrow, narrow).andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: user.assign_self.") }
            }

            assertEquals(0, roleAssignments(narrow, auditorRoleId()))
            assertEquals(0, branchAssignments(narrow))
        }

        @Test
        fun `user assign self cannot be granted to a custom role`() {
            val roleId = seedCustomRole()

            post(
                "${ApiPaths.ROLES}/$roleId/permissions",
                """{"permission_code":"user.assign_self"}""",
                admin,
                setOf("role.assign_permission"),
            ).andExpect {
                status { isUnprocessableContent() }
                jsonPath("$.code") { value("invalid_operation") }
            }

            assertEquals(0, rolePermissions(roleId))
        }

        @Test
        fun `a role manager cannot add a permission to a role they hold`() {
            val manager = seedUser("manager")
            fixture.grantSeededTenantRole(organisationId, manager, "IAM_ADMIN")
            val held = seedCustomRole("HELD")
            val other = seedCustomRole("OTHER")
            // Someone else assigned it: holding a role is not the escalation, adding to it is.
            assignRole(admin, manager, held).andExpect { status { isCreated() } }

            grantPermission(manager, held).andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: user.assign_self.") }
            }
            grantPermission(manager, other).andExpect { status { isCreated() } }

            assertEquals(0, rolePermissions(held))
            assertEquals(1, rolePermissions(other))
            assertEquals(1, deniedRows(manager, "user.assign_self"))
        }

        @Test
        fun `holding the role at one branch counts as holding it`() {
            val manager = seedUser("manager")
            fixture.grantSeededTenantRole(organisationId, manager, "IAM_ADMIN")
            val held = seedCustomRole("HELD")
            assignBranch(admin, manager).andExpect { status { isCreated() } }
            post(
                ApiPaths.ROLE_ASSIGNMENTS,
                """{"user_id":"$manager","role_id":"$held","scope_type":"BRANCH",""" +
                    """"branch_id":"${headOfficeId()}"}""",
                admin,
            ).andExpect { status { isCreated() } }

            grantPermission(manager, held).andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: user.assign_self.") }
            }

            assertEquals(0, rolePermissions(held))
        }

        @Test
        fun `a role manager cannot reactivate a role they hold`() {
            val manager = seedUser("manager")
            fixture.grantSeededTenantRole(organisationId, manager, "IAM_ADMIN")
            val held = seedCustomRole("HELD")
            assignRole(admin, manager, held).andExpect { status { isCreated() } }
            // The administrator disables it to cut off what the manager gets from it.
            transition(admin, held, "deactivate").andExpect { status { isOk() } }

            transition(manager, held, "activate").andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: user.assign_self.") }
            }
            assertEquals("DISABLED", roleStatus(held))

            transition(admin, held, "activate").andExpect { status { isOk() } }
            assertEquals("ACTIVE", roleStatus(held))
        }

        @Test
        fun `a role manager still deactivates a role they hold and reactivates another`() {
            val manager = seedUser("manager")
            fixture.grantSeededTenantRole(organisationId, manager, "IAM_ADMIN")
            val held = seedCustomRole("HELD")
            val other = seedCustomRole("OTHER")
            assignRole(admin, manager, held).andExpect { status { isCreated() } }

            transition(manager, held, "deactivate").andExpect { status { isOk() } }
            transition(manager, other, "deactivate").andExpect { status { isOk() } }
            transition(manager, other, "activate").andExpect { status { isOk() } }

            assertEquals("DISABLED", roleStatus(held))
            assertEquals("ACTIVE", roleStatus(other))
        }

        private fun transition(
            actor: UUID,
            roleId: UUID,
            action: String,
        ): ResultActionsDsl =
            post("${ApiPaths.ROLES}/$roleId/$action", "", actor, setOf("role.$action"))

        private fun roleStatus(roleId: UUID): String? =
            dsl
                .select(ROLE.STATUS)
                .from(ROLE)
                .where(ROLE.ID.eq(roleId))
                .fetchOne(ROLE.STATUS)

        @Test
        fun `the tenant administrator may add a permission to a role they hold`() {
            val held = seedCustomRole("HELD")
            assignRole(admin, admin, held).andExpect { status { isCreated() } }

            grantPermission(admin, held).andExpect { status { isCreated() } }

            assertEquals(1, rolePermissions(held))
        }

        private fun grantPermission(
            actor: UUID,
            roleId: UUID,
        ): ResultActionsDsl =
            post(
                "${ApiPaths.ROLES}/$roleId/permissions",
                """{"permission_code":"audit.view"}""",
                actor,
                setOf("role.assign_permission"),
            )

        private fun rolePermissions(roleId: UUID): Int =
            dsl
                .fetchValue("SELECT count(*) FROM role_permission WHERE role_id = ?", roleId)
                .toString()
                .toInt()

        private fun assignRole(
            actor: UUID,
            user: UUID,
            roleId: UUID = auditorRoleId(),
        ): ResultActionsDsl =
            post(
                ApiPaths.ROLE_ASSIGNMENTS,
                """{"user_id":"$user","role_id":"$roleId","scope_type":"TENANT"}""",
                actor,
            )

        private fun assignBranch(
            actor: UUID,
            user: UUID,
        ): ResultActionsDsl =
            post(
                ApiPaths.BRANCH_ASSIGNMENTS,
                """{"user_id":"$user","branch_id":"${headOfficeId()}",""" +
                    """"assignment_type":"OPERATE"}""",
                actor,
            )

        private fun post(
            path: String,
            body: String,
            actor: UUID,
            authorities: Set<String> = ASSIGNMENT_AUTHORITIES,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token(actor, authorities)))
            }

        private fun deniedRows(
            actor: UUID,
            permission: String,
        ): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND actor_user_id = ? AND action = 'api.access_denied' " +
                        "AND outcome = 'DENIED' AND metadata_jsonb ->> 'permission' = ?",
                    organisationId,
                    actor,
                    permission,
                ).toString()
                .toInt()

        private fun seedCustomRole(code: String = "ASSIGNER"): UUID {
            val roleId = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(ROLE)
                .set(ROLE.ID, roleId)
                .set(ROLE.ORGANISATION_ID, organisationId)
                .set(ROLE.ROLE_CODE, "CUSTOM_$code")
                .set(ROLE.ROLE_NAME, "Custom")
                .set(ROLE.SYSTEM_ROLE, false)
                .set(ROLE.STATUS, "ACTIVE")
                .set(ROLE.CREATED_AT, now)
                .set(ROLE.UPDATED_AT, now)
                .execute()
            return roleId
        }

        private fun roleAssignments(
            userId: UUID,
            roleId: UUID,
        ): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM user_role_assignment " +
                        "WHERE user_id = ? AND role_id = ? AND status = 'ACTIVE'",
                    userId,
                    roleId,
                ).toString()
                .toInt()

        private fun branchAssignments(userId: UUID): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM user_branch_assignment WHERE user_id = ? " +
                        "AND organisation_id = ?",
                    userId,
                    organisationId,
                ).toString()
                .toInt()

        private fun auditorRoleId(): UUID =
            requireNotNull(
                dsl
                    .select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.ORGANISATION_ID.eq(organisationId))
                    .and(ROLE.ROLE_CODE.eq("TENANT_AUDITOR"))
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

        // The database decides; the token carries the coarse authorities the routes' gates read.
        private fun token(
            actor: UUID,
            authorities: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = actor,
                keycloakSubject = "user-$actor",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@self-assign.test",
                fullName = "Self Assign User",
                permissions = authorities,
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@self-assign.test")
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
            val ASSIGNMENT_AUTHORITIES = setOf("user.assign_role", "user.assign_branch")
        }
    }
