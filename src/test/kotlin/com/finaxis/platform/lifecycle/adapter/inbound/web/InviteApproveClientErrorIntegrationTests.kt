package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.BranchAssignmentRequest
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.UserDeactivationAssignmentRevoker
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * Full-stack proof that client-caused failures in the invite and approve flows answer a 4xx
 * problem and leave nothing behind: a state conflict (a duplicate invitation, an approval whose
 * prerequisites are not met) is a 409, a bad reference in the body (an unknown, foreign or inactive
 * role or branch) is a 422, a malformed value the DTO is too lax for is a 400, and every refusal
 * carries `code`, `detail` and `request_id` while writing no row at all.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class InviteApproveClientErrorIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val lifecycleService: FoundationLifecycleService,
        private val userStore: UserProvisioningStore,
        private val branchProvisioningService: BranchProvisioningService,
        private val assignmentRevoker: UserDeactivationAssignmentRevoker,
        private val auditService: AuditService,
        private val eventPublisher: TransitionEventPublisher,
        private val clock: Clock,
        private val permissionGuard: PermissionGuard,
        private val transactionManager: PlatformTransactionManager,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("invite-error", admin)
        private val domain = "invite-error.test"

        @Test
        fun `a duplicate invitation is a 409 and writes no row`() {
            val email = "dup-${uuidV7()}@$domain"
            val username = "dup-${uuidV7()}".take(30)
            invite(inviteBody(email, username)).andExpect { status { isCreated() } }
            val before = rowCounts()

            invite(inviteBody(email, username)).andExpect {
                status { isConflict() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("conflict") }
                jsonPath("$.detail") {
                    value("This user already has a membership in the selected organisation.")
                }
                jsonPath("$.request_id") { isNotEmpty() }
            }

            assertEquals(before, rowCounts())
        }

        @Test
        fun `inviting an existing member under another case of the email is the same 409`() {
            val member = seedUser("member")
            fixture.grantTenantPermissionsOnly(organisationId, member, "user.view")
            val before = rowCounts()

            invite(inviteBody(emailOf(member).uppercase(), "fresh-${uuidV7()}".take(30)))
                .andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("conflict") }
                    jsonPath("$.request_id") { isNotEmpty() }
                }

            assertEquals(before, rowCounts())
        }

        @Test
        fun `a new email with a username already in use is a 409 and writes no row`() {
            val taken = seedUser("taken")
            val before = rowCounts()

            invite(inviteBody("new-${uuidV7()}@$domain", usernameOf(taken).uppercase()))
                .andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("conflict") }
                    jsonPath("$.detail") { value("That username is already in use.") }
                    jsonPath("$.request_id") { isNotEmpty() }
                }

            assertEquals(before, rowCounts())
        }

        @Test
        fun `an unknown foreign or inactive role is a 422 and writes no row`() {
            val foreignOrganisation = foreignOrganisation()
            val inactiveRole = insertRole(organisationId, "DISABLED")
            val before = rowCounts()

            listOf(uuidV7(), roleId(foreignOrganisation), inactiveRole).forEach { roleId ->
                invite(inviteBody(roleId = roleId)).andExpect {
                    status { isUnprocessableContent() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.code") { value("invalid_operation") }
                    jsonPath("$.detail") {
                        value(
                            "A role in the request was not found or is not active in the " +
                                "organisation.",
                        )
                    }
                    jsonPath("$.request_id") { isNotEmpty() }
                }
            }

            assertEquals(before, rowCounts())
        }

        @Test
        fun `an unknown foreign or suspended branch is a 422 and writes no row`() {
            val foreignOrganisation = foreignOrganisation()
            val suspendedBranch = insertBranch(organisationId, "SUSPENDED")
            val before = rowCounts()

            listOf(uuidV7(), headOfficeId(foreignOrganisation), suspendedBranch).forEach { branch ->
                invite(inviteBody(branchId = branch)).andExpect {
                    status { isUnprocessableContent() }
                    jsonPath("$.code") { value("invalid_operation") }
                    jsonPath("$.detail") {
                        value(
                            "A branch in the request was not found or is not active in the " +
                                "organisation.",
                        )
                    }
                    jsonPath("$.request_id") { isNotEmpty() }
                }
            }

            assertEquals(before, rowCounts())
        }

        @Test
        fun `an invitation that loses the race at the unique index is a 409 and writes no row`() {
            // The email and username lookups are courtesies; a request that lost a race saw both
            // free. Blinding them makes the second create hit uq_user_account_lower_email or
            // uq_user_account_lower_username, which must still answer 409 and leave nothing.
            val email = "race-${uuidV7()}@$domain"
            val username = "race-${uuidV7()}".take(30)
            invite(inviteBody(email, username)).andExpect { status { isCreated() } }
            val before = rowCounts()

            listOf(
                email to "other-${uuidV7()}".take(30),
                "other-${uuidV7()}@$domain" to username.uppercase(),
            ).forEach { (raceEmail, raceUsername) ->
                val failure =
                    assertFailsWith<ConflictException> {
                        TransactionTemplate(transactionManager).execute {
                            serviceWithoutPrecheck()
                                .inviteUser(inviteCommand(raceEmail, raceUsername))
                        }
                    }
                assertEquals(
                    "A user with this email or username already exists; retry the invitation.",
                    failure.safeDetail,
                )
                assertIs<DuplicateKeyException>(failure.cause)
            }

            assertEquals(before, rowCounts())
        }

        private fun serviceWithoutPrecheck() =
            UserProvisioningService(
                lifecycleService,
                object : UserProvisioningStore by userStore {
                    override fun findUserIdByEmail(email: String): UUID? = null

                    override fun usernameInUse(username: String): Boolean = false
                },
                branchProvisioningService,
                assignmentRevoker,
                auditService,
                eventPublisher,
                clock,
                permissionGuard,
            )

        private fun inviteCommand(
            email: String,
            username: String,
        ) = InviteUserCommand(
            organisationId = organisationId,
            email = email,
            username = username,
            displayName = "Invitee",
            membershipType = MembershipType.STAFF,
            primaryBranchId = headOfficeId(organisationId),
            branchAssignments =
                listOf(
                    BranchAssignmentRequest(
                        headOfficeId(organisationId),
                        BranchAssignmentType.HOME,
                    ),
                ),
            roleAssignments =
                listOf(
                    RoleAssignmentRequest(roleId(organisationId), RoleAssignmentScopeType.TENANT),
                ),
            invitedBy = admin,
            sendKeycloakInvite = true,
            sendApplicationInvite = false,
        )

        @Test
        fun `a lax email the service refuses is a 400 and writes no row`() {
            val before = rowCounts()

            invite(inviteBody("user@localhost", "lax-${uuidV7()}".take(30))).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.detail") { value("A valid email address is required.") }
                jsonPath("$.request_id") { isNotEmpty() }
            }

            assertEquals(before, rowCounts())
        }

        @Test
        fun `a caller without the invite permission gets 403 before any 422`() {
            val narrow = seedUser("narrow")
            fixture.grantTenantPermissionsOnly(organisationId, narrow, "user.view")
            val before = rowCounts()

            post(
                ApiPaths.TENANT_USERS,
                inviteBody(roleId = uuidV7()),
                narrow,
                setOf("user.invite"),
            ).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
            }

            assertEquals(before, rowCounts())
        }

        @Test
        fun `approving a membership whose assignments are gone is a 409 and changes nothing`() {
            val membership = invitedMembership()
            val checker = checker()
            dsl.execute("DELETE FROM user_branch_assignment WHERE user_id = ?", membership.userId)

            approve(membership, checker).andExpect {
                status { isConflict() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("conflict") }
                jsonPath("$.detail") {
                    value(
                        "The membership cannot be approved until the user has an active branch " +
                            "assignment.",
                    )
                }
                jsonPath("$.request_id") { isNotEmpty() }
            }
            assertApprovalChangedNothing(membership)

            dsl.execute("DELETE FROM user_role_assignment WHERE user_id = ?", membership.userId)
            restoreBranchAssignment(membership.userId)

            approve(membership, checker).andExpect {
                status { isConflict() }
                jsonPath("$.detail") {
                    value(
                        "The membership cannot be approved until the user has an active role " +
                            "assignment.",
                    )
                }
                jsonPath("$.request_id") { isNotEmpty() }
            }
            assertApprovalChangedNothing(membership)
        }

        @Test
        fun `the platform checker route answers the same 409 for unmet prerequisites`() {
            val membership = invitedMembership()
            val platformAdmin = seedUser("platform-admin").also(fixture::grantPlatformSuperAdmin)
            dsl.execute("DELETE FROM user_role_assignment WHERE user_id = ?", membership.userId)

            mockMvc
                .post(
                    "${ApiPaths.PLATFORM_TENANTS}/$organisationId/memberships/" +
                        "${membership.id}/activate",
                ) {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    with(
                        authentication(token(platformAdmin, PlatformOrganisation.ID, APPROVE)),
                    )
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("conflict") }
                    jsonPath("$.detail") {
                        value(
                            "The membership cannot be approved until the user has an active " +
                                "role assignment.",
                        )
                    }
                    jsonPath("$.request_id") { isNotEmpty() }
                }
            assertApprovalChangedNothing(membership)
        }

        @Test
        fun `approving a user whose account cannot start provisioning is a 409`() {
            val membership = invitedMembership()
            dsl.execute(
                "UPDATE user_account SET status = 'SUSPENDED' WHERE id = ?",
                membership.userId,
            )

            approve(membership, checker()).andExpect {
                status { isConflict() }
                jsonPath("$.detail") {
                    value("The user account is not in a state that allows identity provisioning.")
                }
                jsonPath("$.request_id") { isNotEmpty() }
            }

            assertEquals("SUSPENDED", userStatus(membership.userId))
            assertApprovalChangedNothing(membership)
        }

        @Test
        fun `an approval with its prerequisites met still queues provisioning`() {
            val membership = invitedMembership()

            approve(membership, checker()).andExpect { status { isAccepted() } }

            assertEquals("PROVISIONING_IDP", userStatus(membership.userId))
        }

        @Test
        fun `a caller without the approve permission gets 403 before any 409`() {
            val membership = invitedMembership()
            dsl.execute("DELETE FROM user_role_assignment WHERE user_id = ?", membership.userId)
            val narrow = seedUser("narrow")
            fixture.grantTenantPermissionsOnly(organisationId, narrow, "user.view")

            approve(membership, narrow).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
            }
            assertApprovalChangedNothing(membership)
        }

        private fun invitedMembership(): InvitedMembership {
            val email = "invitee-${uuidV7()}@$domain"
            invite(inviteBody(email, "inv-${uuidV7()}".take(30))).andExpect {
                status { isCreated() }
            }
            val userId =
                requireNotNull(
                    dsl
                        .select(USER_ACCOUNT.ID)
                        .from(USER_ACCOUNT)
                        .where(USER_ACCOUNT.EMAIL.eq(email))
                        .fetchOne(USER_ACCOUNT.ID),
                )
            val membershipId =
                dsl
                    .fetchOne(
                        "SELECT id FROM user_organisation_membership " +
                            "WHERE organisation_id = ? AND user_id = ?",
                        organisationId,
                        userId,
                    )!!
                    .get(0, UUID::class.java)
            return InvitedMembership(membershipId, userId)
        }

        private fun checker(): UUID =
            seedUser("checker").also {
                fixture.grantTenantPermissionsOnly(
                    organisationId,
                    it,
                    "user.approve",
                    "membership.view",
                )
            }

        private fun approve(
            membership: InvitedMembership,
            actor: UUID,
        ): ResultActionsDsl =
            mockMvc.post("${ApiPaths.MEMBERSHIPS}/${membership.id}/activate") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                with(authentication(token(actor, organisationId, APPROVE)))
            }

        private fun assertApprovalChangedNothing(membership: InvitedMembership) {
            assertEquals(
                "PENDING_APPROVAL",
                dsl
                    .fetchOne(
                        "SELECT membership_status FROM user_organisation_membership WHERE id = ?",
                        membership.id,
                    )!!
                    .get(0, String::class.java),
            )
            assertEquals(
                0,
                count(
                    "SELECT COUNT(*) FROM identity_dispatch_log WHERE user_id = ?",
                    membership.userId,
                ),
            )
            assertEquals(
                0,
                count(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND action = 'user.approve' AND entity_id = ?",
                    organisationId,
                    membership.userId,
                ),
            )
            assertEquals(
                0,
                count(
                    "SELECT COUNT(*) FROM user_organisation_membership_transition_log " +
                        "WHERE entity_id = ?",
                    membership.id,
                ),
            )
        }

        private fun invite(body: String): ResultActionsDsl =
            post(ApiPaths.TENANT_USERS, body, admin, setOf("user.invite"))

        private fun post(
            path: String,
            body: String,
            actor: UUID,
            authorities: Set<String>,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token(actor, organisationId, authorities)))
            }

        private fun inviteBody(
            email: String = "invitee-${uuidV7()}@$domain",
            username: String = "inv-${uuidV7()}".take(30),
            roleId: UUID = roleId(organisationId),
            branchId: UUID = headOfficeId(organisationId),
        ): String =
            """{"email":"$email","username":"$username","display_name":"Invitee",""" +
                """"membership_type":"STAFF","primary_branch_id":"$branchId",""" +
                """"branch_assignments":[{"branch_id":"$branchId","assignment_type":"HOME"}],""" +
                """"role_assignments":[{"role_id":"$roleId","scope_type":"TENANT"}]}"""

        private fun foreignOrganisation(): UUID =
            fixture.createActiveOrganisation("foreign-invite", seedUser("foreign-admin"))

        private fun roleId(organisation: UUID): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = 'TENANT_ADMIN'",
                    organisation,
                )!!
                .get(0, UUID::class.java)

        private fun headOfficeId(organisation: UUID): UUID =
            dsl
                .fetch("SELECT id FROM branch WHERE organisation_id = ?", organisation)
                .first()
                .get(0, UUID::class.java)

        private fun insertRole(
            organisation: UUID,
            status: String,
        ): UUID {
            val now = OffsetDateTime.now()
            return requireNotNull(
                dsl
                    .insertInto(ROLE)
                    .set(ROLE.ORGANISATION_ID, organisation)
                    .set(ROLE.ROLE_CODE, "TEST_${uuidV7()}")
                    .set(ROLE.ROLE_NAME, "Test role")
                    .set(ROLE.SYSTEM_ROLE, false)
                    .set(ROLE.STATUS, status)
                    .set(ROLE.CREATED_AT, now)
                    .set(ROLE.CREATED_BY, SystemActor.ID)
                    .set(ROLE.UPDATED_AT, now)
                    .set(ROLE.UPDATED_BY, SystemActor.ID)
                    .returning(ROLE.ID)
                    .fetchOne()
                    ?.id,
            )
        }

        // A second branch, copied from the head office so every NOT NULL column is satisfied.
        private fun insertBranch(
            organisation: UUID,
            status: String,
        ): UUID {
            val id = uuidV7()
            dsl.execute(
                "INSERT INTO branch (id, organisation_id, branch_code, branch_name, branch_type, " +
                    "status, timezone, created_at, created_by, updated_at, updated_by) " +
                    "SELECT ?, organisation_id, ?, 'Spare', branch_type, ?, timezone, " +
                    "created_at, created_by, updated_at, updated_by " +
                    "FROM branch WHERE organisation_id = ? LIMIT 1",
                id,
                "SPARE-${id.toString().take(8)}",
                status,
                organisation,
            )
            return id
        }

        private fun restoreBranchAssignment(userId: UUID) {
            dsl.execute(
                "INSERT INTO user_branch_assignment (organisation_id, user_id, branch_id, " +
                    "assignment_type, status, assigned_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'HOME', 'ACTIVE', now(), now(), now())",
                organisationId,
                userId,
                headOfficeId(organisationId),
            )
        }

        private fun rowCounts(): List<Int> =
            listOf(
                count("SELECT COUNT(*) FROM user_account"),
                count(
                    "SELECT COUNT(*) FROM user_organisation_membership WHERE organisation_id = ?",
                    organisationId,
                ),
                count(
                    "SELECT COUNT(*) FROM user_role_assignment WHERE organisation_id = ?",
                    organisationId,
                ),
                count(
                    "SELECT COUNT(*) FROM user_branch_assignment WHERE organisation_id = ?",
                    organisationId,
                ),
                count(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND action = 'user.invite'",
                    organisationId,
                ),
            )

        private fun count(
            sql: String,
            vararg bindings: Any,
        ): Int = dsl.fetchOne(sql, *bindings)!!.get(0, Int::class.java)

        private fun userStatus(userId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        private fun emailOf(userId: UUID): String =
            dsl
                .fetchOne("SELECT email FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        private fun usernameOf(userId: UUID): String =
            dsl
                .fetchOne("SELECT username FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

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
                email = "user@$domain",
                fullName = "Invite Error User",
                permissions = permissions,
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-${id.toString().take(12)}")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@invite-error.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private data class InvitedMembership(
            val id: UUID,
            val userId: UUID,
        )

        private companion object {
            val APPROVE = setOf("user.approve")
        }
    }
