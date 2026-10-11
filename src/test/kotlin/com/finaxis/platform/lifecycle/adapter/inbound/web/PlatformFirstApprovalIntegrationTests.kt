package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.BranchAssignmentEntryDto
import com.finaxis.platform.iam.adapter.inbound.web.dto.InviteUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleAssignmentEntryDto
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.InitialAdminDto
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
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

/**
 * Full-stack proof of the platform-checker policy (#153, ADR 0028): a freshly approved tenant
 * onboards a second person and activates a first branch using API calls only, with the platform
 * administrator as the audited checker and the tenant administrator as the maker; the tenant's own
 * maker-checker rule is unchanged; and platform branch creation needs the platform permission
 * alone.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("LargeClass", "TooManyFunctions", "LongMethod")
class PlatformFirstApprovalIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        organisationProvisioningService: OrganisationProvisioningService,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

        /** Two platform administrators, one tenant administrator and an API-approved tenant. */
        private inner class Scenario {
            val platformMaker = seedUser("pmaker").also { fixture.grantPlatformSuperAdmin(it) }
            val platformChecker = seedUser("pchecker").also { fixture.grantPlatformSuperAdmin(it) }
            val tenantAdmin = seedUser("tadmin")
            val tenantId = provisionTenantThroughApi()

            init {
                // Stands in for the asynchronous bootstrap: the tenant's first administrator.
                fixture.grantTenantAdmin(tenantId, tenantAdmin)
            }

            fun createBranchAsTenantAdmin(code: String = "BR-${shortId().uppercase()}"): UUID =
                idOf(
                    tenantPost(ApiPaths.BRANCHES, tenantAdmin, tenantId, branchBody(code))
                        .andExpect { status { isCreated() } }
                        .andReturn()
                        .response.contentAsString,
                    "branch_id",
                )

            fun submitAsTenantAdmin(branchId: UUID) {
                tenantPost("${ApiPaths.BRANCHES}/$branchId/submit", tenantAdmin, tenantId)
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.status") { value("PENDING_APPROVAL") }
                    }
            }

            fun inviteAsTenantAdmin(
                email: String,
                branchId: UUID,
            ): UUID =
                idOf(
                    tenantPost(
                        ApiPaths.TENANT_USERS,
                        tenantAdmin,
                        tenantId,
                        inviteBody(email, branchId, tenantId),
                    ).andExpect { status { isCreated() } }
                        .andReturn()
                        .response.contentAsString,
                    "membership_id",
                )

            /** A platform administrator whose only permissions are [code] and its views. */
            fun narrowChecker(code: String): UUID =
                seedUser("narrow").also { fixture.grantPlatformPermissionsWithViews(it, code) }

            /** A platform administrator holding exactly [code]: no view, so it is refused. */
            fun viewlessChecker(code: String): UUID =
                seedUser("viewless").also { fixture.grantPlatformPermissionsExactly(it, code) }

            fun activeBranch(): UUID {
                val branchId = createBranchAsTenantAdmin()
                submitAsTenantAdmin(branchId)
                platformPost(
                    "${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/activate",
                    platformChecker,
                ).andExpect { status { isOk() } }
                return branchId
            }
        }

        @Test
        fun `a new tenant onboards a second user and a first branch via the platform checker`() {
            val s = Scenario()
            val branchId = s.createBranchAsTenantAdmin()
            s.submitAsTenantAdmin(branchId)

            // A tenant submission carries no platform marker; only the platform path writes one.
            assertEquals(
                emptyList<String>(),
                platformAuditActors(
                    s.tenantId,
                    "branch.submit_as_platform_checker",
                    branchId,
                    s.platformChecker,
                ),
            )

            // The tenant's own maker-checker rule is untouched: the maker cannot activate it.
            tenantPost("${ApiPaths.BRANCHES}/$branchId/activate", s.tenantAdmin, s.tenantId)
                .andExpect { status { isForbidden() } }

            // The platform administrator checks it on the tenant's behalf.
            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/activate",
                s.platformChecker,
            ).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.status") { value("ACTIVE") }
            }

            // A second person with an existing identity joins the tenant.
            val email = "second-${shortId()}@tenant.test"
            seedIdentifiedUser(email)
            val membershipId = s.inviteAsTenantAdmin(email, branchId)

            // The inviter cannot approve their own invitation, whatever authorities they hold.
            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/activate",
                s.tenantAdmin,
                s.tenantId,
            ).andExpect { status { isForbidden() } }

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate",
                s.platformChecker,
            ).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(membershipId.toString()) }
                jsonPath("$.organisation_id") { value(s.tenantId.toString()) }
                jsonPath("$.membership_status") { value("ACTIVE") }
            }

            // The activation notification pipeline fires exactly as on the tenant path.
            assertEquals(
                1,
                outboxRecords("finaxis.lifecycle.membership.activated", membershipId.toString()),
            )
            assertEquals(
                1,
                outboxRecords("finaxis.lifecycle.branch.activated", branchId.toString()),
            )

            // The tenant maker's refused activation left its own DENIED row (#251).
            assertEquals(
                listOf(s.tenantAdmin.toString()),
                platformAuditActors(
                    s.tenantId,
                    "branch.activate",
                    branchId,
                    s.platformChecker,
                    outcome = "DENIED",
                ),
            )

            // Both checker steps are audited with the platform actor and the tenant id, and the
            // rows are readable in the tenant's own log and through the platform tenant log.
            val userId = idOf(membershipBody(s, membershipId), "user_id")
            listOf(
                "user.approve" to userId,
                "branch.activate" to branchId,
                "branch.activate_as_platform_checker" to branchId,
            ).forEach { (action, resourceId) ->
                assertEquals(
                    listOf(s.platformChecker.toString()),
                    platformAuditActors(s.tenantId, action, resourceId, s.platformChecker),
                    action,
                )
                mockMvc
                    .get(ApiPaths.AUDIT_EVENTS) {
                        param("action", action)
                        param("outcome", "SUCCESS")
                        with(authentication(tenantToken(s.tenantAdmin, s.tenantId)))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.items[?(@.resource_id == '$resourceId')].actor_id") {
                            value(s.platformChecker.toString())
                        }
                    }
            }
        }

        @Test
        fun `a new user's approval by the platform queues identity provisioning`() {
            val s = Scenario()
            val membershipId =
                s.inviteAsTenantAdmin("brand-new-${shortId()}@tenant.test", s.activeBranch())

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate",
                s.platformChecker,
            ).andExpect {
                status { isAccepted() }
                jsonPath("$.membership_status") { value("PENDING_APPROVAL") }
            }

            assertEquals(
                1,
                outboxRecords(
                    "finaxis.lifecycle.user.keycloak-provisioning-requested",
                    membershipId.toString(),
                ),
            )
        }

        @Test
        fun `a platform checker holding user approve and membership view approves a membership`() {
            val s = Scenario()
            val onlyApprove = s.narrowChecker("user.approve")
            val email = "narrow-${shortId()}@tenant.test"
            seedIdentifiedUser(email)
            val membershipId = s.inviteAsTenantAdmin(email, s.activeBranch())

            narrowPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate",
                onlyApprove,
                "user.approve",
            ).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(membershipId.toString()) }
                jsonPath("$.organisation_id") { value(s.tenantId.toString()) }
                jsonPath("$.membership_status") { value("ACTIVE") }
            }

            assertEquals("ACTIVE", membershipStatus(membershipId))
            assertEquals(
                1,
                outboxRecords("finaxis.lifecycle.membership.activated", membershipId.toString()),
            )
            val userId = idOf(membershipBody(s, membershipId), "user_id")
            assertEquals(
                listOf(onlyApprove.toString()),
                platformAuditActors(s.tenantId, "user.approve", userId, s.platformChecker),
            )
        }

        @Test
        fun `a platform checker holding the mutation permission without its view is refused`() {
            val s = Scenario()
            val branchId = s.createBranchAsTenantAdmin()
            s.submitAsTenantAdmin(branchId)
            val viewless = s.viewlessChecker("branch.approve")

            narrowPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/activate",
                viewless,
                "branch.approve",
            ).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("Missing permission: branch.view.") }
            }

            // Nothing changed: still pending, no activation event.
            assertEquals("PENDING_APPROVAL", branchStatus(branchId))
            assertEquals(
                0,
                outboxRecords("finaxis.lifecycle.branch.activated", branchId.toString()),
            )
        }

        @Test
        fun `a platform checker holding branch create and branch view can submit a branch`() {
            val s = Scenario()
            val onlyCreate = s.narrowChecker("branch.create")
            val branchId = s.createBranchAsTenantAdmin()

            narrowPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/submit",
                onlyCreate,
                "branch.create",
            ).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.status") { value("PENDING_APPROVAL") }
            }

            assertEquals("PENDING_APPROVAL", branchStatus(branchId))
            assertEquals(onlyCreate, branchTransitionActor(branchId, "SUBMIT"))
            assertEquals(
                listOf(onlyCreate.toString()),
                platformAuditActors(
                    s.tenantId,
                    "branch.submit_as_platform_checker",
                    branchId,
                    s.platformChecker,
                ),
            )
        }

        @Test
        fun `a platform checker holding branch approve and branch view can activate a branch`() {
            val s = Scenario()
            val onlyApprove = s.narrowChecker("branch.approve")
            val branchId = s.createBranchAsTenantAdmin()
            s.submitAsTenantAdmin(branchId)

            narrowPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/activate",
                onlyApprove,
                "branch.approve",
            ).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.status") { value("ACTIVE") }
            }

            assertEquals("ACTIVE", branchStatus(branchId))
            assertEquals(
                1,
                outboxRecords("finaxis.lifecycle.branch.activated", branchId.toString()),
            )
            assertEquals(
                listOf(onlyApprove.toString()),
                platformAuditActors(
                    s.tenantId,
                    "branch.activate_as_platform_checker",
                    branchId,
                    s.platformChecker,
                ),
            )
        }

        @Test
        fun `a platform actor cannot approve what it created`() {
            val s = Scenario()
            val branchId =
                idOf(
                    platformPost(
                        "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches",
                        s.platformMaker,
                        branchBody("PB-${shortId().uppercase()}"),
                    ).andExpect { status { isCreated() } }
                        .andReturn()
                        .response.contentAsString,
                    "branch_id",
                )
            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/submit",
                s.platformMaker,
            ).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("PENDING_APPROVAL") }
            }

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/activate",
                s.platformMaker,
            ).andExpect { status { isForbidden() } }

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/activate",
                s.platformChecker,
            ).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ACTIVE") }
            }
        }

        @Test
        fun `a tenant context cannot use the platform checker routes`() {
            val s = Scenario()
            val branchId = s.createBranchAsTenantAdmin()
            val membershipId = s.inviteAsTenantAdmin("t-${shortId()}@tenant.test", s.activeBranch())

            listOf(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/submit",
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/activate",
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate",
            ).forEach { path ->
                tenantPost(path, s.tenantAdmin, s.tenantId).andExpect { status { isForbidden() } }
            }
            assertEquals("DRAFT", branchStatus(branchId))
        }

        @Test
        fun `a platform caller without the platform permission is refused`() {
            val s = Scenario()
            val branchId = s.createBranchAsTenantAdmin()
            s.submitAsTenantAdmin(branchId)
            val membershipId = s.inviteAsTenantAdmin("p-${shortId()}@tenant.test", s.activeBranch())
            val support = seedUser("support").also { fixture.grantPlatformSupport(it) }
            val stranger = seedUser("stranger")

            // Both carry the coarse authorities in their token, so only the application-layer
            // check against the PLATFORM organisation can refuse them. Ids are deliberately
            // unknown: the permission check must come before any existence lookup.
            listOf(support, stranger).forEach { actor ->
                listOf(
                    "branches/$branchId/activate",
                    "branches/${uuidV7()}/activate",
                    "branches/$branchId/submit",
                    "memberships/$membershipId/activate",
                    "memberships/${uuidV7()}/activate",
                ).forEach { route ->
                    platformPost("${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/$route", actor)
                        .andExpect { status { isForbidden() } }
                }
                platformPost(
                    "${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/branches",
                    actor,
                    branchBody("NOPE-${shortId().uppercase()}"),
                ).andExpect { status { isForbidden() } }
            }
            assertEquals("PENDING_APPROVAL", branchStatus(branchId))
        }

        @Test
        fun `an id of another tenant or of nothing is not found under the path tenant`() {
            val s = Scenario()
            val other = Scenario()
            val otherBranch = other.createBranchAsTenantAdmin()
            other.submitAsTenantAdmin(otherBranch)
            val otherMembership =
                other.inviteAsTenantAdmin("o-${shortId()}@tenant.test", other.activeBranch())
            val base = "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}"

            listOf(
                "$base/branches/$otherBranch/submit",
                "$base/branches/$otherBranch/activate",
                "$base/branches/${uuidV7()}/activate",
                "$base/memberships/$otherMembership/activate",
                "$base/memberships/${uuidV7()}/activate",
                "${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/branches/$otherBranch/activate",
                "${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/memberships/$otherMembership/activate",
            ).forEach { path ->
                platformPost(path, s.platformChecker).andExpect { status { isNotFound() } }
            }
            assertEquals("PENDING_APPROVAL", branchStatus(otherBranch))
            assertEquals("PENDING_APPROVAL", membershipStatus(otherMembership))
        }

        @Test
        fun `platform branch creation needs no tenant membership and allows provisioning`() {
            val s = Scenario()
            // The platform administrator holds no membership in the tenant at all.
            assertEquals(
                0,
                dsl.fetchCount(
                    USER_ORGANISATION_MEMBERSHIP,
                    USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID
                        .eq(s.tenantId)
                        .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(s.platformMaker)),
                ),
            )

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches",
                s.platformMaker,
                branchBody("ACT-${shortId().uppercase()}"),
            ).andExpect {
                status { isCreated() }
                jsonPath("$.status") { value("DRAFT") }
            }

            setTenantStatus(s.tenantId, "PROVISIONING")
            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches",
                s.platformMaker,
                branchBody("PRV-${shortId().uppercase()}"),
            ).andExpect { status { isCreated() } }

            setTenantStatus(s.tenantId, "SUSPENDED")
            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches",
                s.platformMaker,
                branchBody("SUS-${shortId().uppercase()}"),
            ).andExpect { status { isConflict() } }

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${uuidV7()}/branches",
                s.platformMaker,
                branchBody("GONE-${shortId().uppercase()}"),
            ).andExpect { status { isNotFound() } }
        }

        @Test
        fun `a platform administrator cannot approve their own membership in a tenant`() {
            val s = Scenario()
            val branchId = s.activeBranch()
            // The tenant maker invites an existing account that happens to be the platform checker.
            linkIdentity(s.platformChecker)
            val membershipId =
                s.inviteAsTenantAdmin("pchecker-${s.platformChecker}@example.test", branchId)

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate",
                s.platformChecker,
            ).andExpect { status { isForbidden() } }

            // Nor can the same user approve it from the tenant side.
            tenantPost(
                "${ApiPaths.MEMBERSHIPS}/$membershipId/activate",
                s.platformChecker,
                s.tenantId,
            ).andExpect { status { isForbidden() } }

            assertEquals("PENDING_APPROVAL", membershipStatus(membershipId))
            listOf(
                "finaxis.lifecycle.membership.activated",
                "finaxis.lifecycle.user.keycloak-provisioning-requested",
            ).forEach { assertEquals(0, outboxRecords(it, membershipId.toString()), it) }

            // A different platform administrator still can.
            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate",
                s.platformMaker,
            ).andExpect { status { is2xxSuccessful() } }
        }

        @Test
        fun `a platform actor that submitted a tenant draft cannot activate it`() {
            val s = Scenario()
            val branchId = s.createBranchAsTenantAdmin()
            val base = "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId"

            platformPost("$base/submit", s.platformChecker)
                .andExpect { status { isOk() } }
            platformPost("$base/activate", s.platformChecker)
                .andExpect { status { isForbidden() } }
            assertEquals("PENDING_APPROVAL", branchStatus(branchId))

            platformPost("$base/activate", s.platformMaker).andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ACTIVE") }
            }
        }

        @Test
        fun `the platform checker routes refuse the platform organisation as the tenant`() {
            val s = Scenario()
            val base = "${ApiPaths.PLATFORM_TENANTS}/${PlatformOrganisation.ID}"

            listOf(
                "$base/branches/${uuidV7()}/submit",
                "$base/branches/${uuidV7()}/activate",
                "$base/memberships/${uuidV7()}/activate",
            ).forEach { platformPost(it, s.platformChecker).andExpect { status { isNotFound() } } }
            platformPost(
                "$base/branches",
                s.platformChecker,
                branchBody("PLAT-${shortId().uppercase()}"),
            ).andExpect { status { isNotFound() } }
        }

        @Test
        fun `platform submission is refused while the tenant is not open for branches`() {
            val s = Scenario()
            val branchId = s.createBranchAsTenantAdmin()
            setTenantStatus(s.tenantId, "SUSPENDED")

            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches/$branchId/submit",
                s.platformChecker,
            ).andExpect { status { isConflict() } }
            assertEquals("DRAFT", branchStatus(branchId))
        }

        @Test
        fun `the platform checker closes for branches once the tenant has its own active branch`() {
            val s = Scenario()
            s.activeBranch()
            val second = s.createBranchAsTenantAdmin()
            s.submitAsTenantAdmin(second)
            val third = s.createBranchAsTenantAdmin()
            val base = "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/branches"

            platformPost("$base/$second/activate", s.platformMaker).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("lifecycle.platform_checker_closed") }
            }
            platformPost("$base/$third/submit", s.platformMaker)
                .andExpect { status { isConflict() } }

            assertEquals("PENDING_APPROVAL", branchStatus(second))
            assertEquals("DRAFT", branchStatus(third))
            // Creating a draft is not a checker step and stays open.
            platformPost(base, s.platformMaker, branchBody("MORE-${shortId().uppercase()}"))
                .andExpect { status { isCreated() } }
        }

        @Test
        fun `the platform checker closes for memberships once the tenant has its own member`() {
            val s = Scenario()
            val branchId = s.activeBranch()
            val firstEmail = "first-${shortId()}@tenant.test"
            seedIdentifiedUser(firstEmail)
            val first = s.inviteAsTenantAdmin(firstEmail, branchId)
            val secondEmail = "second-${shortId()}@tenant.test"
            seedIdentifiedUser(secondEmail)
            val second = s.inviteAsTenantAdmin(secondEmail, branchId)
            val base = "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships"

            platformPost("$base/$first/activate", s.platformChecker)
                .andExpect { status { isOk() } }
            platformPost("$base/$second/activate", s.platformMaker).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("lifecycle.platform_checker_closed") }
            }

            assertEquals("PENDING_APPROVAL", membershipStatus(second))
            assertEquals(
                0,
                outboxRecords("finaxis.lifecycle.membership.activated", second.toString()),
            )
        }

        @Test
        fun `a tenant cannot be approved by the account it names as initial administrator`() {
            val maker = seedUser("im-maker").also { fixture.grantPlatformSuperAdmin(it) }
            val approver = seedUser("im-approver").also { fixture.grantPlatformSuperAdmin(it) }
            val other = seedUser("im-other").also { fixture.grantPlatformSuperAdmin(it) }
            val tenantId = submittedTenant(maker, "im-approver-$approver@example.test")
            val base = "${ApiPaths.PLATFORM_TENANTS}/$tenantId"
            val outboxBefore = outboxRecordsFor(tenantId.toString())

            platformPost("$base/approve", approver).andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("lifecycle.approver_is_initial_administrator") }
            }
            assertEquals("PENDING_APPROVAL", tenantStatus(tenantId))
            assertEquals(outboxBefore, outboxRecordsFor(tenantId.toString()))
            assertEquals("PENDING_ACTIVATION", bootstrapStatus(tenantId))

            // Any other approver still approves and queues the bootstrap.
            platformPost("$base/approve", other).andExpect {
                status { isAccepted() }
                jsonPath("$.status") { value("ACTIVE") }
            }
            assertEquals("QUEUED", bootstrapStatus(tenantId))
        }

        @Test
        fun `a remark on an immediate tenant activation is the status reason and audited`() {
            val s = Scenario()
            val email = "remark-${shortId()}@tenant.test"
            seedIdentifiedUser(email)
            val membershipId = s.inviteAsTenantAdmin(email, s.activeBranch())
            val userId = idOf(membershipBody(s, membershipId), "user_id")
            val secondAdmin = seedUser("tadmin2").also { fixture.grantTenantAdmin(s.tenantId, it) }
            val key = uuidV7().toString()
            val activatePath = "${ApiPaths.MEMBERSHIPS}/$membershipId/activate"
            val body = remarkBody("Checked against the signed request form.")

            val first =
                tenantPostWithKey(activatePath, secondAdmin, s, key, body)
                    .andExpect {
                        status { isOk() }
                        jsonPath("$.membership_status") { value("ACTIVE") }
                    }.andReturn()
                    .response.contentAsString
            val replay =
                tenantPostWithKey(activatePath, secondAdmin, s, key, body)
                    .andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString

            assertEquals(first, replay)
            assertEquals(
                "Checked against the signed request form.",
                membershipStatusReason(membershipId),
            )
            assertEquals(
                listOf("Checked against the signed request form."),
                auditReasons(s.tenantId, "user.approve", userId),
            )
            assertEquals(
                listOf("Checked against the signed request form."),
                auditReasons(s.tenantId, "membership.activate", membershipId),
            )
            assertEquals(
                1,
                outboxRecords("finaxis.lifecycle.membership.activated", membershipId.toString()),
            )
        }

        @Test
        fun `a remark on the platform membership route is persisted`() {
            val s = Scenario()
            val branchId = s.activeBranch()
            val withRemark = "remark-${shortId()}@tenant.test"
            seedIdentifiedUser(withRemark)
            val remarked = s.inviteAsTenantAdmin(withRemark, branchId)
            val base = "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships"

            val remark = remarkBody("First-line checker.")
            platformPost("$base/$remarked/activate", s.platformChecker, remark)
                .andExpect { status { isOk() } }

            assertEquals("First-line checker.", membershipStatusReason(remarked))
            assertEquals(
                listOf("First-line checker."),
                auditReasons(s.tenantId, "membership.activate", remarked),
            )
            val userId = idOf(membershipBody(s, remarked), "user_id")
            assertEquals(
                listOf("First-line checker."),
                auditReasons(s.tenantId, "user.approve", userId),
            )
        }

        @Test
        fun `a remark on a queued activation lives only on the user approve audit row`() {
            val s = Scenario()
            val membershipId =
                s.inviteAsTenantAdmin("queued-${shortId()}@tenant.test", s.activeBranch())
            val userId = idOf(membershipBody(s, membershipId), "user_id")
            val key = uuidV7().toString()
            val path =
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate"
            val body = remarkBody("Approved pending identity creation.")

            val first =
                platformPostWithKey(path, s.platformChecker, key, body)
                    .andExpect {
                        status { isAccepted() }
                        jsonPath("$.membership_status") { value("PENDING_APPROVAL") }
                    }.andReturn()
                    .response.contentAsString
            val replay =
                platformPostWithKey(path, s.platformChecker, key, body)
                    .andExpect { status { isAccepted() } }
                    .andReturn()
                    .response.contentAsString

            assertEquals(first, replay)
            assertEquals(
                listOf("Approved pending identity creation."),
                auditReasons(s.tenantId, "user.approve", userId),
            )
            assertEquals(null, membershipStatusReason(membershipId))
            assertEquals(emptyList(), auditReasons(s.tenantId, "membership.activate", membershipId))
        }

        @Test
        fun `a remark over 500 characters on either membership route is a 400`() {
            val s = Scenario()
            val email = "toolong-${shortId()}@tenant.test"
            seedIdentifiedUser(email)
            val membershipId = s.inviteAsTenantAdmin(email, s.activeBranch())
            val secondAdmin = seedUser("tadmin3").also { fixture.grantTenantAdmin(s.tenantId, it) }
            val tooLong = remarkBody("x".repeat(501))

            val tenantRoute = "${ApiPaths.MEMBERSHIPS}/$membershipId/activate"
            tenantPost(tenantRoute, secondAdmin, s.tenantId, tooLong)
                .andExpect { status { isBadRequest() } }
            platformPost(
                "${ApiPaths.PLATFORM_TENANTS}/${s.tenantId}/memberships/$membershipId/activate",
                s.platformChecker,
                tooLong,
            ).andExpect { status { isBadRequest() } }

            assertEquals("PENDING_APPROVAL", membershipStatus(membershipId))
        }

        @Test
        fun `a remark on tenant approval becomes the organisation status reason and is audited`() {
            val maker = seedUser("rm-maker").also { fixture.grantPlatformSuperAdmin(it) }
            val checker = seedUser("rm-checker").also { fixture.grantPlatformSuperAdmin(it) }
            val tenantId = submittedTenant(maker, "admin-${shortId()}@tenant.test")
            val path = "${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve"
            val key = uuidV7().toString()
            val body = remarkBody("KYC pack reviewed.")

            val first =
                platformPostWithKey(path, checker, key, body)
                    .andExpect {
                        status { isAccepted() }
                        jsonPath("$.status") { value("ACTIVE") }
                    }.andReturn()
                    .response.contentAsString
            val replay =
                platformPostWithKey(path, checker, key, body)
                    .andExpect { status { isAccepted() } }
                    .andReturn()
                    .response.contentAsString

            assertEquals(first, replay)
            assertEquals("KYC pack reviewed.", tenantStatusReason(tenantId))
            assertEquals(
                listOf("KYC pack reviewed."),
                auditReasons(tenantId, "organisation.activate", tenantId),
            )
        }

        @Test
        fun `tenant approval rejects an over-long remark and works without one`() {
            val maker = seedUser("rl-maker").also { fixture.grantPlatformSuperAdmin(it) }
            val checker = seedUser("rl-checker").also { fixture.grantPlatformSuperAdmin(it) }
            val tenantId = submittedTenant(maker, "admin-${shortId()}@tenant.test")
            val path = "${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve"

            platformPost(path, checker, remarkBody("x".repeat(501)))
                .andExpect { status { isBadRequest() } }
            assertEquals("PENDING_APPROVAL", tenantStatus(tenantId))

            platformPost(path, checker).andExpect { status { isAccepted() } }
            assertEquals("ACTIVE", tenantStatus(tenantId))
            assertEquals(null, tenantStatusReason(tenantId))
        }

        private fun remarkBody(reason: String) =
            apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason))

        private fun platformPostWithKey(
            path: String,
            actor: UUID,
            key: String,
            body: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token(actor, PlatformOrganisation.ID, COARSE_AUTHORITIES)))
            }

        private fun tenantPostWithKey(
            path: String,
            actor: UUID,
            s: Scenario,
            key: String,
            body: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, key)
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(tenantToken(actor, s.tenantId)))
            }

        private fun membershipStatusReason(membershipId: UUID): String? =
            dsl
                .fetchOne(
                    "SELECT status_reason FROM user_organisation_membership WHERE id = ?",
                    membershipId,
                )!!
                .get(0, String::class.java)

        private fun tenantStatusReason(tenantId: UUID): String? =
            dsl
                .fetchOne("SELECT status_reason FROM organisation WHERE id = ?", tenantId)!!
                .get(0, String::class.java)

        private fun auditReasons(
            tenantId: UUID,
            action: String,
            entityId: UUID,
        ): List<String?> =
            dsl
                .fetch(
                    "SELECT reason FROM audit_event " +
                        "WHERE organisation_id = ? AND action = ? AND entity_id = ? " +
                        "ORDER BY event_time",
                    tenantId,
                    action,
                    entityId,
                ).map { it.get(0, String::class.java) }

        private fun provisionTenantThroughApi(): UUID {
            val maker = seedUser("tenant-maker").also { fixture.grantPlatformSuperAdmin(it) }
            val checker = seedUser("tenant-checker").also { fixture.grantPlatformSuperAdmin(it) }
            val tenantId = submittedTenant(maker, "admin-${shortId()}@tenant.test")
            platformPost("${ApiPaths.PLATFORM_TENANTS}/$tenantId/approve", checker)
                .andExpect {
                    status { isAccepted() }
                    jsonPath("$.status") { value("ACTIVE") }
                }
            return tenantId
        }

        /** Creates and submits a tenant draft through the API, naming [adminEmail] as its admin. */
        private fun submittedTenant(
            maker: UUID,
            adminEmail: String,
        ): UUID {
            val code = "first-${shortId()}"
            val tenantId =
                idOf(
                    platformPost(
                        ApiPaths.PLATFORM_TENANTS,
                        maker,
                        apiJsonCodec.mapper.writeValueAsString(
                            CreateTenantDraftRequest(
                                tenantCode = code,
                                displayName = "First Approvals Tenant",
                                legalName = "First Approvals Tenant Ltd",
                                registrationNumber = "REG-${shortId()}",
                                countryCode = "KE",
                                baseCurrencyCode = "KES",
                                timezone = "Africa/Nairobi",
                                admin =
                                    InitialAdminDto(
                                        email = adminEmail,
                                        username = "admin-$code",
                                        displayName = "Initial Admin",
                                        phoneE164 = "+254700000000",
                                        sendApplicationInvite = false,
                                    ),
                            ),
                        ),
                    ).andExpect { status { isCreated() } }
                        .andReturn()
                        .response.contentAsString,
                    "organisation_id",
                )
            platformPost("${ApiPaths.PLATFORM_TENANTS}/$tenantId/submit", maker)
                .andExpect { status { isOk() } }
            return tenantId
        }

        private fun tenantStatus(tenantId: UUID): String =
            dsl
                .select(ORGANISATION.STATUS)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(tenantId))
                .fetchOne(ORGANISATION.STATUS)!!

        private fun bootstrapStatus(tenantId: UUID): String =
            dsl
                .fetchOne(
                    "SELECT status FROM organisation_initial_administrator_bootstrap " +
                        "WHERE organisation_id = ?",
                    tenantId,
                )!!
                .get(0, String::class.java)

        private fun outboxRecordsFor(tenantId: String): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM outbox_record WHERE payload LIKE ?",
                    "%$tenantId%",
                )!!
                .get(0, Int::class.java)

        private fun branchBody(code: String) =
            apiJsonCodec.mapper.writeValueAsString(
                CreateBranchRequest(code, "Branch $code", "OPERATIONAL", null, "Africa/Nairobi"),
            )

        private fun inviteBody(
            email: String,
            branchId: UUID,
            tenantId: UUID,
        ) = apiJsonCodec.mapper.writeValueAsString(
            InviteUserRequest(
                email = email,
                username = "user-${shortId()}",
                displayName = "Second User",
                membershipType = MembershipType.STAFF,
                primaryBranchId = branchId,
                branchAssignments =
                    listOf(BranchAssignmentEntryDto(branchId, BranchAssignmentType.HOME)),
                roleAssignments =
                    listOf(
                        RoleAssignmentEntryDto(
                            tenantAdminRoleId(tenantId),
                            RoleAssignmentScopeType.TENANT,
                        ),
                    ),
                sendKeycloakInvite = false,
                sendApplicationInvite = false,
            ),
        )

        private fun platformPost(
            path: String,
            actor: UUID,
            body: String? = null,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = it
                }
                with(authentication(token(actor, PlatformOrganisation.ID, COARSE_AUTHORITIES)))
            }

        /** A platform call whose token carries only [authority], as a narrow role would. */
        private fun narrowPost(
            path: String,
            actor: UUID,
            authority: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                with(authentication(token(actor, PlatformOrganisation.ID, setOf(authority))))
            }

        private fun branchTransitionActor(
            branchId: UUID,
            transition: String,
        ): UUID =
            dsl
                .fetchOne(
                    "SELECT created_by FROM branch_transition_log " +
                        "WHERE branch_id = ? AND transition_name = ?",
                    branchId,
                    transition,
                )!!
                .get(0, UUID::class.java)

        private fun tenantPost(
            path: String,
            actor: UUID,
            tenantId: UUID,
            body: String? = null,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                body?.let {
                    contentType = MediaType.APPLICATION_JSON
                    content = it
                }
                with(authentication(tenantToken(actor, tenantId)))
            }

        private fun platformAuditActors(
            tenantId: UUID,
            action: String,
            resourceId: UUID,
            platformActor: UUID,
            outcome: String = "SUCCESS",
        ): List<String> {
            val body =
                mockMvc
                    .get("${ApiPaths.PLATFORM_TENANTS}/$tenantId/audit-events") {
                        param("action", action)
                        param("outcome", outcome)
                        with(
                            authentication(
                                token(platformActor, PlatformOrganisation.ID, COARSE_AUTHORITIES),
                            ),
                        )
                    }.andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            val items = apiJsonCodec.mapper.readTree(body).get("items")
            return (0 until items.size())
                .map { items.get(it) }
                .filter { it.get("resource_id").asString() == resourceId.toString() }
                .map { it.get("actor_id").asString() }
        }

        private fun membershipBody(
            s: Scenario,
            membershipId: UUID,
        ): String =
            mockMvc
                .get("${ApiPaths.MEMBERSHIPS}/$membershipId") {
                    with(authentication(tenantToken(s.tenantAdmin, s.tenantId)))
                }.andExpect { status { isOk() } }
                .andReturn()
                .response.contentAsString

        private fun idOf(
            body: String,
            field: String,
        ): UUID =
            UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(body)
                    .get(field)
                    .asString(),
            )

        private fun outboxRecords(
            target: String,
            aggregateId: String,
        ): Int =
            dsl
                .fetchOne(
                    "SELECT COUNT(*) FROM outbox_record WHERE payload LIKE ? AND payload LIKE ?",
                    "%$target%",
                    "%$aggregateId%",
                )!!
                .get(0, Int::class.java)

        private fun branchStatus(branchId: UUID): String =
            dsl
                .fetchOne("SELECT status FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun membershipStatus(membershipId: UUID): String =
            dsl
                .fetchOne(
                    "SELECT membership_status FROM user_organisation_membership WHERE id = ?",
                    membershipId,
                )!!
                .get(0, String::class.java)

        private fun setTenantStatus(
            tenantId: UUID,
            status: String,
        ) {
            dsl
                .update(ORGANISATION)
                .set(ORGANISATION.STATUS, status)
                .where(ORGANISATION.ID.eq(tenantId))
                .execute()
        }

        private fun tenantAdminRoleId(tenantId: UUID): UUID =
            requireNotNull(
                dsl
                    .select(ROLE.ID)
                    .from(ROLE)
                    .where(ROLE.ORGANISATION_ID.eq(tenantId))
                    .and(ROLE.ROLE_CODE.eq("TENANT_ADMIN"))
                    .fetchOne(ROLE.ID),
            )

        private fun seedUser(label: String): UUID {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "$label-$id")
                .set(USER_ACCOUNT.EMAIL, "$label-$id@example.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }

        /** An ACTIVE account with a Keycloak identity, so approval activates it at once. */
        private fun seedIdentifiedUser(email: String) {
            val id = uuidV7()
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, id)
                .set(USER_ACCOUNT.USERNAME, "ident-$id")
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.DISPLAY_NAME, "Identified User")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            linkIdentity(id)
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

        private fun shortId(): String = uuidV7().toString().takeLast(8)

        private fun tenantToken(
            userId: UUID,
            tenantId: UUID,
        ) = token(userId, tenantId, COARSE_AUTHORITIES)

        private fun token(
            userId: UUID,
            organisationId: UUID,
            permissions: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "it-user-$userId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "it@example.test",
                fullName = "Integration User",
                permissions = permissions,
            ),
        )

        private companion object {
            /**
             * Every coarse authority any route here needs. The coarse gate only filters; the
             * application layer re-checks the caller's real permissions in the database.
             */
            val COARSE_AUTHORITIES =
                setOf(
                    "tenant.create",
                    "tenant.submit_for_approval",
                    "tenant.approve",
                    "tenant.view",
                    "branch.create",
                    "branch.approve",
                    "branch.view",
                    "user.invite",
                    "user.approve",
                    "user.view",
                    "membership.view",
                    "audit.view",
                )
        }
    }
