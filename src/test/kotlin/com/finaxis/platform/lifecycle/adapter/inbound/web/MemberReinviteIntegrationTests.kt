package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.FailedJobRequeuer
import com.finaxis.platform.common.jobs.JobRequeueOutcome
import com.finaxis.platform.common.jobs.deterministicJobId
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.BranchAssignmentRequest
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.LifecycleErrorCodes
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.UserDeactivationAssignmentRevoker
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.allControlsOn
import com.finaxis.platform.lifecycle.application.port.outbound.UserProvisioningStore
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.TransientDataAccessResourceException
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A revoked member is invited again through the existing invite route (#172, ADR 0037): the
 * membership row is reused and reset, direct overrides are cleared, any other state is still the
 * 409, a checker approves again (never the maker of the re-invitation), and the approval queues
 * a fresh dispatch under a new generation. The reset, the transition and its audit row commit or
 * roll back together.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("LargeClass")
class MemberReinviteIntegrationTests
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
        private val permissionResolver: EffectivePermissionResolver,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("reinvite", admin)
        private val domain = "reinvite.test"

        @Test
        fun `a revoked member is invited again on the same row and every reset field is reset`() {
            val member = activeMember()
            val previousMaker = membershipColumn(member.membershipId, "created_by")
            plantFirstLifeLeftovers(member)
            revoke(member).andExpect { status { isOk() } }
            val reinviter =
                seedUser(
                    "reinviter",
                ).also { fixture.grantTenantAdmin(organisationId, it) }
            val secondBranch = insertBranch()
            reinvite(
                member,
                actor = reinviter,
                body =
                    inviteBody(
                        member.email,
                        membershipType = "ADMIN",
                        branchId = secondBranch,
                        sendKeycloakInvite = false,
                        sendApplicationInvite = true,
                    ),
            ).andExpect {
                status { isCreated() }
                jsonPath("$.user_id") { value(member.userId.toString()) }
                jsonPath("$.membership_id") { value(member.membershipId.toString()) }
                jsonPath("$.membership_status") { value("PENDING_APPROVAL") }
            }

            // The one row is reused: no second membership, no second user.
            assertEquals(1, membershipCount(member.userId))
            assertRowReset(member, reinviter, previousMaker, secondBranch)
            assertAssignmentsRenewed(member, secondBranch)
            assertTransitionRecorded(member)
        }

        private fun assertRowReset(
            member: Member,
            reinviter: UUID,
            previousMaker: Any?,
            secondBranch: UUID,
        ) {
            val row =
                dsl
                    .fetchOne(
                        "SELECT membership_status, membership_type, primary_branch_id, " +
                            "created_by, joined_at, suspended_at, revoked_at, status_reason, " +
                            "pending_keycloak_invite, pending_application_invite " +
                            "FROM user_organisation_membership WHERE id = ?",
                        member.membershipId,
                    )!!
            assertEquals("PENDING_APPROVAL", row["membership_status"])
            assertEquals("ADMIN", row["membership_type"])
            assertEquals(secondBranch, row["primary_branch_id"])
            // The maker is the re-inviter, not whoever invited the first time.
            assertEquals(reinviter, row["created_by"])
            assertNotEquals(previousMaker, row["created_by"])
            assertNull(row["joined_at"])
            assertNull(row["suspended_at"])
            assertNull(row["revoked_at"])
            assertNull(row["status_reason"])
            assertEquals(false, row["pending_keycloak_invite"])
            assertEquals(true, row["pending_application_invite"])
            // Nothing the member held directly survives into the new life.
            assertEquals(
                0,
                count(
                    "SELECT COUNT(*) FROM membership_permission WHERE membership_id = ?",
                    member.membershipId,
                ),
            )
        }

        /** The first life's assignments stay as history; the invitation made fresh ones. */
        private fun assertAssignmentsRenewed(
            member: Member,
            secondBranch: UUID,
        ) {
            val assignments =
                dsl.fetch(
                    "SELECT status, branch_id FROM user_branch_assignment " +
                        "WHERE organisation_id = ? AND user_id = ? ORDER BY created_at, id",
                    organisationId,
                    member.userId,
                )
            assertEquals(listOf("REVOKED", "ACTIVE"), assignments.map { it["status"] })
            assertEquals(secondBranch, assignments.last()["branch_id"])
            assertEquals(
                1,
                count(
                    "SELECT COUNT(*) FROM user_role_assignment WHERE organisation_id = ? " +
                        "AND user_id = ? AND status = 'ACTIVE'",
                    organisationId,
                    member.userId,
                ),
            )
        }

        /** The transition's one record: the log row and the one audit row, keyed by the member. */
        private fun assertTransitionRecorded(member: Member) {
            val log =
                dsl.fetchOne(
                    "SELECT status_from, status_to FROM " +
                        "user_organisation_membership_transition_log " +
                        "WHERE entity_id = ? AND transition_name = 'REINVITE'",
                    member.membershipId,
                )!!
            assertEquals("REVOKED", log["status_from"])
            assertEquals("PENDING_APPROVAL", log["status_to"])
            assertEquals(
                1,
                count(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND action = 'membership.reinvite' AND entity_id = ? " +
                        "AND outcome = 'SUCCESS'",
                    organisationId,
                    member.membershipId,
                ),
            )
        }

        @Test
        fun `a member in any state other than revoked is still the 409 and changes nothing`() {
            val pending = pendingMember()
            val active = activeMember()
            val suspended = activeMember()
            dsl.execute(
                "UPDATE user_organisation_membership SET membership_status = 'SUSPENDED' " +
                    "WHERE id = ?",
                suspended.membershipId,
            )

            listOf(
                pending to "PENDING_APPROVAL",
                active to "ACTIVE",
                suspended to "SUSPENDED",
            ).forEach { (member, state) ->
                val before = rowCounts(member)

                reinvite(member).andExpect {
                    status { isConflict() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.code") { value("conflict") }
                    jsonPath("$.detail") {
                        value("This user already has a membership in the selected organisation.")
                    }
                    jsonPath("$.request_id") { isNotEmpty() }
                }

                assertEquals(state, membershipColumn(member.membershipId, "membership_status"))
                assertEquals(before, rowCounts(member), "a refused invitation wrote a row")
                assertEquals(0, reinviteLogRows(member))
            }
        }

        @Test
        fun `a member revoked a second time can be invited again once more`() {
            val member = activeMember()
            repeat(2) {
                revoke(member).andExpect { status { isOk() } }
                reinvite(member).andExpect { status { isCreated() } }
                approve(member, checker()).andExpect { status { is2xxSuccessful() } }
                assertEquals("ACTIVE", membershipColumn(member.membershipId, "membership_status"))
            }

            assertEquals(2, reinviteLogRows(member))
            assertEquals(1, membershipCount(member.userId))
        }

        @Test
        fun `the maker of a re-invitation cannot approve it and another checker can`() {
            val member = activeMember()
            revoke(member).andExpect { status { isOk() } }
            val reinviter =
                seedUser(
                    "reinviter",
                ).also { fixture.grantTenantAdmin(organisationId, it) }
            reinvite(member, actor = reinviter).andExpect { status { isCreated() } }

            approve(member, reinviter).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value(LifecycleErrorCodes.APPROVER_IS_MEMBERSHIP_MAKER) }
            }
            assertEquals(
                "PENDING_APPROVAL",
                membershipColumn(member.membershipId, "membership_status"),
            )
            // The refusal is audited as the denial it is, written independently of the rollback.
            assertEquals(
                1,
                count(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND action = 'user.approve' AND outcome = 'DENIED' AND entity_id = ?",
                    organisationId,
                    member.userId,
                ),
            )

            // The first life's maker (admin) is not this life's maker, so they are a checker again.
            approve(member, admin).andExpect { status { is2xxSuccessful() } }

            assertEquals("ACTIVE", membershipColumn(member.membershipId, "membership_status"))
        }

        @Test
        fun `approving a re-invitation sends a fresh invite under a new generation and job id`() {
            val member = activeMember(sendApplicationInvite = true)
            val firstKey = "${member.userId}:$organisationId:APPLICATION_INVITE"
            assertEquals(listOf(firstKey), dispatchKeys(member.userId, "APPLICATION_INVITE"))
            // The first invitation was delivered: this is the row that, keyed by user and
            // organisation alone, made a re-approved member's invite a silent no-op.
            dsl.execute(
                "UPDATE identity_dispatch_log SET status = 'SUCCEEDED' WHERE dispatch_key = ?",
                firstKey,
            )
            revoke(member).andExpect { status { isOk() } }
            reinvite(member, body = inviteBody(member.email, sendApplicationInvite = true))
                .andExpect { status { isCreated() } }
            // Nothing is dispatched by the invitation itself: dispatch happens at approval.
            assertEquals(listOf(firstKey), dispatchKeys(member.userId, "APPLICATION_INVITE"))

            approve(member, checker()).andExpect { status { is2xxSuccessful() } }

            val secondKey = "$firstKey:G1"
            assertEquals(
                setOf(firstKey, secondKey),
                dispatchKeys(member.userId, "APPLICATION_INVITE").toSet(),
            )
            assertEquals("SUCCEEDED", dispatchStatus(firstKey))
            assertEquals("PENDING", dispatchStatus(secondKey))
            assertNotEquals(deterministicJobId(firstKey), deterministicJobId(secondKey))
            // The event that becomes the job carries the new key, and so the new job id.
            assertEquals(
                1,
                count(
                    "SELECT COUNT(*) FROM outbox_record WHERE payload LIKE '%' || ? || '%'",
                    secondKey,
                ),
            )
        }

        @Test
        fun `a re-invited user still waiting on provisioning is provisioned under the new key`() {
            val member = pendingMember(newUser = true)
            approve(member, checker()).andExpect { status { isAccepted() } }
            val firstKey = "$organisationId:${member.userId}:KEYCLOAK_PROVISIONING"
            assertEquals("PROVISIONING_IDP", userStatus(member.userId))
            // What the first job leaves when the revoke cancels it: a FAILED dispatch.
            dsl.execute(
                "UPDATE identity_dispatch_log SET status = 'FAILED' WHERE dispatch_key = ?",
                firstKey,
            )
            revoke(member).andExpect { status { isOk() } }
            reinvite(member).andExpect { status { isCreated() } }

            // Not the 409 that a repeat approval of a dispatch never recorded is.
            approve(member, checker()).andExpect { status { isAccepted() } }

            assertEquals(
                "PENDING_APPROVAL",
                membershipColumn(member.membershipId, "membership_status"),
            )
            assertEquals("FAILED", dispatchStatus(firstKey))
            assertEquals("PENDING", dispatchStatus("$firstKey:G1"))
        }

        @Test
        fun `a re-approved member is served the narrower new permissions not the cached old`() {
            val member = activeMember()
            val membership = member.membershipId
            // The first life resolves as TENANT_ADMIN and fills the cache (as the principal loader
            // does on each request): the entry survives the revoke, masked while not ACTIVE.
            val firstLife = permissionResolver.effectivePermissions(membership)
            assertTrue("user.invite" in firstLife, "the first life holds the administrator's codes")
            revoke(member).andExpect { status { isOk() } }

            reinvite(
                member,
                body = inviteBody(member.email, roleId = roleId("BRANCH_OPERATOR")),
            ).andExpect { status { isCreated() } }
            approve(member, checker()).andExpect { status { is2xxSuccessful() } }

            val secondLife = permissionResolver.effectivePermissions(membership)
            assertTrue(secondLife.isNotEmpty(), "the new role grants something")
            assertTrue("user.invite" !in secondLife, "the first life's cached set was served")
            assertTrue(secondLife.size < firstLife.size)
        }

        @Test
        fun `a revoke re-invite and approval store no event publication`() {
            // The cache eviction must not be a registry-backed after-commit listener: Spring
            // Modulith would insert an event_publication row for every transition event of every
            // aggregate inside the publishing transaction (ADR 0018 keeps the table empty).
            val before = eventPublicationRows()

            val member = activeMember()
            revoke(member).andExpect { status { isOk() } }
            reinvite(member).andExpect { status { isCreated() } }
            approve(member, checker()).andExpect { status { is2xxSuccessful() } }

            assertEquals("ACTIVE", membershipColumn(member.membershipId, "membership_status"))
            assertEquals(before, eventPublicationRows())
        }

        @Test
        fun `a re-invitation that fails part way rolls back the reset the log and the audit row`() {
            val member = activeMember()
            plantFirstLifeLeftovers(member)
            revoke(member).andExpect { status { isOk() } }
            val reinviter =
                seedUser(
                    "reinviter",
                ).also { fixture.grantTenantAdmin(organisationId, it) }
            val snapshot = revokedSnapshot(member)

            // The failure comes after the reset, from the role assignment the invitation makes
            // last, so the reset, the cleared overrides and the transition are all in flight.
            val failure =
                assertFailsWith<TransientDataAccessResourceException> {
                    TransactionTemplate(transactionManager).execute {
                        serviceFailingOnRoleAssignment()
                            .inviteUser(inviteCommand(member, reinviter))
                    }
                }
            assertEquals("The role assignment failed.", failure.message)

            assertEquals(snapshot, revokedSnapshot(member), "the revoked row is exactly as it was")
            assertEquals(0, reinviteLogRows(member))
            assertEquals(
                0,
                count(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND action = 'membership.reinvite' AND entity_id = ?",
                    organisationId,
                    member.membershipId,
                ),
            )
            assertEquals(
                1,
                count(
                    "SELECT COUNT(*) FROM membership_permission WHERE membership_id = ?",
                    member.membershipId,
                ),
                "the direct override is still there",
            )
            // And the invitation still works afterwards, so nothing was left half done.
            reinvite(member).andExpect { status { isCreated() } }
            assertEquals(1, reinviteLogRows(member))
        }

        private fun serviceFailingOnRoleAssignment() =
            UserProvisioningService(
                lifecycleService,
                object : UserProvisioningStore by userStore {
                    override fun assignRole(
                        organisationId: UUID,
                        userId: UUID,
                        roleId: UUID,
                        scopeType: RoleAssignmentScopeType,
                        branchId: UUID?,
                        actorId: UUID,
                    ): UUID =
                        throw TransientDataAccessResourceException("The role assignment failed.")
                },
                branchProvisioningService,
                assignmentRevoker,
                auditService,
                eventPublisher,
                clock,
                permissionGuard,
                FailedJobRequeuer { JobRequeueOutcome.NOT_FAILED },
                allControlsOn(),
            )

        private fun inviteCommand(
            member: Member,
            actor: UUID,
        ) = InviteUserCommand(
            organisationId = organisationId,
            email = member.email,
            username = "inv-${uuidV7()}".take(30),
            displayName = "Invitee",
            membershipType = MembershipType.ADMIN,
            primaryBranchId = headOfficeId(),
            branchAssignments =
                listOf(BranchAssignmentRequest(headOfficeId(), BranchAssignmentType.HOME)),
            roleAssignments =
                listOf(RoleAssignmentRequest(roleId(), RoleAssignmentScopeType.TENANT)),
            invitedBy = actor,
            sendKeycloakInvite = true,
            sendApplicationInvite = false,
        )

        /** The columns the reset writes, plus the overrides and the logs it must not have moved. */
        private fun revokedSnapshot(member: Member): List<Any?> =
            listOf(
                dsl
                    .fetchOne(
                        "SELECT membership_status, membership_type, primary_branch_id, " +
                            "created_by, joined_at, suspended_at, revoked_at, status_reason, " +
                            "pending_keycloak_invite, pending_application_invite, row_version " +
                            "FROM user_organisation_membership WHERE id = ?",
                        member.membershipId,
                    )!!
                    .intoMap(),
                count(
                    "SELECT COUNT(*) FROM user_branch_assignment WHERE user_id = ?",
                    member.userId,
                ),
                count(
                    "SELECT COUNT(*) FROM user_role_assignment WHERE user_id = ?",
                    member.userId,
                ),
            )

        // ----- fixtures -----

        /** A user with an identity who is invited, approved and so an ACTIVE member. */
        private fun activeMember(sendApplicationInvite: Boolean = false): Member {
            val member = pendingMember(sendApplicationInvite = sendApplicationInvite)
            approve(member, checker()).andExpect { status { is2xxSuccessful() } }
            assertEquals("ACTIVE", membershipColumn(member.membershipId, "membership_status"))
            return member
        }

        /**
         * A user invited and awaiting approval. By default an existing, linked user (an approval
         * activates at once); [newUser] makes a draft account that needs Keycloak provisioning.
         */
        private fun pendingMember(
            newUser: Boolean = false,
            sendApplicationInvite: Boolean = false,
        ): Member {
            val email: String
            val userId: UUID
            if (newUser) {
                email = "member-${uuidV7()}@$domain"
                invite(inviteBody(email, sendApplicationInvite = sendApplicationInvite))
                    .andExpect { status { isCreated() } }
                userId =
                    requireNotNull(
                        dsl
                            .select(USER_ACCOUNT.ID)
                            .from(USER_ACCOUNT)
                            .where(USER_ACCOUNT.EMAIL.eq(email))
                            .fetchOne(USER_ACCOUNT.ID),
                    )
            } else {
                userId = seedUser("member")
                email = emailOf(userId)
                linkIdentity(userId)
                invite(inviteBody(email, sendApplicationInvite = sendApplicationInvite))
                    .andExpect { status { isCreated() } }
            }
            val membershipId =
                dsl
                    .fetchOne(
                        "SELECT id FROM user_organisation_membership " +
                            "WHERE organisation_id = ? AND user_id = ?",
                        organisationId,
                        userId,
                    )!!
                    .get(0, UUID::class.java)
            return Member(membershipId, userId, email)
        }

        /** What a first life leaves behind that the second must not inherit. */
        private fun plantFirstLifeLeftovers(member: Member) {
            val permissionId =
                dsl
                    .fetchOne("SELECT id FROM permission WHERE permission_code = 'branch.view'")!!
                    .get(0, UUID::class.java)
            dsl.execute(
                "INSERT INTO membership_permission (organisation_id, membership_id, " +
                    "permission_id, effect, granted_at, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'ALLOW', now(), now(), now())",
                organisationId,
                member.membershipId,
                permissionId,
            )
            dsl.execute(
                "UPDATE user_organisation_membership SET joined_at = now() - interval '2 days', " +
                    "suspended_at = now() - interval '1 day', revoked_at = now() WHERE id = ?",
                member.membershipId,
            )
        }

        private fun checker(): UUID =
            seedUser("checker").also {
                fixture.grantTenantPermissionsWithViews(
                    organisationId,
                    it,
                    "user.approve",
                    "membership.view",
                )
            }

        // ----- requests -----

        private fun invite(body: String): ResultActionsDsl =
            post(ApiPaths.TENANT_USERS, body, admin, setOf("user.invite"))

        /** The invite route for a member that already has a membership here. */
        private fun reinvite(
            member: Member,
            actor: UUID = admin,
            body: String = inviteBody(member.email),
        ): ResultActionsDsl = post(ApiPaths.TENANT_USERS, body, actor, setOf("user.invite"))

        private fun revoke(member: Member): ResultActionsDsl =
            post(
                "${ApiPaths.MEMBERSHIPS}/${member.membershipId}/revoke",
                """{"reason":"Offboarding."}""",
                admin,
                setOf("membership.revoke"),
            )

        private fun approve(
            member: Member,
            actor: UUID,
        ): ResultActionsDsl =
            mockMvc.post("${ApiPaths.MEMBERSHIPS}/${member.membershipId}/activate") {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                with(authentication(token(actor, setOf("user.approve"))))
            }

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
                with(authentication(token(actor, authorities)))
            }

        @Suppress("LongParameterList")
        private fun inviteBody(
            email: String,
            membershipType: String = "STAFF",
            branchId: UUID = headOfficeId(),
            sendKeycloakInvite: Boolean = true,
            sendApplicationInvite: Boolean = false,
            roleId: UUID = roleId(),
        ): String =
            """{"email":"$email","username":"${"inv-${uuidV7()}".take(30)}",""" +
                """"display_name":"Invitee","membership_type":"$membershipType",""" +
                """"primary_branch_id":"$branchId",""" +
                """"branch_assignments":[{"branch_id":"$branchId","assignment_type":"HOME"}],""" +
                """"role_assignments":[{"role_id":"$roleId","scope_type":"TENANT"}],""" +
                """"send_keycloak_invite":$sendKeycloakInvite,""" +
                """"send_application_invite":$sendApplicationInvite}"""

        // ----- reads -----

        private fun membershipColumn(
            membershipId: UUID,
            column: String,
        ): Any? =
            dsl
                .fetchOne(
                    "SELECT $column FROM user_organisation_membership WHERE id = ?",
                    membershipId,
                )!!
                .get(0)

        private fun eventPublicationRows(): Int = count("SELECT COUNT(*) FROM event_publication")

        private fun membershipCount(userId: UUID): Int =
            count(
                "SELECT COUNT(*) FROM user_organisation_membership " +
                    "WHERE organisation_id = ? AND user_id = ?",
                organisationId,
                userId,
            )

        private fun reinviteLogRows(member: Member): Int =
            count(
                "SELECT COUNT(*) FROM user_organisation_membership_transition_log " +
                    "WHERE entity_id = ? AND transition_name = 'REINVITE'",
                member.membershipId,
            )

        private fun rowCounts(member: Member): List<Int> =
            listOf(
                membershipCount(member.userId),
                count(
                    "SELECT COUNT(*) FROM user_branch_assignment WHERE user_id = ?",
                    member.userId,
                ),
                count(
                    "SELECT COUNT(*) FROM user_role_assignment WHERE user_id = ?",
                    member.userId,
                ),
                count(
                    "SELECT COUNT(*) FROM audit_event WHERE organisation_id = ? " +
                        "AND (action = 'user.invite' OR action = 'membership.reinvite')",
                    organisationId,
                ),
                count(
                    "SELECT COUNT(*) FROM user_organisation_membership_transition_log " +
                        "WHERE entity_id = ?",
                    member.membershipId,
                ),
            )

        private fun dispatchKeys(
            userId: UUID,
            type: String,
        ): List<String> =
            dsl
                .fetch(
                    "SELECT dispatch_key FROM identity_dispatch_log WHERE user_id = ? " +
                        "AND dispatch_type = ? ORDER BY created_at, id",
                    userId,
                    type,
                ).map { it.get(0, String::class.java) }

        private fun dispatchStatus(key: String): String? =
            dsl
                .fetchOne("SELECT status FROM identity_dispatch_log WHERE dispatch_key = ?", key)
                ?.get(0, String::class.java)

        private fun userStatus(userId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        private fun emailOf(userId: UUID): String =
            dsl
                .fetchOne("SELECT email FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        private fun count(
            sql: String,
            vararg bindings: Any,
        ): Int = dsl.fetchOne(sql, *bindings)!!.get(0, Int::class.java)

        private fun roleId(code: String = "TENANT_ADMIN"): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = ?",
                    organisationId,
                    code,
                )!!
                .get(0, UUID::class.java)

        private fun headOfficeId(): UUID =
            dsl
                .fetch(
                    "SELECT id FROM branch WHERE organisation_id = ? ORDER BY created_at, id",
                    organisationId,
                ).first()
                .get(0, UUID::class.java)

        // A second ACTIVE branch, copied from the head office so every NOT NULL column is set.
        private fun insertBranch(): UUID {
            val id = uuidV7()
            dsl.execute(
                "INSERT INTO branch (id, organisation_id, branch_code, branch_name, branch_type, " +
                    "status, timezone, created_at, created_by, updated_at, updated_by) " +
                    "SELECT ?, organisation_id, ?, 'Spare', branch_type, 'ACTIVE', timezone, " +
                    "created_at, created_by, updated_at, updated_by " +
                    "FROM branch WHERE organisation_id = ? ORDER BY created_at, id LIMIT 1",
                id,
                "SPARE-${id.toString().takeLast(8)}",
                organisationId,
            )
            return id
        }

        private fun linkIdentity(userId: UUID) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(KEYCLOAK_IDENTITY_LINK)
                .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
                .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, "kc-$userId")
                .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
                .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
                .execute()
        }

        private fun token(
            userId: UUID,
            permissions: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@$domain",
                fullName = "Reinvite Test User",
                permissions = permissions,
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-${id.toString().takeLast(12)}")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@reinvite-member.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private data class Member(
            val membershipId: UUID,
            val userId: UUID,
            val email: String,
        )
    }
