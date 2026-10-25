package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full-stack proof of role archive (#297, ADR 0035). `ARCHIVED` is a terminal state reachable from
 * `ACTIVE` or `DISABLED`, refused while the role has an ACTIVE assignment, never for a system
 * role, hidden from the default list, and it leaves the role immutable and unassignable. Every
 * transition (`ACTIVATE`, `DEACTIVATE`, `ARCHIVE`) writes one `role_transition_log` row.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("LargeClass", "TooManyFunctions")
class RoleArchiveIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val userProvisioningService: UserProvisioningService,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser()
        private val organisationId = fixture.createActiveOrganisation("rai", admin)

        @Test
        fun `an active role is archived, logged once and audited as role archive`() {
            val roleId = createRole("ACTIVE_TO_ARCHIVE")

            post("$ROLE_PATH/$roleId/archive", "role.archive").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ARCHIVED") }
            }

            assertEquals("ARCHIVED", statusOf(roleId))
            assertEquals(listOf(Triple("ARCHIVE", "ACTIVE", "ARCHIVED")), logOf(roleId))
            assertEquals(1, audits("role.archive", roleId))
            assertEquals("ACTIVE", auditFromState("role.archive", roleId))
        }

        @Test
        fun `a disabled role is archived and still audited as role archive`() {
            val roleId = createRole("DISABLED_TO_ARCHIVE")
            post("$ROLE_PATH/$roleId/deactivate", "role.deactivate").andExpect {
                status { isOk() }
            }

            post("$ROLE_PATH/$roleId/archive", "role.archive").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ARCHIVED") }
            }

            assertEquals("ARCHIVED", statusOf(roleId))
            assertEquals(
                listOf(
                    Triple("DEACTIVATE", "ACTIVE", "DISABLED"),
                    Triple("ARCHIVE_DISABLED", "DISABLED", "ARCHIVED"),
                ),
                logOf(roleId),
            )
            // Every archive is one action name, whatever state it left; the from-state says which.
            assertEquals(1, audits("role.archive", roleId))
            assertEquals("DISABLED", auditFromState("role.archive", roleId))
        }

        @Test
        fun `activate and deactivate write their log rows and keep their audit action names`() {
            val roleId = createRole("LOGGED_EDGES")

            post("$ROLE_PATH/$roleId/deactivate", "role.deactivate").andExpect {
                status { isOk() }
            }
            post("$ROLE_PATH/$roleId/activate", "role.activate").andExpect { status { isOk() } }

            assertEquals(
                listOf(
                    Triple("DEACTIVATE", "ACTIVE", "DISABLED"),
                    Triple("ACTIVATE", "DISABLED", "ACTIVE"),
                ),
                logOf(roleId),
            )
            assertEquals(1, audits("role.deactivate", roleId))
            assertEquals(1, audits("role.activate", roleId))
        }

        @Test
        fun `a role with an active assignment is refused with a conflict naming the count`() {
            val roleId = createRole("IN_USE")
            val members = listOf(seedMember(), seedMember())
            members.forEach { assign(it, roleId) }

            val problem =
                post("$ROLE_PATH/$roleId/archive", "role.archive")
                    .andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("conflict") }
                    }.andReturn()
                    .response.contentAsString
            assertContains(problem, "2 active assignments")

            assertEquals("ACTIVE", statusOf(roleId))
            assertEquals(emptyList(), logOf(roleId))
            assertEquals(0, audits("role.archive", roleId))
        }

        @Test
        fun `a revoked assignment no longer blocks the archive`() {
            val roleId = createRole("REVOKED_FIRST")
            val member = seedMember()
            assign(member, roleId)
            dsl.execute(
                "UPDATE user_role_assignment SET status = 'REVOKED' WHERE role_id = ?",
                roleId,
            )

            post("$ROLE_PATH/$roleId/archive", "role.archive").andExpect { status { isOk() } }

            assertEquals("ARCHIVED", statusOf(roleId))
        }

        @Test
        fun `a system role cannot be archived`() {
            val tenantAdminRole =
                UUID.fromString(
                    dsl
                        .fetchValue(
                            "SELECT id FROM role WHERE organisation_id = ? " +
                                "AND role_code = 'TENANT_ADMIN'",
                            organisationId,
                        ).toString(),
                )

            post("$ROLE_PATH/$tenantAdminRole/archive", "role.archive").andExpect {
                status { isConflict() }
            }

            assertEquals("ACTIVE", statusOf(tenantAdminRole))
            assertEquals(emptyList(), logOf(tenantAdminRole))
        }

        @Test
        fun `an unknown role is not found`() {
            post("$ROLE_PATH/${uuidV7()}/archive", "role.archive").andExpect {
                status { isNotFound() }
            }
        }

        @Test
        fun `nothing leaves archived, and the code stays reserved`() {
            val roleId = createRole("TERMINAL")
            post("$ROLE_PATH/$roleId/archive", "role.archive").andExpect { status { isOk() } }

            listOf(
                "activate" to "role.activate",
                "deactivate" to "role.deactivate",
                "archive" to "role.archive",
            ).forEach { (edge, permission) ->
                post("$ROLE_PATH/$roleId/$edge", permission).andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("conflict") }
                }
            }
            val clash =
                post(ROLE_PATH, "role.create", """{"role_code":"TERMINAL","role_name":"Again"}""")
                    .andExpect { status { isConflict() } }
                    .andReturn()
                    .response.contentAsString
            assertContains(clash, "reserved")

            assertEquals("ARCHIVED", statusOf(roleId))
            assertEquals(1, logOf(roleId).size)
        }

        @Test
        fun `an archived role cannot be assigned, but is still readable`() {
            val roleId = createRole("NO_ASSIGN")
            post("$ROLE_PATH/$roleId/archive", "role.archive").andExpect { status { isOk() } }

            post(
                ApiPaths.ROLE_ASSIGNMENTS,
                "user.assign_role",
                assignmentBody(seedMember(), roleId),
            ).andExpect {
                status { isUnprocessableContent() }
                jsonPath("$.code") { value("invalid_operation") }
            }

            assertEquals(0, activeAssignments(roleId))
            get("$ROLE_PATH/$roleId").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ARCHIVED") }
            }
        }

        @Test
        fun `inviting a user to an archived role is refused`() {
            val roleId = createRole("NO_INVITE")
            post("$ROLE_PATH/$roleId/archive", "role.archive").andExpect { status { isOk() } }

            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    userProvisioningService.inviteUser(
                        InviteUserCommand(
                            organisationId = organisationId,
                            email = "archived-$roleId@invite.test",
                            username = "archived-$roleId",
                            displayName = "Archived Invitee",
                            membershipType = MembershipType.AUDITOR,
                            branchAssignments = emptyList(),
                            roleAssignments =
                                listOf(
                                    RoleAssignmentRequest(roleId, RoleAssignmentScopeType.TENANT),
                                ),
                            invitedBy = admin,
                            sendKeycloakInvite = false,
                            sendApplicationInvite = false,
                        ),
                    )
                }
            }

            assertEquals(0, activeAssignments(roleId))
        }

        @Test
        fun `archived roles are hidden from the default list and shown by the status filter`() {
            val live = createRole("LISTED_LIVE")
            val disabled = createRole("LISTED_DISABLED")
            val archived = createRole("LISTED_ARCHIVED")
            post("$ROLE_PATH/$disabled/deactivate", "role.deactivate").andExpect {
                status { isOk() }
            }
            post("$ROLE_PATH/$archived/archive", "role.archive").andExpect { status { isOk() } }

            val default = listing("q=LISTED_&size=100")
            assertTrue(live.toString() in default, "an ACTIVE role is listed")
            assertTrue(disabled.toString() in default, "a DISABLED role is listed")
            assertFalse(archived.toString() in default, "an ARCHIVED role is hidden by default")

            val onlyArchived = listing("q=LISTED_&size=100&status=ARCHIVED")
            assertTrue(archived.toString() in onlyArchived)
            assertFalse(live.toString() in onlyArchived)
            assertFalse(disabled.toString() in onlyArchived)

            val onlyActive = listing("q=LISTED_&size=100&status=ACTIVE")
            assertTrue(live.toString() in onlyActive)
            assertFalse(archived.toString() in onlyActive)
        }

        @Test
        fun `an archived role refuses permission edits and metadata edits`() {
            val roleId = createRole("FROZEN")
            val grantBody =
                post("$ROLE_PATH/$roleId/permissions", "role.assign_permission", GRANT_VIEW)
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            val grantId = ID.find(grantBody)!!.groupValues[1]
            post("$ROLE_PATH/$roleId/archive", "role.archive").andExpect { status { isOk() } }

            post("$ROLE_PATH/$roleId/permissions", "role.assign_permission", GRANT_ROLE_VIEW)
                .andExpect { status { isConflict() } }
            delete("$ROLE_PATH/$roleId/permissions/$grantId", "role.remove_permission")
                .andExpect { status { isConflict() } }
            patch("$ROLE_PATH/$roleId", "role.update", """{"role_name":"Renamed"}""")
                .andExpect { status { isConflict() } }

            assertEquals(setOf("branch.view"), permissionCodes(roleId))
            assertEquals(
                "FROZEN",
                dsl.fetchValue("SELECT role_name FROM role WHERE id = ?", roleId),
            )
        }

        @Test
        fun `a caller without the archive permission gets the named 403 and nothing changes`() {
            val roleId = createRole("GUARDED")
            val viewer = seedUser()
            fixture.grantTenantPermissionsExactly(organisationId, viewer, "role.view")
            val archiverWithoutView = seedUser()
            fixture.grantTenantPermissionsExactly(
                organisationId,
                archiverWithoutView,
                "role.archive",
            )

            post("$ROLE_PATH/$roleId/archive", "role.archive", actor = viewer).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("Missing permission: role.archive.") }
            }
            // The mutation without its view is refused, naming the view it lacks (ADR 0030).
            post("$ROLE_PATH/$roleId/archive", "role.archive", actor = archiverWithoutView)
                .andExpect {
                    status { isForbidden() }
                    jsonPath("$.detail") { value("Missing permission: role.view.") }
                }
            // And the same mutation with its view works.
            val archiver = seedUser()
            fixture.grantTenantPermissionsWithViews(organisationId, archiver, "role.archive")
            post("$ROLE_PATH/$roleId/archive", "role.archive", actor = archiver).andExpect {
                status { isOk() }
            }

            assertEquals("ARCHIVED", statusOf(roleId))
            assertEquals(1, audits("role.archive", roleId))
        }

        @Test
        fun `repeating an archive with the same idempotency key replays the first answer`() {
            val roleId = createRole("REPLAYED")
            val key = uuidV7().toString()

            repeat(2) {
                post("$ROLE_PATH/$roleId/archive", "role.archive", key = key).andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("ARCHIVED") }
                }
            }

            assertEquals(1, audits("role.archive", roleId))
            assertEquals(1, logOf(roleId).size)
        }

        @Test
        fun `the administrator roles hold the archive permission`() {
            val held =
                dsl
                    .fetch(
                        "SELECT p.permission_code FROM role r " +
                            "JOIN role_permission rp ON rp.role_id = r.id " +
                            "JOIN permission p ON p.id = rp.permission_id " +
                            "WHERE r.organisation_id = ? AND r.role_code = 'TENANT_ADMIN'",
                        organisationId,
                    ).map { it[0].toString() }
            assertTrue("role.archive" in held)
        }

        private fun assignmentBody(
            userId: UUID,
            roleId: UUID,
        ) = """{"user_id":"$userId","role_id":"$roleId","scope_type":"TENANT"}"""

        private fun assign(
            userId: UUID,
            roleId: UUID,
        ) {
            post(ApiPaths.ROLE_ASSIGNMENTS, "user.assign_role", assignmentBody(userId, roleId))
                .andExpect { status { isCreated() } }
        }

        private fun activeAssignments(roleId: UUID): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM user_role_assignment " +
                        "WHERE role_id = ? AND status = 'ACTIVE'",
                    roleId,
                ).toString()
                .toInt()

        private fun createRole(code: String): UUID {
            val body =
                post(ROLE_PATH, "role.create", """{"role_code":"$code","role_name":"$code"}""")
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            return UUID.fromString(ID.find(body)!!.groupValues[1])
        }

        private fun statusOf(roleId: UUID): String =
            dsl.fetchValue("SELECT status FROM role WHERE id = ?", roleId).toString()

        private fun permissionCodes(roleId: UUID): Set<String> =
            dsl
                .fetch(
                    "SELECT p.permission_code FROM role_permission rp " +
                        "JOIN permission p ON p.id = rp.permission_id WHERE rp.role_id = ?",
                    roleId,
                ).map { it[0].toString() }
                .toSet()

        private fun logOf(roleId: UUID): List<Triple<String, String, String>> =
            dsl
                .fetch(
                    "SELECT transition_name, status_from, status_to FROM role_transition_log " +
                        "WHERE entity_id = ? ORDER BY created_at, id",
                    roleId,
                ).map { Triple(it[0].toString(), it[1].toString(), it[2].toString()) }

        private fun audits(
            action: String,
            roleId: UUID,
        ): Int =
            dsl
                .fetchValue(
                    "SELECT count(*) FROM audit_event WHERE organisation_id = ? AND action = ? " +
                        "AND entity_id = ?",
                    organisationId,
                    action,
                    roleId,
                ).toString()
                .toInt()

        private fun auditFromState(
            action: String,
            roleId: UUID,
        ): String =
            dsl
                .fetchValue(
                    "SELECT metadata_jsonb ->> 'from' FROM audit_event " +
                        "WHERE organisation_id = ? AND action = ? AND entity_id = ?",
                    organisationId,
                    action,
                    roleId,
                ).toString()

        private fun listing(query: String): String =
            get("$ROLE_PATH?$query").andReturn().response.contentAsString

        private fun get(path: String): ResultActionsDsl =
            mockMvc.get(path) {
                with(authentication(token("role.view", admin)))
            }

        private fun delete(
            path: String,
            permission: String,
        ): ResultActionsDsl =
            mockMvc.delete(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                with(authentication(token(permission, admin)))
            }

        private fun patch(
            path: String,
            permission: String,
            body: String,
        ): ResultActionsDsl =
            mockMvc.patch(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token(permission, admin)))
            }

        private fun post(
            path: String,
            permission: String,
            body: String = "{}",
            actor: UUID = admin,
            key: String = uuidV7().toString(),
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token(permission, actor)))
            }

        // The administrator holds every tenant permission in the database; the token carries the
        // coarse authority the route's gate reads.
        private fun token(
            permission: String,
            actor: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = actor,
                keycloakSubject = "user-$actor",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = null,
                email = "admin@rai.test",
                fullName = "Role Archive Admin",
                permissions = setOf(permission, "role.view"),
            ),
        )

        /** A user who is an ACTIVE member of the organisation, so a role can be assigned. */
        private fun seedMember(): UUID {
            val member = seedUser()
            // A tenant administrator membership is the cheapest real one; the role under test is
            // assigned on top of it, which is what the assignment count sees.
            fixture.grantTenantAdmin(organisationId, member)
            return member
        }

        private fun seedUser(): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "rai-$id")
                .set(USER_ACCOUNT.EMAIL, "rai-$id@seed.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "rai")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            val ROLE_PATH = ApiPaths.ROLES
            val ID = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            const val GRANT_VIEW = """{"permission_code":"branch.view"}"""
            const val GRANT_ROLE_VIEW = """{"permission_code":"role.view"}"""
        }
    }
