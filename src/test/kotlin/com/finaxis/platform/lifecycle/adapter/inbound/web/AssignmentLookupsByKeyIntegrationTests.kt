package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.role.RevokeRoleAssignment
import com.finaxis.platform.iam.application.role.RoleManagementService
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.AssignUserToBranchCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RevokeBranchAssignmentCommand
import com.finaxis.platform.lifecycle.withRequestContext
import com.jayway.jsonpath.JsonPath
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Full-stack proof of ADR 0030 rollout step 6c, on real Postgres.
 *
 * The two read-backs that used to ask for "the first page of 100" (role-permission grant,
 * branch-assignment assign) read the row just written by its key, so a role or a branch holding
 * more than a page of rows can no longer 404 after a successful mutation. The revoke routes that
 * name only an assignment id (branch assignment, role assignment) authorise at the assignment's
 * own scope through an authorised combined lookup, and answer a caller who may not touch the row
 * exactly as they answer an unknown id: no response, status or body difference, so existence is
 * no oracle. A tenant-wide holder keeps the 404, and a caller with no grant is refused by name.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
// LargeClass, TooManyFunctions: one real-Postgres fixture holds every scenario of step 6c.
@Suppress("LargeClass", "TooManyFunctions")
class AssignmentLookupsByKeyIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val branchProvisioningService: BranchProvisioningService,
        private val roleManagementService: RoleManagementService,
        private val permissionGuard: PermissionGuard,
        private val iamQueryService: IamQueryService,
        private val cacheManager: CacheManager,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("alk", admin)

        // ---- by-key read-backs ----------------------------------------------------------------

        @Test
        fun `a role holding more than a page of grants reads back the grant just written`() {
            val roleId = customRole()
            try {
                // 105 other grants, all stamped later than the new one, so the new grant would sit
                // on the second page of the role's list and a first-page read-back would 404.
                insertFillerGrants(roleId)
                assertTrue(grantCount(roleId) > PAGE)

                post(
                    "${ApiPaths.ROLES}/$roleId/permissions",
                    tenantToken(admin, "role.assign_permission"),
                    """{"permission_code":"permission.view"}""",
                ).andExpect {
                    status { isCreated() }
                    jsonPath("$.permission_code") { value("permission.view") }
                    jsonPath("$.role_id") { value(roleId.toString()) }
                }
            } finally {
                dropFillerGrants(roleId)
            }
        }

        @Test
        fun `a branch holding more than a page of assignments reads back the one just assigned`() {
            val branchId = activeBranch()
            // 101 other ACTIVE OPERATE assignments, all stamped later than the new one.
            repeat(PAGE + 1) {
                val filler = seedUser("filler")
                insertMembership(filler)
                insertAssignment(filler, branchId, "OPERATE", OffsetDateTime.now().plusDays(1))
            }
            val assignee = seedUser("assignee")
            fixture.grantTenantPermissionsWithViews(organisationId, assignee, "branch.view")

            post(
                ApiPaths.BRANCH_ASSIGNMENTS,
                tenantToken(admin, "user.assign_branch"),
                """{"user_id":"$assignee","branch_id":"$branchId","assignment_type":"OPERATE"}""",
            ).andExpect {
                status { isCreated() }
                jsonPath("$.user_id") { value(assignee.toString()) }
                jsonPath("$.branch_id") { value(branchId.toString()) }
                jsonPath("$.status") { value("ACTIVE") }
            }
        }

        // ---- branch-assignment revoke: the authorised combined lookup -------------------------

        @Test
        fun `a branch scoped revoker gets one identical refusal for unknown and foreign rows`() {
            val branchA = activeBranch()
            val branchB = activeBranch()
            val ownRow = targetOnBranch(branchA)
            val foreignRow = targetOnBranch(branchB)
            val revoker = seedUser("branch-revoker")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                branchA,
                revoker,
                "user.revoke_branch",
            )
            val token = tenantToken(revoker, "user.revoke_branch", branchA)
            val before = footprint(revoker)

            val unknown = uuidV7()
            val refusedForeign = revokeBranchAssignment(foreignRow, token)
            val refusedUnknown = revokeBranchAssignment(unknown, token)

            assertEquals(403, refusedForeign.status)
            assertEquals(403, refusedUnknown.status)
            assertEquals(
                problem(refusedForeign, foreignRow),
                problem(refusedUnknown, unknown),
            )
            assertEquals(
                "Missing permission: user.revoke_branch.",
                detail(refusedUnknown.contentAsString),
            )
            assertEquals(before, footprint(revoker))
            assertEquals("ACTIVE", branchAssignmentStatus(foreignRow))

            // The same caller does revoke at its own branch.
            val revoked = revokeBranchAssignment(ownRow, token)
            assertEquals(200, revoked.status)
            assertEquals("REVOKED", branchAssignmentStatus(ownRow))
        }

        @Test
        fun `a tenant wide branch assignment revoker keeps the 404 for an unknown id`() {
            val branchId = activeBranch()
            val row = targetOnBranch(branchId)
            val revoker = seedUser("tenant-revoker")
            fixture.grantTenantPermissionsWithViews(organisationId, revoker, "user.revoke_branch")
            val token = tenantToken(revoker, "user.revoke_branch")

            val unknown = revokeBranchAssignment(uuidV7(), token)
            assertEquals(404, unknown.status)
            assertEquals(
                "resource_not_found",
                JsonPath.read<String>(unknown.contentAsString, "$.code"),
            )

            assertEquals(200, revokeBranchAssignment(row, token).status)
            assertEquals("REVOKED", branchAssignmentStatus(row))
        }

        @Test
        fun `a caller with no grant is refused by name for a real and an unknown assignment`() {
            val branchId = activeBranch()
            val row = targetOnBranch(branchId)
            val stranger = seedUser("no-grant")
            // The coarse gate passes on the token; the database holds nothing for this caller.
            val token = tenantToken(stranger, "user.revoke_branch")

            val unknownId = uuidV7()
            val real = revokeBranchAssignment(row, token)
            val unknown = revokeBranchAssignment(unknownId, token)

            assertEquals(403, real.status)
            assertEquals(403, unknown.status)
            assertEquals(problem(real, row), problem(unknown, unknownId))
            assertEquals(
                "Missing permission: user.revoke_branch.",
                detail(real.contentAsString),
            )
            assertEquals("ACTIVE", branchAssignmentStatus(row))
        }

        @Test
        fun `a branch assignment revoker without the view is refused by name before any lookup`() {
            val branchId = activeBranch()
            val row = targetOnBranch(branchId)
            val revoker = seedUser("revoker-no-view")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchId,
                revoker,
                "user.revoke_branch",
            )
            val token = tenantToken(revoker, "user.revoke_branch", branchId)

            val real = revokeBranchAssignment(row, token)
            val unknown = revokeBranchAssignment(uuidV7(), token)

            // The refusal comes from the caller's grants alone, so an assignment on the caller's
            // own branch and an unknown id are refused alike, naming the missing view.
            listOf(real, unknown).forEach {
                assertEquals(403, it.status)
                assertEquals(
                    "Missing permission: branch_assignment.view.",
                    detail(it.contentAsString),
                )
            }
            assertEquals("ACTIVE", branchAssignmentStatus(row))
        }

        // ---- another organisation's real ids ----------------------------------------------------

        @Test
        fun `another organisation's real assignment id is refused like an unknown one`() {
            val otherOrganisation = fixture.createActiveOrganisation("alk-other", seedUser("other"))
            val otherBranch = headOfficeOf(otherOrganisation)
            val otherUser = seedUser("other-assignee")
            fixture.grantTenantPermissionsWithViews(otherOrganisation, otherUser, "branch.view")
            val now = OffsetDateTime.now()
            insertAssignment(otherUser, otherBranch, "VIEW", now, otherOrganisation)
            val otherBranchRow =
                insertAssignment(otherUser, otherBranch, "OPERATE", now, otherOrganisation)
            val otherRoleRow = roleAssignmentOf(otherUser, "TENANT", otherOrganisation)

            val branchA = activeBranch()
            val scopedBranchRevoker = seedUser("scoped-branch-revoker")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                branchA,
                scopedBranchRevoker,
                "user.revoke_branch",
                "user.revoke_role",
            )
            val scoped = tenantToken(scopedBranchRevoker, "user.revoke_branch", branchA)
            val scopedRole = tenantToken(scopedBranchRevoker, "user.revoke_role", branchA)
            val unknownBranch = uuidV7()
            val unknownRole = uuidV7()

            assertEquals(
                problem(revokeBranchAssignment(unknownBranch, scoped), unknownBranch),
                problem(revokeBranchAssignment(otherBranchRow, scoped), otherBranchRow),
            )
            assertEquals(
                problem(revokeRoleAssignment(unknownRole, scopedRole), unknownRole),
                problem(revokeRoleAssignment(otherRoleRow, scopedRole), otherRoleRow),
            )

            val tenantWide = seedUser("tenant-wide-both")
            fixture.grantTenantPermissionsWithViews(
                organisationId,
                tenantWide,
                "user.revoke_branch",
                "user.revoke_role",
            )
            val wideBranch = tenantToken(tenantWide, "user.revoke_branch")
            val wideRole = tenantToken(tenantWide, "user.revoke_role")
            assertEquals(404, revokeBranchAssignment(otherBranchRow, wideBranch).status)
            assertEquals(404, revokeRoleAssignment(otherRoleRow, wideRole).status)
            assertEquals("ACTIVE", branchAssignmentStatus(otherBranchRow))
            assertEquals("ACTIVE", roleAssignmentStatus(otherRoleRow))
        }

        @Test
        fun `the mutation is named to a caller who holds it when the view is held elsewhere`() {
            val branchA = activeBranch()
            val branchB = activeBranch()
            val ownRow = targetOnBranch(branchA)
            val revoker = seedUser("mutation-at-a-view-at-b")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchA,
                revoker,
                "user.revoke_branch",
            )
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchB,
                revoker,
                "branch_assignment.view",
            )
            val token = tenantToken(revoker, "user.revoke_branch", branchA)

            val own = revokeBranchAssignment(ownRow, token)
            val unknown = revokeBranchAssignment(uuidV7(), token)

            // The intersection of the two codes is empty, so even the caller's own row at A is
            // refused naming the mutation, as an unknown id is; nothing about the row leaks.
            listOf(own, unknown).forEach {
                assertEquals(403, it.status)
                assertEquals("Missing permission: user.revoke_branch.", detail(it.contentAsString))
            }
            assertEquals("ACTIVE", branchAssignmentStatus(ownRow))
        }

        // ---- branch-assignment assign is authorised at the target branch ----------------------

        @Test
        fun `a branch scoped assigner assigns at its branch and is refused elsewhere`() {
            val branchA = activeBranch()
            val branchB = activeBranch()
            val assignee = seedUser("scoped-assignee")
            fixture.grantTenantPermissionsWithViews(organisationId, assignee, "branch.view")
            val assigner = seedUser("scoped-assigner")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                branchA,
                assigner,
                "user.assign_branch",
                "user.assign_role",
            )
            val token = tenantToken(assigner, "user.assign_branch", branchA)
            val before = footprint(assigner)

            val atB = assignBranch(assignee, branchB, token)
            assertEquals(403, atB.status)
            assertEquals("Missing permission: user.assign_branch.", detail(atB.contentAsString))
            assertEquals(before, footprint(assigner))

            // A TENANT-scope role assignment is out of reach of a branch-scoped holder, too.
            val roleId = roleIdOf("BRANCH_OPERATOR")
            val tenantRole =
                mockMvc
                    .post(ApiPaths.ROLE_ASSIGNMENTS) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            """{"user_id":"$assignee","role_id":"$roleId","scope_type":"TENANT"}"""
                        with(authentication(tenantToken(assigner, "user.assign_role", branchA)))
                    }.andReturn()
                    .response
            assertEquals(403, tenantRole.status)
            assertEquals(
                "Missing permission: user.assign_role.",
                detail(tenantRole.contentAsString),
            )
            assertEquals(before, footprint(assigner))

            val atA = assignBranch(assignee, branchA, token)
            assertEquals(201, atA.status)
            assertEquals(
                branchA.toString(),
                JsonPath.read<String>(atA.contentAsString, "$.branch_id"),
            )
        }

        // ---- role-assignment revoke: the authorised combined lookup ---------------------------

        @Test
        fun `a branch scoped role revoker gets one refusal for unknown foreign and tenant rows`() {
            val branchA = activeBranch()
            val branchB = activeBranch()
            val ownRow = branchRoleAssignmentOn(branchA)
            val foreignRow = branchRoleAssignmentOn(branchB)
            val tenantRow = tenantRoleAssignment()
            val revoker = seedUser("role-branch-revoker")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                branchA,
                revoker,
                "user.revoke_role",
            )
            val token = tenantToken(revoker, "user.revoke_role", branchA)
            val before = footprint(revoker)

            val unknown = uuidV7()
            val refusals =
                listOf(foreignRow, tenantRow, unknown).map { it to revokeRoleAssignment(it, token) }

            refusals.forEach { (_, response) -> assertEquals(403, response.status) }
            assertEquals(1, refusals.map { (id, response) -> problem(response, id) }.toSet().size)
            assertEquals(
                "Missing permission: user.revoke_role.",
                detail(refusals.first().second.contentAsString),
            )
            assertEquals(before, footprint(revoker))
            assertEquals("ACTIVE", roleAssignmentStatus(foreignRow))
            assertEquals("ACTIVE", roleAssignmentStatus(tenantRow))

            val revoked = revokeRoleAssignment(ownRow, token)
            assertEquals(200, revoked.status)
            assertEquals("REVOKED", roleAssignmentStatus(ownRow))
        }

        @Test
        fun `a tenant wide role revoker keeps the 404 and revokes either scope`() {
            val branchId = activeBranch()
            val branchRow = branchRoleAssignmentOn(branchId)
            val tenantRow = tenantRoleAssignment()
            val revoker = seedUser("role-tenant-revoker")
            fixture.grantTenantPermissionsWithViews(organisationId, revoker, "user.revoke_role")
            val token = tenantToken(revoker, "user.revoke_role")

            assertEquals(404, revokeRoleAssignment(uuidV7(), token).status)
            assertEquals(200, revokeRoleAssignment(branchRow, token).status)
            assertEquals(200, revokeRoleAssignment(tenantRow, token).status)
            assertEquals("REVOKED", roleAssignmentStatus(branchRow))
            assertEquals("REVOKED", roleAssignmentStatus(tenantRow))
        }

        @Test
        fun `a role revoker without the view is refused by name, real or unknown`() {
            val branchId = activeBranch()
            val branchRow = branchRoleAssignmentOn(branchId)
            val tenantRow = tenantRoleAssignment()
            val tenantRevoker = seedUser("role-revoker-no-view")
            fixture.grantTenantPermissionsExactly(organisationId, tenantRevoker, "user.revoke_role")
            val branchRevoker = seedUser("role-branch-revoker-no-view")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchId,
                branchRevoker,
                "user.revoke_role",
            )

            listOf(
                tenantRevoker to tenantToken(tenantRevoker, "user.revoke_role"),
                branchRevoker to tenantToken(branchRevoker, "user.revoke_role", branchId),
            ).forEach { (_, token) ->
                listOf(tenantRow, branchRow, uuidV7()).forEach { id ->
                    val refused = revokeRoleAssignment(id, token)
                    assertEquals(403, refused.status)
                    assertEquals(
                        "Missing permission: role_assignment.view.",
                        detail(refused.contentAsString),
                    )
                }
            }
            assertEquals("ACTIVE", roleAssignmentStatus(tenantRow))
            assertEquals("ACTIVE", roleAssignmentStatus(branchRow))
        }

        // ---- the read-back never re-resolves ---------------------------------------------------

        @Test
        fun `the role permission read-back decides from the pre-check's answer`() {
            val roleId = customRole()
            val actor = seedUser("memo-grant")
            val caller = TenantCaller(actor, organisationId)
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "role.assign_permission")
            insertGrant(roleId, "permission.view")

            assertReadBackUsesPreCheck(
                actor,
                "role.view",
                preCheck = {
                    permissionGuard.requireTenantPermission(
                        actor,
                        organisationId,
                        "role.assign_permission",
                    )
                },
                readBack = {
                    iamQueryService.getRolePermissionByCode(
                        organisationId,
                        roleId,
                        "permission.view",
                        caller,
                    )
                },
                afterwards = {
                    iamQueryService.getRolePermissionByCode(
                        organisationId,
                        roleId,
                        "permission.view",
                        caller,
                    )
                },
            )
        }

        @Test
        fun `the branch assignment assign read-back decides from the pre-check's answer`() {
            val branchId = activeBranch()
            val actor = seedUser("memo-assign")
            val assignee = seedUser("memo-assignee")
            val caller = TenantCaller(actor, organisationId)
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "user.assign_branch")
            fixture.grantTenantPermissionsWithViews(organisationId, assignee, "branch.view")
            var assignmentId: UUID? = null

            assertReadBackUsesPreCheck(
                actor,
                "branch_assignment.view",
                preCheck = {
                    assignmentId =
                        branchProvisioningService.assignUser(
                            AssignUserToBranchCommand(
                                organisationId,
                                assignee,
                                branchId,
                                BranchAssignmentType.OPERATE,
                                actor,
                            ),
                        )
                },
                readBack = {
                    iamQueryService.getBranchAssignment(
                        organisationId,
                        requireNotNull(assignmentId),
                        caller,
                    )
                },
                afterwards = {
                    iamQueryService.getBranchAssignment(
                        organisationId,
                        requireNotNull(assignmentId),
                        caller,
                    )
                },
            )
        }

        @Test
        fun `the branch assignment revoke read-back decides from the pre-check's answer`() {
            val branchId = activeBranch()
            val row = targetOnBranch(branchId)
            val actor = seedUser("memo-revoke")
            val caller = TenantCaller(actor, organisationId)
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "user.revoke_branch")

            assertReadBackUsesPreCheck(
                actor,
                "branch_assignment.view",
                preCheck = {
                    branchProvisioningService.revokeAssignment(
                        RevokeBranchAssignmentCommand(organisationId, row, actor),
                    )
                },
                readBack = { iamQueryService.getBranchAssignment(organisationId, row, caller) },
                afterwards = { iamQueryService.getBranchAssignment(organisationId, row, caller) },
            )
            assertEquals("REVOKED", branchAssignmentStatus(row))
        }

        @Test
        fun `the role assignment revoke read-back decides from the pre-check's answer`() {
            val row = tenantRoleAssignment()
            val actor = seedUser("memo-role-revoke")
            val caller = TenantCaller(actor, organisationId)
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "user.revoke_role")

            assertReadBackUsesPreCheck(
                actor,
                "role_assignment.view",
                preCheck = {
                    roleManagementService.revokeRoleAssignment(
                        RevokeRoleAssignment(organisationId, row, actor),
                    )
                },
                readBack = { iamQueryService.getRoleAssignment(organisationId, row, caller) },
                afterwards = { iamQueryService.getRoleAssignment(organisationId, row, caller) },
            )
            assertEquals("REVOKED", roleAssignmentStatus(row))
        }

        /**
         * One request context is one `RequestPermissionCache`. After the pre-check (the service
         * call that authorised and made the mutation) passed, the
         * [view] is revoked in the database and the shared cache invalidated, so any
         * re-resolution would now refuse. The read-back still passes (it hits the memo), and a
         * **new** request is refused, which proves the revocation was real.
         */
        private fun assertReadBackUsesPreCheck(
            actor: UUID,
            view: String,
            preCheck: () -> Unit,
            readBack: () -> Unit,
            afterwards: () -> Unit,
        ) {
            withRequestContext {
                preCheck()
                revokeFromNarrowRole(actor, view)
                readBack()
            }
            assertFailsWith<ForbiddenOperationException> { withRequestContext(afterwards) }
        }

        // ---- fixtures -------------------------------------------------------------------------

        private fun revokeFromNarrowRole(
            actor: UUID,
            view: String,
        ) {
            dsl.execute(
                "DELETE FROM role_permission WHERE organisation_id = ? " +
                    "AND permission_id = (SELECT id FROM permission WHERE permission_code = ?) " +
                    "AND role_id IN (SELECT role_id FROM user_role_assignment " +
                    "WHERE user_id = ? AND organisation_id = ?)",
                organisationId,
                view,
                actor,
                organisationId,
            )
            // invalidate() deletes synchronously; the Redis cache's clear() may run asynchronously.
            cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
        }

        /** A user with two ACTIVE assignments on [branchId], so one may be revoked; one id. */
        private fun targetOnBranch(branchId: UUID): UUID {
            val user = seedUser("assignee")
            fixture.grantTenantPermissionsWithViews(organisationId, user, "branch.view")
            insertAssignment(user, branchId, "VIEW", OffsetDateTime.now())
            return insertAssignment(user, branchId, "OPERATE", OffsetDateTime.now())
        }

        private fun branchRoleAssignmentOn(branchId: UUID): UUID {
            val user = seedUser("branch-role-holder")
            fixture.grantBranchPermissionsExactly(organisationId, branchId, user, "branch.view")
            return roleAssignmentOf(user, "BRANCH")
        }

        private fun tenantRoleAssignment(): UUID {
            val user = seedUser("tenant-role-holder")
            fixture.grantTenantPermissionsWithViews(organisationId, user, "branch.view")
            return roleAssignmentOf(user, "TENANT")
        }

        private fun roleAssignmentOf(
            userId: UUID,
            scopeType: String,
            inOrganisation: UUID = organisationId,
        ): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM user_role_assignment WHERE organisation_id = ? " +
                        "AND user_id = ? AND scope_type = ? AND status = 'ACTIVE'",
                    inOrganisation,
                    userId,
                    scopeType,
                )!!
                .get(0, UUID::class.java)

        private fun insertMembership(userId: UUID) {
            val now = OffsetDateTime.now()
            dsl.execute(
                "INSERT INTO user_organisation_membership (id, organisation_id, user_id, " +
                    "membership_status, membership_type, created_by, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'ACTIVE', 'STAFF', ?, ?::timestamptz, ?::timestamptz)",
                uuidV7(),
                organisationId,
                userId,
                SystemActor.ID,
                now,
                now,
            )
        }

        private fun insertAssignment(
            userId: UUID,
            branchId: UUID,
            type: String,
            assignedAt: OffsetDateTime,
            inOrganisation: UUID = organisationId,
        ): UUID =
            dsl
                .fetchOne(
                    "INSERT INTO user_branch_assignment (organisation_id, user_id, branch_id, " +
                        "assignment_type, status, assigned_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'ACTIVE', " +
                        "?::timestamptz, ?::timestamptz, ?::timestamptz) " +
                        "RETURNING id",
                    inOrganisation,
                    userId,
                    branchId,
                    type,
                    assignedAt,
                    assignedAt,
                    assignedAt,
                )!!
                .get(0, UUID::class.java)

        private fun customRole(): UUID {
            val now = OffsetDateTime.now()
            return dsl
                .fetchOne(
                    "INSERT INTO role (organisation_id, role_code, role_name, system_role, " +
                        "status, created_at, updated_at) " +
                        "VALUES (?, ?, 'By-key role', FALSE, 'ACTIVE', " +
                        "?::timestamptz, ?::timestamptz) " +
                        "RETURNING id",
                    organisationId,
                    "BYKEY_${uuidV7().toString().takeLast(SUFFIX)}".uppercase(),
                    now,
                    now,
                )!!
                .get(0, UUID::class.java)
        }

        private fun insertGrant(
            roleId: UUID,
            code: String,
        ) {
            val now = OffsetDateTime.now()
            dsl.execute(
                "INSERT INTO role_permission (organisation_id, role_id, permission_id, " +
                    "granted_at, created_at, updated_at) " +
                    "SELECT ?, ?, id, ?::timestamptz, ?::timestamptz, ?::timestamptz " +
                    "FROM permission WHERE permission_code = ?",
                organisationId,
                roleId,
                now,
                now,
                now,
                code,
            )
        }

        /** More than a page of grants: DISABLED filler permissions, stamped a day ahead. */
        private fun insertFillerGrants(roleId: UUID) {
            dsl.execute(
                "INSERT INTO permission (permission_code, permission_name, module_code, " +
                    "risk_level, status, created_at, updated_at, kind, grant_scope) " +
                    "SELECT '$FILLER' || g, 'Filler', 'zz', 'LOW', 'DISABLED', now(), now(), " +
                    "'VIEW', 'TENANT' FROM generate_series(1, ${PAGE + 5}) g",
            )
            dsl.execute(
                "INSERT INTO role_permission (organisation_id, role_id, permission_id, " +
                    "granted_at, created_at, updated_at) " +
                    "SELECT ?, ?, id, now() + interval '1 day', now(), now() " +
                    "FROM permission WHERE permission_code LIKE '$FILLER%'",
                organisationId,
                roleId,
            )
        }

        private fun dropFillerGrants(roleId: UUID) {
            dsl.execute("DELETE FROM role_permission WHERE role_id = ?", roleId)
            dsl.execute("DELETE FROM permission WHERE permission_code LIKE '$FILLER%'")
        }

        private fun grantCount(roleId: UUID): Int =
            dsl
                .fetchOne("SELECT count(*) FROM role_permission WHERE role_id = ?", roleId)!!
                .get(0, Int::class.java)

        private fun branchAssignmentStatus(id: UUID): String =
            dsl
                .fetchOne("SELECT status FROM user_branch_assignment WHERE id = ?", id)!!
                .get(0, String::class.java)

        private fun roleAssignmentStatus(id: UUID): String =
            dsl
                .fetchOne("SELECT status FROM user_role_assignment WHERE id = ?", id)!!
                .get(0, String::class.java)

        /** Everything a refused request must not touch, as in the mutation-view suite. */
        private fun footprint(actor: UUID): List<Long> =
            dsl
                .fetchOne(
                    "SELECT " +
                        "(SELECT count(*) FROM audit_event WHERE actor_user_id = ?), " +
                        "(SELECT count(*) FROM user_role_assignment " +
                        "WHERE organisation_id = ? AND status = 'ACTIVE'), " +
                        "(SELECT count(*) FROM user_branch_assignment " +
                        "WHERE organisation_id = ? AND status = 'ACTIVE'), " +
                        "(SELECT count(*) FROM role_permission WHERE organisation_id = ?)",
                    actor,
                    organisationId,
                    organisationId,
                    organisationId,
                )!!
                .intoArray()
                .map { (it as Number).toLong() }

        /**
         * A refusal as the caller sees it. `request_id` is the per-request correlation id and
         * `instance` echoes the path the caller itself asked for, so both are set aside; every
         * other member (status, type, title, code, detail) must be identical.
         */
        private fun problem(
            response: org.springframework.mock.web.MockHttpServletResponse,
            askedFor: UUID,
        ): String {
            val body =
                JsonPath
                    .parse(response.contentAsString)
                    .json<MutableMap<String, Any?>>()
                    .toSortedMap()
            body.remove("request_id")
            body["instance"] = body["instance"].toString().replace(askedFor.toString(), "{id}")
            return "${response.status} ${response.contentType} $body"
        }

        private fun assignBranch(
            userId: UUID,
            branchId: UUID,
            token: AppPrincipalAuthenticationToken,
        ) = mockMvc
            .post(ApiPaths.BRANCH_ASSIGNMENTS) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"user_id":"$userId","branch_id":"$branchId","assignment_type":"OPERATE"}"""
                with(authentication(token))
            }.andReturn()
            .response

        private fun headOfficeOf(organisation: UUID): UUID =
            dsl
                .fetchOne("SELECT id FROM branch WHERE organisation_id = ? LIMIT 1", organisation)!!
                .get(0, UUID::class.java)

        private fun roleIdOf(code: String): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = ?",
                    organisationId,
                    code,
                )!!
                .get(0, UUID::class.java)

        private fun detail(body: String): String = JsonPath.read<String>(body, "$.detail")

        private fun revokeBranchAssignment(
            id: UUID,
            token: AppPrincipalAuthenticationToken,
        ) = revoke("${ApiPaths.BRANCH_ASSIGNMENTS}/$id", token)

        private fun revokeRoleAssignment(
            id: UUID,
            token: AppPrincipalAuthenticationToken,
        ) = revoke("${ApiPaths.ROLE_ASSIGNMENTS}/$id", token)

        private fun revoke(
            path: String,
            token: AppPrincipalAuthenticationToken,
        ): org.springframework.mock.web.MockHttpServletResponse =
            mockMvc
                .delete(path) {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    with(authentication(token))
                }.andReturn()
                .response

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
            body: String,
        ) = mockMvc.post(path) {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            contentType = MediaType.APPLICATION_JSON
            content = body
            with(authentication(token))
        }

        /** A branch created by the tenant administrator, submitted and activated by a checker. */
        private fun activeBranch(): UUID {
            val checker = seedUser("checker")
            fixture.grantTenantAdmin(organisationId, checker)
            val created =
                post(ApiPaths.BRANCHES, tenantToken(admin, "branch.create"), branchBody())
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            val branchId = UUID.fromString(BRANCH_ID.find(created)!!.groupValues[1])
            post("${ApiPaths.BRANCHES}/$branchId/submit", tenantToken(admin, "branch.create"), "{}")
                .andExpect { status { isOk() } }
            post(
                "${ApiPaths.BRANCHES}/$branchId/activate",
                tenantToken(checker, "branch.approve"),
                "{}",
            ).andExpect { status { isOk() } }
            return branchId
        }

        private fun branchBody() =
            """{"branch_code":"BR-${uuidV7().toString().takeLast(8).uppercase()}",""" +
                """"branch_name":"Riverside Branch","branch_type":"OPERATIONAL",""" +
                """"timezone":"Africa/Nairobi"}"""

        private fun tenantToken(
            userId: UUID,
            permission: String,
            pinnedTo: UUID? = null,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = pinnedTo,
                email = "user@alk.test",
                fullName = "Lookup By Key User",
                permissions = setOf(permission),
            ),
        )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@seed.test")
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
            const val PAGE = 100
            const val SUFFIX = 10
            const val FILLER = "zz.by_key_"
            val BRANCH_ID = Regex("\"branch_id\"\\s*:\\s*\"([^\"]+)\"")
        }
    }
