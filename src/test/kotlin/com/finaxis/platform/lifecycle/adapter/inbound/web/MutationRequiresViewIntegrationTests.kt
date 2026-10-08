package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.DeactivateUserCommand
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReactivateUserCommand
import com.finaxis.platform.lifecycle.application.Reason
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.SuspendMembershipCommand
import com.finaxis.platform.lifecycle.application.SuspendUserCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Full-stack proof of ADR 0030, decision 4, for each route family: a caller holding a mutation
 * code without the view the catalogue pairs with it is refused with 403 `forbidden`, a `detail`
 * naming the first missing code ("Missing permission: branch.view."), the mutation code first,
 * before any existence lookup, and **nothing is written** (no transition log, no audit row, no
 * created resource, no idempotency row). The read-back of a request that passed the pre-check is
 * decided from the same per-request permission memo and cannot be refused by a later change.
 *
 * One family is the stated exception: tenant settings resolve the setting key and organisation
 * state before the permission check. Role-permission remove and the two assignment revokes are
 * named like the rest.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("LargeClass", "TooManyFunctions")
class MutationRequiresViewIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val permissionGuard: PermissionGuard,
        private val userProvisioningService: UserProvisioningService,
        private val foundationQueryService: FoundationQueryService,
        private val iamQueryService: IamQueryService,
        private val cacheManager: CacheManager,
    ) {
        @MockitoSpyBean
        private lateinit var branchProvisioningService: BranchProvisioningService

        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("mrv", admin)

        // ---- branch routes (tenant, target-branch scope) --------------------------------------

        @Test
        fun `a branch mutation without branch view is refused by name before any lookup`() {
            val actor = seedUser("suspender")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "branch.suspend")
            val key = uuidV7()
            val before = footprint(actor)

            // An unknown id: the refusal precedes the existence lookup, so it is 403 and not 404.
            suspendBranch(uuidV7(), tenantToken(actor, "branch.suspend"), key).andExpect {
                forbiddenNaming("branch.view")
            }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))
        }

        @Test
        fun `the mutation code is named before its view`() {
            val actor = seedUser("viewer-only")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "branch.view")
            val before = footprint(actor)

            // The token carries the coarse authority, so the unnamed gate passes and the service
            // check names the code the caller lacks.
            suspendBranch(uuidV7(), tenantToken(actor, "branch.suspend")).andExpect {
                forbiddenNaming("branch.suspend")
            }

            assertEquals(before, footprint(actor))
        }

        @Test
        fun `a branch mutation with its view suspends the branch and reads it back`() {
            val branchId = activeBranch()
            val actor = seedUser("suspender-with-view")
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "branch.suspend")

            suspendBranch(branchId, tenantToken(actor, "branch.suspend")).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.status") { value("SUSPENDED") }
            }
        }

        @Test
        fun `a branch scoped mutation needs the view at that branch and nothing is changed`() {
            // The target is already SUSPENDED: without the pre-check the service would answer a
            // state-conflict 409, and the gated read-back alone would roll back with the same
            // named 403, so only a 403 here proves the refusal comes BEFORE the change.
            val branchId = suspendedBranch()
            val noView = seedUser("branch-no-view")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchId,
                noView,
                "branch.suspend",
            )
            val before = footprint(noView)

            suspendBranch(branchId, tenantToken(noView, "branch.suspend", branchId))
                .andExpect { forbiddenNaming("branch.view") }

            assertEquals(before, footprint(noView))
            assertEquals("SUSPENDED", branchStatus(branchId))

            // The view must be held at the TARGET branch: one held at another does not count.
            val elsewhere = activeBranch()
            val viewElsewhere = seedUser("branch-view-elsewhere")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchId,
                viewElsewhere,
                "branch.suspend",
            )
            fixture.grantBranchPermissionsExactly(
                organisationId,
                elsewhere,
                viewElsewhere,
                "branch.view",
            )
            suspendBranch(branchId, tenantToken(viewElsewhere, "branch.suspend", branchId))
                .andExpect { forbiddenNaming("branch.view") }
            assertEquals("SUSPENDED", branchStatus(branchId))

            val withView = seedUser("branch-with-view")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                elsewhere,
                withView,
                "branch.suspend",
            )
            // Another branch (here an unknown id): the grant at this branch does not reach it.
            suspendBranch(uuidV7(), tenantToken(withView, "branch.suspend", elsewhere))
                .andExpect { forbiddenNaming("branch.suspend") }
            suspendBranch(elsewhere, tenantToken(withView, "branch.suspend", elsewhere))
                .andExpect { status { isOk() } }
        }

        // ---- platform tenant routes -----------------------------------------------------------

        @Test
        fun `a platform tenant mutation without tenant view is refused and changes nothing`() {
            val actor = seedUser("platform-suspender")
            fixture.grantPlatformPermissionsExactly(actor, "tenant.suspend")
            val key = uuidV7()
            val before = footprint(actor)

            // An unknown tenant id: still 403, never 404, and the tenant is untouched.
            platformSuspend(uuidV7(), actor, key).andExpect { forbiddenNaming("tenant.view") }
            platformSuspend(organisationId, actor, key).andExpect { forbiddenNaming("tenant.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))
            assertEquals("ACTIVE", tenantStatus(organisationId))
        }

        @Test
        fun `a refused platform mutation leaves no denied audit row for the platform tenant`() {
            val actor = seedUser("platform-protector")
            fixture.grantPlatformPermissionsExactly(actor, "tenant.suspend")
            val before = footprint(actor)

            // The platform organisation is protected: past the pre-check the service would answer
            // 409 AFTER writing a DENIED audit row (REQUIRES_NEW, so it survives any rollback).
            // That the actor has no audit row at all proves the view refusal came first.
            platformSuspend(PlatformOrganisation.ID, actor)
                .andExpect { forbiddenNaming("tenant.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, auditRows(actor))
            assertEquals("ACTIVE", tenantStatus(PlatformOrganisation.ID))
        }

        @Test
        fun `a platform tenant mutation with its view suspends the tenant and reads it back`() {
            val owner = seedUser("suspend-owner")
            val target = fixture.createActiveOrganisation("mrv-suspend", owner)
            val actor = seedUser("platform-suspender-view")
            fixture.grantPlatformPermissionsWithViews(actor, "tenant.suspend")

            platformSuspend(target, actor).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("SUSPENDED") }
            }
            assertEquals("SUSPENDED", tenantStatus(target))
        }

        @Test
        fun `a platform branch mutation without branch view creates nothing`() {
            val actor = seedUser("platform-brancher")
            fixture.grantPlatformPermissionsExactly(actor, "branch.create")
            val before = footprint(actor)

            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$organisationId/branches") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = branchBody()
                    with(authentication(platformToken(actor, setOf("branch.create"))))
                }.andExpect { forbiddenNaming("branch.view") }

            assertEquals(before, footprint(actor))
        }

        // ---- membership and invite routes -----------------------------------------------------

        @Test
        fun `a membership mutation without membership view is refused before any lookup`() {
            val actor = seedUser("member-suspender")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "membership.suspend")
            val key = uuidV7()
            val before = footprint(actor)

            post(
                "${ApiPaths.MEMBERSHIPS}/${uuidV7()}/suspend",
                tenantToken(actor, "membership.suspend"),
                """{"reason":"Audit hold"}""",
                key,
            ).andExpect { forbiddenNaming("membership.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))
        }

        // The HTTP route test below is a regression test of the route's behaviour, not proof that
        // the check moved into the service: its invalid role and branch ids would be refused by a
        // controller-side check just the same. The direct-service test after it is the proof.
        @Test
        fun `an invitation names each missing view in turn and writes nothing`() {
            val actor = seedUser("inviter")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "user.invite")
            val partial = seedUser("inviter-membership-view")
            fixture.grantTenantPermissionsExactly(
                organisationId,
                partial,
                "user.invite",
                "membership.view",
            )
            val before = footprint(actor)

            invite(tenantToken(actor, "user.invite"))
                .andExpect { forbiddenNaming("membership.view") }
            invite(tenantToken(partial, "user.invite")).andExpect { forbiddenNaming("user.view") }

            assertEquals(before, footprint(actor))
        }

        // ---- role routes ----------------------------------------------------------------------

        @Test
        fun `a role mutation without role view is refused before the duplicate check`() {
            val actor = seedUser("role-maker")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "role.create")
            val key = uuidV7()
            val before = footprint(actor)

            // TENANT_ADMIN exists: past the pre-check the service would answer 409, and the
            // gated read-back alone would roll a fresh code back with the same named 403, so a
            // 403 on a duplicate code proves the refusal precedes the change.
            post(
                ApiPaths.ROLES,
                tenantToken(actor, "role.create"),
                """{"role_code":"TENANT_ADMIN","role_name":"Duplicate"}""",
                key,
            ).andExpect { forbiddenNaming("role.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))
        }

        // ---- assignment routes ----------------------------------------------------------------

        @Test
        fun `a branch assignment without branch assignment view assigns nothing`() {
            val branchId = activeBranch()
            val member = seedUser("assignee")
            fixture.grantTenantPermissionsWithViews(organisationId, member, "branch.view")
            val actor = seedUser("branch-assigner")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "user.assign_branch")
            val key = uuidV7()
            val before = footprint(actor)
            val body =
                """{"user_id":"$member","branch_id":"$branchId","assignment_type":"OPERATE"}"""

            // Real, assignable inputs: with the view this very request would succeed (below).
            post(ApiPaths.BRANCH_ASSIGNMENTS, tenantToken(actor, "user.assign_branch"), body, key)
                .andExpect { forbiddenNaming("branch_assignment.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))

            val withView = seedUser("branch-assigner-view")
            fixture.grantTenantPermissionsWithViews(organisationId, withView, "user.assign_branch")
            post(ApiPaths.BRANCH_ASSIGNMENTS, tenantToken(withView, "user.assign_branch"), body)
                .andExpect { status { isCreated() } }
        }

        @Test
        fun `a role assignment without role assignment view is refused at either scope`() {
            val member = seedUser("role-assignee")
            fixture.grantTenantPermissionsWithViews(organisationId, member, "branch.view")
            val roleId = roleIdOf("BRANCH_OPERATOR")
            val branchId = activeBranch()
            val elsewhere = activeBranch()
            val tenantActor = seedUser("tenant-role-assigner")
            fixture.grantTenantPermissionsExactly(organisationId, tenantActor, "user.assign_role")
            val before = footprint(tenantActor)

            post(
                ApiPaths.ROLE_ASSIGNMENTS,
                tenantToken(tenantActor, "user.assign_role"),
                """{"user_id":"$member","role_id":"$roleId","scope_type":"TENANT"}""",
            ).andExpect { forbiddenNaming("role_assignment.view") }
            assertEquals(before, footprint(tenantActor))

            // BRANCH scope: the mutation is held at the target branch, the view only at another.
            val branchActor = seedUser("branch-role-assigner")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchId,
                branchActor,
                "user.assign_role",
            )
            fixture.grantBranchPermissionsExactly(
                organisationId,
                elsewhere,
                branchActor,
                "role_assignment.view",
            )
            val branchBefore = footprint(branchActor)
            post(
                ApiPaths.ROLE_ASSIGNMENTS,
                tenantToken(branchActor, "user.assign_role", branchId),
                """{"user_id":"$member","role_id":"$roleId","scope_type":"BRANCH",""" +
                    """"branch_id":"$branchId"}""",
            ).andExpect { forbiddenNaming("role_assignment.view") }
            assertEquals(branchBefore, footprint(branchActor))
        }

        @Test
        fun `a role assignment revoke without the view revokes nothing at either scope`() {
            val tenantMember = seedUser("tenant-revokee")
            fixture.grantTenantPermissionsWithViews(organisationId, tenantMember, "branch.view")
            val branchId = activeBranch()
            val branchMember = seedUser("branch-revokee")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchId,
                branchMember,
                "branch.view",
            )
            val tenantRevoker = seedUser("tenant-revoker")
            fixture.grantTenantPermissionsExactly(organisationId, tenantRevoker, "user.revoke_role")
            val branchRevoker = seedUser("branch-revoker")
            fixture.grantBranchPermissionsExactly(
                organisationId,
                branchId,
                branchRevoker,
                "user.revoke_role",
            )
            val tenantRow = roleAssignmentOf(tenantMember)
            val branchRow = roleAssignmentOf(branchMember)
            val tenantBefore = footprint(tenantRevoker)
            val branchBefore = footprint(branchRevoker)

            // The service authorises before it reads the assignment (the authorised combined
            // lookup resolves the caller's visibility from grants alone), so the refusal names
            // the missing view whichever row is asked for, and an unknown id is refused alike.
            delete(
                "${ApiPaths.ROLE_ASSIGNMENTS}/$tenantRow",
                tenantToken(tenantRevoker, "user.revoke_role"),
            ).andExpect { forbiddenNaming("role_assignment.view") }
            delete(
                "${ApiPaths.ROLE_ASSIGNMENTS}/$branchRow",
                tenantToken(branchRevoker, "user.revoke_role", branchId),
            ).andExpect { forbiddenNaming("role_assignment.view") }
            delete(
                "${ApiPaths.ROLE_ASSIGNMENTS}/${uuidV7()}",
                tenantToken(tenantRevoker, "user.revoke_role"),
            ).andExpect { forbiddenNaming("role_assignment.view") }

            assertEquals(tenantBefore, footprint(tenantRevoker))
            assertEquals(branchBefore, footprint(branchRevoker))
            assertEquals("ACTIVE", roleAssignmentStatus(tenantRow))
            assertEquals("ACTIVE", roleAssignmentStatus(branchRow))
        }

        // ---- role permission routes -----------------------------------------------------------

        @Test
        fun `granting a role permission without role view is refused before the role is read`() {
            val actor = seedUser("permission-granter")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "role.assign_permission")
            val key = uuidV7()
            val before = footprint(actor)

            // TENANT_ADMIN is immutable: past the pre-check the service would answer 409.
            post(
                "${ApiPaths.ROLES}/${roleIdOf("TENANT_ADMIN")}/permissions",
                tenantToken(actor, "role.assign_permission"),
                """{"permission_code":"branch.view"}""",
                key,
            ).andExpect { forbiddenNaming("role.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))
        }

        @Test
        fun `removing a role permission without role view removes nothing`() {
            val actor = seedUser("permission-remover")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "role.remove_permission")
            val roleId = roleIdOf("TENANT_ADMIN")
            val grantId =
                dsl
                    .fetchOne(
                        "SELECT id FROM role_permission WHERE role_id = ? LIMIT 1",
                        roleId,
                    )!!
                    .get(0, UUID::class.java)
            val before = footprint(actor)

            // The permission check is the first thing the service does, before the grant is read,
            // so the refusal names the missing view (and an unknown grant id is refused alike).
            delete(
                "${ApiPaths.ROLES}/$roleId/permissions/$grantId",
                tenantToken(actor, "role.remove_permission"),
            ).andExpect { forbiddenNaming("role.view") }
            delete(
                "${ApiPaths.ROLES}/$roleId/permissions/${uuidV7()}",
                tenantToken(actor, "role.remove_permission"),
            ).andExpect { forbiddenNaming("role.view") }

            assertEquals(before, footprint(actor))
        }

        // ---- business date, settings, users, approvals ----------------------------------------

        @Test
        fun `a business date mutation without business date view changes nothing`() {
            val actor = seedUser("cob-starter")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "cob.start")
            val key = uuidV7()
            val before = footprint(actor)
            val status = businessDateStatus()

            post(
                "${ApiPaths.BUSINESS_DATE}/cob/start",
                tenantToken(actor, "cob.start"),
                """{"reason":"End of day"}""",
                key,
            ).andExpect { forbiddenNaming("business_date.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))
            assertEquals(status, businessDateStatus())
        }

        @Test
        fun `a tenant setting write without settings view writes nothing`() {
            val actor = seedUser("settings-writer")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "settings.update")
            val key = uuidV7()
            val before = footprint(actor)

            put(
                "${ApiPaths.TENANT_SETTINGS}/require_maker_checker_for_user_invites",
                tenantToken(actor, "settings.update"),
                """{"value":"true","reason":"Policy"}""",
                key,
            ).andExpect { forbiddenNaming("settings.view") }

            assertEquals(before, footprint(actor))
            assertEquals(0, idempotencyRows(key))
        }

        @Test
        fun `a platform user lifecycle mutation without user view changes nothing`() {
            val routes =
                mapOf(
                    "user.suspend" to ("suspend" to "ACTIVE"),
                    "user.activate" to ("reactivate" to "SUSPENDED"),
                    "user.deactivate" to ("deactivate" to "ACTIVE"),
                )

            routes.forEach { (code, route) ->
                val (action, status) = route
                val target = seedUser("lifecycle-target", status)
                val actor = seedUser("platform-user-$action")
                fixture.grantPlatformPermissionsExactly(actor, code)
                val key = uuidV7()
                val before = footprint(actor)
                val transitionsBefore = userTransitions(target)

                // A known and an unknown user are refused alike: the check precedes the lookup.
                listOf(target, uuidV7()).forEach { userId ->
                    post(
                        "${ApiPaths.PLATFORM_USERS}/$userId/$action",
                        platformToken(actor, setOf(code)),
                        """{"reason":"Regulatory review"}""",
                        key,
                    ).andExpect { forbiddenNaming("user.view") }
                }

                assertEquals(before, footprint(actor), code)
                assertEquals(0, idempotencyRows(key), code)
                assertEquals(status, userStatus(target), code)
                assertEquals(transitionsBefore, userTransitions(target), code)
            }
        }

        @Test
        fun `a platform user lifecycle route acts on a user with no platform membership`() {
            val actor = seedUser("platform-user-admin")
            fixture.grantPlatformPermissionsWithViews(
                actor,
                "user.suspend",
                "user.activate",
                "user.deactivate",
            )
            val target = seedUser("tenant-member-target")
            linkKeycloakIdentity(target)
            // An ACTIVE tenant membership with a role assignment, so deactivating the user runs
            // the assignment revoker before the route reads the user back.
            fixture.grantTenantAdmin(organisationId, target)
            val assignment = roleAssignmentOf(target)
            val body = """{"reason":"Regulatory review"}"""

            listOf(
                Triple("suspend", "user.suspend", "SUSPENDED"),
                Triple("reactivate", "user.activate", "ACTIVE"),
                Triple("deactivate", "user.deactivate", "DEACTIVATED"),
            ).forEach { (action, code, status) ->
                post(
                    "${ApiPaths.PLATFORM_USERS}/$target/$action",
                    platformToken(actor, setOf(code)),
                    body,
                ).andExpect {
                    status { isOk() }
                    jsonPath("$.user_id") { value(target.toString()) }
                    jsonPath("$.status") { value(status) }
                }
                assertEquals(status, userStatus(target))
            }
            assertEquals("REVOKED", roleAssignmentStatus(assignment))
        }

        @Test
        fun `the user lifecycle service authorises its own callers and not only the controller`() {
            val target = seedUser("service-lifecycle-target")
            val actor = seedUser("service-lifecycle-viewless")
            fixture.grantPlatformPermissionsExactly(
                actor,
                "user.suspend",
                "user.activate",
                "user.deactivate",
            )
            val before = footprint(actor)
            val reason = Reason.required("Regulatory review")

            val refusals =
                listOf(
                    {
                        userProvisioningService.suspendUser(
                            SuspendUserCommand(PlatformOrganisation.ID, target, actor, reason),
                        )
                    },
                    {
                        userProvisioningService.reactivateUser(
                            ReactivateUserCommand(PlatformOrganisation.ID, target, actor),
                        )
                    },
                    {
                        userProvisioningService.deactivateUser(
                            DeactivateUserCommand(PlatformOrganisation.ID, target, actor, reason),
                        )
                    },
                ).map { call ->
                    assertFailsWith<MissingPermissionException> { withRequestContext { call() } }
                }

            assertEquals(
                listOf("user.view", "user.view", "user.view"),
                refusals.map { it.permissionCode },
            )
            assertEquals(before, footprint(actor))
            assertEquals("ACTIVE", userStatus(target))
        }

        @Test
        fun `the user lifecycle read-back decides from the pre-check's answer`() {
            val actor = seedUser("memo-platform-user")
            val caller = PlatformCaller(actor, PlatformOrganisation.ID)
            fixture.grantPlatformPermissionsWithViews(actor, "user.suspend")
            val target = seedUser("memo-user-target")

            assertReadBackUsesPreCheck(
                actor,
                "user.view",
                preCheck = { permissionGuard.requirePlatformPermission(actor, "user.suspend") },
                readBack = { iamQueryService.getGlobalUser(target, caller) },
                afterwards = { iamQueryService.getGlobalUser(target, caller) },
                organisation = PlatformOrganisation.ID,
            )
        }

        @Test
        fun `approving a membership without membership view approves nothing`() {
            val membershipId = membershipOf(admin)
            val tenantActor = seedUser("tenant-approver")
            fixture.grantTenantPermissionsExactly(organisationId, tenantActor, "user.approve")
            val platformActor = seedUser("platform-approver")
            fixture.grantPlatformPermissionsExactly(platformActor, "user.approve")
            val tenantBefore = footprint(tenantActor)
            val platformBefore = footprint(platformActor)

            post(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/activate",
                tenantToken(tenantActor, "user.approve"),
                "{}",
            ).andExpect { forbiddenNaming("membership.view") }
            post(
                "${ApiPaths.PLATFORM_TENANTS}/$organisationId/memberships/$membershipId/activate",
                platformToken(platformActor, setOf("user.approve")),
                "{}",
            ).andExpect { forbiddenNaming("membership.view") }

            assertEquals(tenantBefore, footprint(tenantActor))
            assertEquals(platformBefore, footprint(platformActor))
        }

        @Test
        fun `each membership route without its view leaves a real membership untouched`() {
            val membershipId = membershipOf(admin)
            val routes =
                mapOf(
                    "membership.suspend" to "suspend",
                    "membership.reactivate" to "reactivate",
                    "membership.revoke" to "revoke",
                )

            routes.forEach { (code, action) ->
                val actor = seedUser("viewless-$action")
                fixture.grantTenantPermissionsExactly(organisationId, actor, code)
                val key = uuidV7()
                val before = footprint(actor)

                post(
                    "${ApiPaths.MEMBERSHIPS}/$membershipId/$action",
                    tenantToken(actor, code),
                    """{"reason":"Audit hold"}""",
                    key,
                ).andExpect { forbiddenNaming("membership.view") }

                assertEquals(before, footprint(actor), code)
                assertEquals(0, idempotencyRows(key), code)
                assertEquals("ACTIVE", membershipStatus(membershipId), code)
            }
        }

        @Test
        fun `the membership service authorises its own callers and not only the controller`() {
            val membershipId = membershipOf(admin)
            val actor = seedUser("service-viewless")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "membership.suspend")
            val before = footprint(actor)

            val suspension =
                assertFailsWith<MissingPermissionException> {
                    withRequestContext {
                        userProvisioningService.suspendMembership(
                            SuspendMembershipCommand(
                                organisationId,
                                membershipId,
                                actor,
                                Reason.required("Audit hold"),
                            ),
                        )
                    }
                }
            val approval =
                assertFailsWith<MissingPermissionException> {
                    withRequestContext {
                        userProvisioningService.approveUser(
                            ApproveUserCommand(organisationId, membershipId, actor),
                        )
                    }
                }

            assertEquals("membership.view", suspension.permissionCode)
            assertEquals("user.approve", approval.permissionCode)
            assertEquals(before, footprint(actor))
            assertEquals("ACTIVE", membershipStatus(membershipId))
        }

        @Test
        fun `the invitation service authorises its own callers and creates no user`() {
            val actor = seedUser("service-inviter")
            fixture.grantTenantPermissionsExactly(organisationId, actor, "user.invite")
            val partial = seedUser("service-inviter-membership-view")
            fixture.grantTenantPermissionsExactly(
                organisationId,
                partial,
                "user.invite",
                "membership.view",
            )
            val suffix = uuidV7().toString().takeLast(12)
            val email = "svc-$suffix@mrv.test"
            val before = footprint(actor)

            fun inviteAs(inviter: UUID) =
                assertFailsWith<MissingPermissionException> {
                    withRequestContext {
                        userProvisioningService.inviteUser(
                            InviteUserCommand(
                                organisationId = organisationId,
                                email = email,
                                username = "svc-$suffix",
                                displayName = "Invitee",
                                membershipType = MembershipType.STAFF,
                                branchAssignments = emptyList(),
                                roleAssignments =
                                    listOf(
                                        RoleAssignmentRequest(
                                            roleIdOf("TENANT_ADMIN"),
                                            RoleAssignmentScopeType.TENANT,
                                        ),
                                    ),
                                invitedBy = inviter,
                                sendKeycloakInvite = false,
                                sendApplicationInvite = false,
                            ),
                        )
                    }
                }

            assertEquals("membership.view", inviteAs(actor).permissionCode)
            assertEquals("user.view", inviteAs(partial).permissionCode)
            assertEquals(before, footprint(actor))
            // An invitation creates the user (and the membership) first: neither may exist.
            assertEquals(
                0,
                dsl.fetchCount(USER_ACCOUNT, USER_ACCOUNT.EMAIL.eq(email)),
            )
        }

        @Test
        fun `the platform membership read-back decides from the pre-check's answer`() {
            val actor = seedUser("memo-platform-membership")
            val caller: FoundationCaller = PlatformCaller(actor, PlatformOrganisation.ID)
            fixture.grantPlatformPermissionsWithViews(actor, "user.approve")
            val membershipId = membershipOf(admin)

            assertReadBackUsesPreCheck(
                actor,
                "membership.view",
                preCheck = { permissionGuard.requirePlatformPermission(actor, "user.approve") },
                readBack = { membership(membershipId, caller) },
                afterwards = { membership(membershipId, caller) },
                organisation = PlatformOrganisation.ID,
            )
        }

        // ---- the read-back never re-resolves ---------------------------------------------------

        @Test
        fun `the branch read-back decides from the pre-check's answer when the view is revoked`() {
            val branchId = activeBranch()
            val actor = seedUser("memo-branch")
            val caller = TenantCaller(actor, organisationId)
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "branch.suspend")

            assertReadBackUsesPreCheck(
                actor,
                "branch.view",
                preCheck = {
                    permissionGuard.requireBranchPermission(
                        actor,
                        organisationId,
                        branchId,
                        "branch.suspend",
                    )
                },
                readBack = { foundationQueryService.getBranch(organisationId, branchId, caller) },
                afterwards = { foundationQueryService.getBranch(organisationId, branchId, caller) },
            )
        }

        /**
         * Calls the guard and `getBranch` directly, as the controller does in sequence, so it
         * proves the memo property (the read-back decides from the pre-check's answer); it would
         * also pass before the read-back moved to the gated query. The MockMvc case below is the
         * end-to-end proof. The mutations are the ones a branch return can ask for: `branch.create`
         * for the maker's withdrawal, `branch.approve` for a checker's return, and `branch.update`.
         */
        @Test
        fun `the update and return read-backs decide from the pre-check's answer at the branch`() {
            val branchId = activeBranch()
            listOf("branch.update", "branch.create", "branch.approve").forEach { mutation ->
                val actor = seedUser("memo-$mutation")
                val caller = TenantCaller(actor, organisationId)
                fixture.grantBranchPermissionsWithViews(organisationId, branchId, actor, mutation)

                assertReadBackUsesPreCheck(
                    actor,
                    "branch.view",
                    preCheck = {
                        permissionGuard.requireBranchPermission(
                            actor,
                            organisationId,
                            branchId,
                            mutation,
                        )
                    },
                    readBack = {
                        foundationQueryService.getBranch(organisationId, branchId, caller)
                    },
                    afterwards = {
                        foundationQueryService.getBranch(organisationId, branchId, caller)
                    },
                )
            }
        }

        /**
         * Direct guard and `getBranch` calls, like the tenant case above (memo property only).
         * There is no platform branch update route, so the platform codes are those of the
         * platform submit, activate and return routes.
         */
        @Test
        fun `the platform checker branch read-back decides from the pre-check's answer`() {
            val branchId = activeBranch()
            listOf("branch.create", "branch.approve").forEach { mutation ->
                val actor = seedUser("memo-platform-$mutation")
                val caller: FoundationCaller = PlatformCaller(actor, PlatformOrganisation.ID)
                fixture.grantPlatformPermissionsWithViews(actor, mutation)

                assertReadBackUsesPreCheck(
                    actor,
                    "branch.view",
                    preCheck = { permissionGuard.requirePlatformPermission(actor, mutation) },
                    readBack = {
                        foundationQueryService.getBranch(organisationId, branchId, caller)
                    },
                    afterwards = {
                        foundationQueryService.getBranch(organisationId, branchId, caller)
                    },
                    organisation = PlatformOrganisation.ID,
                )
            }
        }

        @Test
        fun `a branch update whose view is revoked mid-request still answers and commits`() {
            val branchId = activeBranch()
            val actor = seedUser("branch-updater-revoked")
            fixture.grantBranchPermissionsWithViews(
                organisationId,
                branchId,
                actor,
                "branch.update",
            )
            // Revoke the view after the service has checked it and changed the branch, and before
            // the controller's gated read-back: only the per-request memo lets the read-back pass.
            doAnswer { invocation ->
                invocation.callRealMethod().also {
                    revokeFromNarrowRole(actor, organisationId, "branch.view")
                }
            }.whenever(branchProvisioningService).update(any())

            mockMvc
                .patch("${ApiPaths.BRANCHES}/$branchId") {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"branch_name":"Renamed Branch"}"""
                    with(authentication(tenantToken(actor, "branch.update", branchId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.branch_name") { value("Renamed Branch") }
                }

            assertEquals("Renamed Branch", branchName(branchId))
            // The revocation was real: a new request is refused the read.
            mockMvc
                .get("${ApiPaths.BRANCHES}/$branchId") {
                    with(authentication(tenantToken(actor, "branch.update", branchId)))
                }.andExpect { forbiddenGeneric() }
        }

        @Test
        fun `the platform tenant read-back decides from the pre-check's answer`() {
            val actor = seedUser("memo-platform")
            val caller: FoundationCaller = PlatformCaller(actor, PlatformOrganisation.ID)
            fixture.grantPlatformPermissionsWithViews(actor, "tenant.suspend")

            assertReadBackUsesPreCheck(
                actor,
                "tenant.view",
                preCheck = { permissionGuard.requirePlatformPermission(actor, "tenant.suspend") },
                readBack = { foundationQueryService.getTenant(organisationId, caller) },
                afterwards = { foundationQueryService.getTenant(organisationId, caller) },
                organisation = PlatformOrganisation.ID,
            )
        }

        @Test
        fun `the membership read-back decides from the pre-check's answer`() {
            val actor = seedUser("memo-membership")
            val caller = TenantCaller(actor, organisationId)
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "membership.suspend")
            val membershipId = membershipOf(admin)

            assertReadBackUsesPreCheck(
                actor,
                "membership.view",
                preCheck = {
                    permissionGuard.requireTenantPermission(
                        actor,
                        organisationId,
                        "membership.suspend",
                    )
                },
                readBack = { membership(membershipId, caller) },
                afterwards = { membership(membershipId, caller) },
            )
        }

        @Test
        fun `the role read-back decides from the pre-check's answer`() {
            val actor = seedUser("memo-role")
            val caller = TenantCaller(actor, organisationId)
            fixture.grantTenantPermissionsWithViews(organisationId, actor, "role.update")
            val roleId = roleIdOf("TENANT_ADMIN")

            assertReadBackUsesPreCheck(
                actor,
                "role.view",
                preCheck = {
                    permissionGuard.requireTenantPermission(actor, organisationId, "role.update")
                },
                readBack = { iamQueryService.getRole(organisationId, roleId, caller) },
                afterwards = { iamQueryService.getRole(organisationId, roleId, caller) },
            )
        }

        /**
         * One request context is one `RequestPermissionCache`. After the pre-check passed, the
         * [view] is revoked in the database and the shared cache cleared, so any re-resolution
         * would now refuse. The read-back still passes (it hits the memo), and a **new** request
         * is refused with the view named, which proves the revocation was real.
         */
        private fun assertReadBackUsesPreCheck(
            actor: UUID,
            view: String,
            preCheck: () -> Unit,
            readBack: () -> Unit,
            afterwards: () -> Unit,
            organisation: UUID = organisationId,
        ) {
            withRequestContext {
                preCheck()
                revokeFromNarrowRole(actor, organisation, view)
                readBack()
            }
            val refusal =
                assertFailsWith<MissingPermissionException> { withRequestContext(afterwards) }
            assertEquals(view, refusal.permissionCode)
        }

        // ---- helpers --------------------------------------------------------------------------

        private fun membership(
            membershipId: UUID,
            caller: FoundationCaller,
        ) = iamQueryService.getMembership(organisationId, membershipId, caller)

        private fun revokeFromNarrowRole(
            actor: UUID,
            organisation: UUID,
            view: String,
        ) {
            dsl.execute(
                "DELETE FROM role_permission WHERE organisation_id = ? " +
                    "AND permission_id = (SELECT id FROM permission WHERE permission_code = ?) " +
                    "AND role_id IN (SELECT role_id FROM user_role_assignment " +
                    "WHERE user_id = ? AND organisation_id = ?)",
                organisation,
                view,
                actor,
                organisation,
            )
            // invalidate() deletes synchronously; the Redis cache's clear() may run asynchronously.
            cacheManager.getCache(EffectivePermissionResolver.CACHE_NAME)?.invalidate()
        }

        private fun MockMvcResultMatchersDsl.forbiddenGeneric() {
            status { isForbidden() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.code") { value("forbidden") }
        }

        private fun MockMvcResultMatchersDsl.forbiddenNaming(code: String) {
            status { isForbidden() }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.code") { value("forbidden") }
            jsonPath("$.detail") { value("Missing permission: $code.") }
        }

        private fun suspendBranch(
            branchId: UUID,
            token: AppPrincipalAuthenticationToken,
            key: UUID = uuidV7(),
        ) = post(
            "${ApiPaths.BRANCHES}/$branchId/suspend",
            token,
            """{"reason":"Audit hold"}""",
            key,
        )

        private fun platformSuspend(
            tenantId: UUID,
            actor: UUID,
            key: UUID = uuidV7(),
        ) = post(
            "${ApiPaths.PLATFORM_TENANTS}/$tenantId/suspend",
            platformToken(actor, setOf("tenant.suspend")),
            """{"reason":"Regulatory review"}""",
            key,
        )

        private fun invite(token: AppPrincipalAuthenticationToken): ResultActionsDsl {
            val suffix = uuidV7().toString().takeLast(12)
            return post(
                ApiPaths.TENANT_USERS,
                token,
                """{"email":"mrv-$suffix@mrv.test","username":"mrv-$suffix",""" +
                    """"display_name":"Invitee","membership_type":"STAFF",""" +
                    """"primary_branch_id":"${uuidV7()}","branch_assignments":[],""" +
                    """"role_assignments":[{"role_id":"${uuidV7()}","scope_type":"TENANT"}]}""",
            )
        }

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
            body: String,
            key: UUID = uuidV7(),
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key.toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token))
            }

        private fun put(
            path: String,
            token: AppPrincipalAuthenticationToken,
            body: String,
            key: UUID = uuidV7(),
        ): ResultActionsDsl =
            mockMvc.put(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key.toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token))
            }

        private fun delete(
            path: String,
            token: AppPrincipalAuthenticationToken,
            key: UUID = uuidV7(),
        ): ResultActionsDsl =
            mockMvc.delete(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key.toString())
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
            val approver = tenantToken(checker, "branch.approve")
            post("${ApiPaths.BRANCHES}/$branchId/activate", approver, "{}")
                .andExpect { status { isOk() } }
            return branchId
        }

        /** A branch that is already SUSPENDED, so a second suspend is a state conflict. */
        private fun suspendedBranch(): UUID {
            val branchId = activeBranch()
            suspendBranch(branchId, tenantToken(admin, "branch.suspend"))
                .andExpect { status { isOk() } }
            return branchId
        }

        private fun branchBody() =
            """{"branch_code":"BR-${uuidV7().toString().takeLast(8).uppercase()}",""" +
                """"branch_name":"Riverside Branch","branch_type":"OPERATIONAL",""" +
                """"timezone":"Africa/Nairobi"}"""

        /**
         * Everything a refused request must not touch: the tenant's lifecycle, assignment,
         * role-grant, setting and business-date state, and every audit row written by [actor]
         * (filtered by actor so a background writer cannot disturb the comparison).
         */
        private fun footprint(actor: UUID): List<Long> =
            dsl
                .fetchOne(
                    "SELECT " +
                        "(SELECT count(*) FROM audit_event WHERE actor_user_id = ?), " +
                        "(SELECT count(*) FROM organisation_transition_log " +
                        "WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM branch_transition_log WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM user_organisation_membership_transition_log " +
                        "WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM branch WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM role WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM role_permission WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM user_role_assignment " +
                        "WHERE organisation_id = ? AND status = 'ACTIVE'), " +
                        "(SELECT count(*) FROM user_branch_assignment " +
                        "WHERE organisation_id = ? AND status = 'ACTIVE'), " +
                        "(SELECT count(*) FROM organisation_setting WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM business_date_history WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM user_organisation_membership " +
                        "WHERE organisation_id = ?), " +
                        "(SELECT count(*) FROM user_account WHERE email LIKE '%@mrv.test')",
                    actor,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                    organisationId,
                )!!
                .intoArray()
                .map { (it as Number).toLong() }

        private fun auditRows(actor: UUID): Int =
            dsl
                .fetchOne("SELECT count(*) FROM audit_event WHERE actor_user_id = ?", actor)!!
                .get(0, Int::class.java)

        private fun idempotencyRows(key: UUID): Int =
            dsl
                .fetchOne(
                    "SELECT count(*) FROM api_idempotency_record WHERE idempotency_key = ?",
                    key,
                )!!
                .get(0, Int::class.java)

        private fun branchName(branchId: UUID): String =
            dsl
                .fetchOne("SELECT branch_name FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun branchStatus(branchId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun businessDateStatus(): String =
            dsl
                .fetchOne(
                    "SELECT status || ' ' || current_business_date FROM business_date " +
                        "WHERE organisation_id = ?",
                    organisationId,
                )!!
                .get(0, String::class.java)

        private fun membershipStatus(membershipId: UUID): String =
            dsl
                .fetchOne(
                    "SELECT membership_status FROM user_organisation_membership WHERE id = ?",
                    membershipId,
                )!!
                .get(0, String::class.java)

        private fun userStatus(userId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM user_account WHERE id = ?", userId)!!
                .get(0, String::class.java)

        private fun roleAssignmentOf(userId: UUID): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM user_role_assignment WHERE organisation_id = ? " +
                        "AND user_id = ? AND status = 'ACTIVE'",
                    organisationId,
                    userId,
                )!!
                .get(0, UUID::class.java)

        private fun userTransitions(userId: UUID): Int =
            dsl
                .fetchOne(
                    "SELECT count(*) FROM user_account_transition_log WHERE entity_id = ?",
                    userId,
                )!!
                .get(0, Int::class.java)

        private fun roleAssignmentStatus(assignmentId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM user_role_assignment WHERE id = ?", assignmentId)!!
                .get(0, String::class.java)

        private fun tenantStatus(tenantId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM organisation WHERE id = ?", tenantId)!!
                .get(0, String::class.java)

        private fun membershipOf(userId: UUID): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM user_organisation_membership " +
                        "WHERE organisation_id = ? AND user_id = ?",
                    organisationId,
                    userId,
                )!!
                .get(0, UUID::class.java)

        private fun roleIdOf(code: String): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = ?",
                    organisationId,
                    code,
                )!!
                .get(0, UUID::class.java)

        private fun tenantToken(
            userId: UUID,
            permission: String,
            pinnedTo: UUID? = null,
        ) = token(userId, organisationId, setOf(permission), pinnedTo)

        private fun platformToken(
            userId: UUID,
            permissions: Set<String>,
        ) = token(userId, PlatformOrganisation.ID, permissions)

        private fun token(
            userId: UUID,
            tenantId: UUID,
            permissions: Set<String>,
            pinnedTo: UUID? = null,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = pinnedTo,
                email = "user@mrv.test",
                fullName = "Mutation View User",
                permissions = permissions,
            ),
        )

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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@seed.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, status)
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        private companion object {
            val BRANCH_ID = Regex("\"branch_id\"\\s*:\\s*\"([^\"]+)\"")
        }
    }
