package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationSettingsStore
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Full-stack proof of the pending-approval counts (#169), the badge answer that is not an inbox
 * (ADR 0038): `GET /tenant/approvals/pending-count` returns one count per resource the caller may
 * approve with its existing approve code, omits the key of a resource it may not, excludes what
 * the caller made itself, and never counts another tenant; `GET /platform/approvals/pending-count`
 * counts pending tenants for a `tenant.approve` holder in the platform organisation only.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Suppress("TooManyFunctions", "LargeClass")
class ApprovalCountsIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
        private val userProvisioningService: UserProvisioningService,
        private val settings: OrganisationSettingsStore,
    ) {
        private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        private val admin = seedUser("admin")
        private val organisationId = fixture.createActiveOrganisation("approval-counts", admin)

        @Test
        fun `a user approve holder sees the memberships count and no branches key`() {
            invite(admin)
            invite(admin)
            val approver = seedUser("user-approver")
            fixture.grantTenantPermissionsWithViews(organisationId, approver, "user.approve")

            tenantCounts(approver, "user.approve").andExpect {
                status { isOk() }
                jsonPath("$.memberships") { value(2) }
                jsonPath("$.branches") { doesNotExist() }
            }
        }

        @Test
        fun `a branch approve holder sees the branches count and no memberships key`() {
            invite(admin)
            val branchId = pendingBranch(admin)
            val approver = seedUser("branch-approver")
            fixture.grantTenantPermissionsWithViews(organisationId, approver, "branch.approve")

            tenantCounts(approver, "branch.approve").andExpect {
                status { isOk() }
                jsonPath("$.branches") { value(1) }
                jsonPath("$.memberships") { doesNotExist() }
            }
            assertEquals("PENDING_APPROVAL", branchStatus(branchId))
        }

        @Test
        fun `a holder of both codes sees both counts`() {
            val tenantAdmin = seedUser("both-admin")
            val tenant = fixture.createActiveOrganisation("counts-both", tenantAdmin)
            invite(tenantAdmin, tenant)
            pendingBranch(tenantAdmin, tenant)
            val approver = seedUser("both-approver")
            fixture.grantTenantPermissionsWithViews(
                tenant,
                approver,
                "user.approve",
                "branch.approve",
            )

            tenantCountsIn(tenant, approver, "user.approve", "branch.approve").andExpect {
                status { isOk() }
                jsonPath("$.memberships") { value(1) }
                jsonPath("$.branches") { value(1) }
            }
        }

        @Test
        fun `a caller holding neither approve code is refused`() {
            val viewer = seedUser("viewer")
            fixture.grantTenantPermissionsExactly(
                organisationId,
                viewer,
                "membership.view",
                "branch.view",
            )

            // Refused by name, the first resource's approve code first (ADR 0030).
            tenantCounts(viewer, "user.approve", "branch.approve").andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("forbidden") }
                jsonPath("$.detail") { value("Missing permission: user.approve.") }
            }
        }

        @Test
        fun `an approve code without its view is refused by name`() {
            val noView = seedUser("approve-no-view")
            fixture.grantTenantPermissionsExactly(organisationId, noView, "user.approve")

            tenantCounts(noView, "user.approve").andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: membership.view.") }
            }
        }

        @Test
        fun `a branch scoped approve grant sees no branches key`() {
            val tenantAdmin = seedUser("scoped-admin")
            val tenant = fixture.createActiveOrganisation("counts-scoped", tenantAdmin)
            pendingBranch(tenantAdmin, tenant)
            val headOffice = headOfficeId(tenant)
            val approver = seedUser("scoped-approver")
            fixture.grantTenantPermissionsWithViews(tenant, approver, "user.approve")
            fixture.grantBranchPermissionsWithViews(tenant, headOffice, approver, "branch.approve")

            // Only a tenant-wide holder can approve a pending branch: a branch-scope grant counts
            // only while its branch is ACTIVE, and a PENDING_APPROVAL branch has none.
            tenantCountsIn(tenant, approver, "user.approve", "branch.approve").andExpect {
                status { isOk() }
                jsonPath("$.memberships") { value(0) }
                jsonPath("$.branches") { doesNotExist() }
            }
        }

        @Test
        fun `the caller's own pending items are excluded`() {
            val tenant = fixture.createActiveOrganisation("counts-own", seedUser("own-admin"))
            val maker = seedUser("maker")
            val checker = seedUser("checker")
            fixture.grantTenantAdmin(tenant, maker)
            fixture.grantTenantAdmin(tenant, checker)
            invite(maker, tenant)
            invite(maker, tenant)
            invite(checker, tenant)
            pendingBranch(maker, tenant)
            pendingBranch(checker, tenant)
            pendingBranch(checker, tenant)

            tenantCountsIn(tenant, maker, "user.approve", "branch.approve").andExpect {
                jsonPath("$.memberships") { value(1) }
                jsonPath("$.branches") { value(2) }
            }
            tenantCountsIn(tenant, checker, "user.approve", "branch.approve").andExpect {
                jsonPath("$.memberships") { value(2) }
                jsonPath("$.branches") { value(1) }
            }
        }

        @Test
        fun `a tenant that switched maker-checker off counts its own items too`() {
            val tenant = fixture.createActiveOrganisation("counts-off", seedUser("off-admin"))
            val maker = seedUser("off-maker")
            fixture.grantTenantAdmin(tenant, maker)
            invite(maker, tenant)
            pendingBranch(maker, tenant)
            switchOff(tenant, TenantSettingCatalog.MAKER_CHECKER_USER_INVITES_KEY)
            switchOff(tenant, TenantSettingCatalog.MAKER_CHECKER_BRANCH_CREATION_KEY)

            // The approval would be allowed (and audited HIGH), so the count must include it.
            tenantCountsIn(tenant, maker, "user.approve", "branch.approve").andExpect {
                jsonPath("$.memberships") { value(1) }
                jsonPath("$.branches") { value(1) }
            }
        }

        @Test
        fun `another tenant's pending items are never counted`() {
            val mine = fixture.createActiveOrganisation("counts-mine", seedUser("mine-admin"))
            val theirAdmin = seedUser("theirs-admin")
            val theirs = fixture.createActiveOrganisation("counts-theirs", theirAdmin)
            invite(theirAdmin, theirs)
            invite(theirAdmin, theirs)
            pendingBranch(theirAdmin, theirs)
            val approver = seedUser("mine-approver")
            fixture.grantTenantPermissionsWithViews(
                mine,
                approver,
                "user.approve",
                "branch.approve",
            )

            tenantCountsIn(mine, approver, "user.approve", "branch.approve").andExpect {
                jsonPath("$.memberships") { value(0) }
                jsonPath("$.branches") { value(0) }
            }
        }

        @Test
        fun `the platform route counts pending tenants for a tenant approve holder`() {
            val makerA = fixture.createPlatformOperator("count-maker-a")
            val makerB = fixture.createPlatformOperator("count-maker-b")
            val before = platformCount(makerA)
            val beforeB = platformCount(makerB)
            pendingTenant(makerA)
            pendingTenant(makerB)

            // Each operator's own pending tenant is excluded from its own count.
            assertEquals(before + 1, platformCount(makerA))
            assertEquals(beforeB + 1, platformCount(makerB))
            // A third operator sees both.
            val third = fixture.createPlatformOperator("count-third")
            assertEquals(before + 2, platformCount(third))
        }

        @Test
        fun `a platform caller without tenant approve is refused by name`() {
            val operator = seedUser("platform-viewer")
            fixture.grantPlatformPermissionsExactly(operator, "tenant.view")

            platformCounts(operator).andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("Missing permission: tenant.approve.") }
            }
        }

        @Test
        fun `a tenant administrator is refused on the platform route`() {
            platformCountsAs(tenantToken(admin, organisationId, "tenant.approve")).andExpect {
                status { isForbidden() }
            }
        }

        private fun platformCount(actor: UUID): Int {
            val body =
                platformCounts(actor)
                    .andExpect { status { isOk() } }
                    .andReturn()
                    .response.contentAsString
            return apiJsonCodec.mapper
                .readTree(body)
                .get("tenants")
                .asInt()
        }

        private fun pendingTenant(maker: UUID): UUID =
            withRequestContext {
                val id =
                    organisationProvisioningService
                        .createDraft(
                            CreateOrganisationDraftCommand(
                                tenantCode = "pending-${uuidV7()}",
                                displayName = "Pending Organisation",
                                legalName = "Pending Organisation Limited",
                                registrationNumber = "PEND-${uuidV7()}",
                                countryCode = "KE",
                                baseCurrencyCode = "KES",
                                timezone = "Africa/Nairobi",
                                requestedBy = maker,
                            ),
                        ).organisationId
                organisationProvisioningService.submitForApproval(
                    SubmitOrganisationForApprovalCommand(id, actorId = maker),
                )
                id
            }

        private fun invite(
            inviter: UUID,
            tenant: UUID = organisationId,
        ) {
            val suffix = uuidV7().toString()
            withRequestContext {
                userProvisioningService.inviteUser(
                    InviteUserCommand(
                        organisationId = tenant,
                        email = "invitee-$suffix@approval-counts.test",
                        username = "invitee-$suffix",
                        displayName = "Invitee",
                        membershipType = MembershipType.AUDITOR,
                        branchAssignments = emptyList(),
                        roleAssignments =
                            listOf(
                                RoleAssignmentRequest(
                                    roleIdOf(tenant, "TENANT_ADMIN"),
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

        private fun pendingBranch(
            maker: UUID,
            tenant: UUID = organisationId,
        ): UUID {
            val body =
                apiJsonCodec.mapper.writeValueAsString(
                    CreateBranchRequest(
                        branchCode = "BR-${uuidV7().toString().takeLast(8).uppercase()}",
                        branchName = "Counted Branch",
                        branchType = "OPERATIONAL",
                        timezone = "Africa/Nairobi",
                    ),
                )
            val created =
                post(ApiPaths.BRANCHES, tenantToken(maker, tenant, "branch.create"), body)
                    .andExpect { status { isCreated() } }
                    .andReturn()
                    .response.contentAsString
            val branchId =
                UUID.fromString(
                    apiJsonCodec.mapper
                        .readTree(created)
                        .get("branch_id")
                        .asString(),
                )
            post(
                "${ApiPaths.BRANCHES}/$branchId/submit",
                tenantToken(maker, tenant, "branch.create"),
                "{}",
            ).andExpect { status { isOk() } }
            return branchId
        }

        private fun switchOff(
            tenant: UUID,
            key: String,
        ) {
            settings.upsertSetting(tenant, key, "false", "BOOLEAN", false, admin)
        }

        private fun tenantCounts(
            actor: UUID,
            vararg authorities: String,
        ): ResultActionsDsl = tenantCountsIn(organisationId, actor, *authorities)

        private fun tenantCountsIn(
            tenant: UUID,
            actor: UUID,
            vararg authorities: String,
        ): ResultActionsDsl =
            mockMvc.get("${ApiPaths.TENANT}/approvals/pending-count") {
                with(authentication(token(actor, tenant, authorities.toSet())))
            }

        private fun platformCounts(actor: UUID): ResultActionsDsl =
            platformCountsAs(token(actor, PlatformOrganisation.ID, setOf("tenant.approve")))

        private fun platformCountsAs(token: AppPrincipalAuthenticationToken): ResultActionsDsl =
            mockMvc.get("${ApiPaths.PLATFORM}/approvals/pending-count") {
                with(authentication(token))
            }

        private fun post(
            path: String,
            token: AppPrincipalAuthenticationToken,
            body: String,
        ): ResultActionsDsl =
            mockMvc.post(path) {
                header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
                contentType = MediaType.APPLICATION_JSON
                content = body
                with(authentication(token))
            }

        private fun branchStatus(branchId: UUID): String =
            dsl
                .fetchOne("SELECT status::text FROM branch WHERE id = ?", branchId)!!
                .get(0, String::class.java)

        private fun headOfficeId(tenant: UUID): UUID =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(tenant))
                    .and(BRANCH.BRANCH_TYPE.eq("HEAD_OFFICE"))
                    .and(BRANCH.STATUS.eq("ACTIVE"))
                    .fetchOne(BRANCH.ID),
            )

        private fun roleIdOf(
            tenant: UUID,
            code: String,
        ): UUID =
            dsl
                .fetchOne(
                    "SELECT id FROM role WHERE organisation_id = ? AND role_code = ?",
                    tenant,
                    code,
                )!!
                .get(0, UUID::class.java)

        private fun tenantToken(
            userId: UUID,
            tenant: UUID,
            authority: String,
        ) = token(userId, tenant, setOf(authority))

        private fun token(
            userId: UUID,
            tenant: UUID,
            authorities: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
                keycloakSubject = "user-$userId",
                organisationId = tenant,
                membershipId = uuidV7(),
                branchId = null,
                email = "user@approval-counts.test",
                fullName = "Approval Counts User",
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
                .set(USER_ACCOUNT.EMAIL, "$label-$id@approval-counts.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, label)
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
            return id
        }
    }
