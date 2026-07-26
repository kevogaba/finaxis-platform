package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignPermissionRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.CreateRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.InviteUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.RoleAssignmentEntryDto
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningGateway
import com.finaxis.platform.lifecycle.application.port.outbound.KeycloakUserRef
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class FoundationIamWebIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
        private val dsl: DSLContext,
        private val organisationProvisioningService: OrganisationProvisioningService,
    ) {
        @MockitoBean
        private lateinit var identityProvisioningGateway: IdentityProvisioningGateway

        @Test
        @Suppress("LongMethod")
        fun `full tenant IAM REST lifecycle uses database backed authorization`() {
            whenever(identityProvisioningGateway.findOrCreateUser(any()))
                .thenReturn(KeycloakUserRef(subject = UUID.randomUUID().toString(), created = true))

            val makerId = uuidV7()
            val checkerId = uuidV7()
            val runId =
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(8)
                    .lowercase()
            seedUserAccount(makerId, "iam-maker-$runId", "maker-$runId@finaxis.test")
            seedUserAccount(checkerId, "iam-checker-$runId", "checker-$runId@finaxis.test")

            val organisationFixture =
                TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
            val organisationId =
                organisationFixture.createActiveOrganisation(
                    "iam-web-$runId",
                    makerId,
                )
            organisationFixture.grantTenantAdmin(organisationId, checkerId)
            organisationFixture.grantPlatformSuperAdmin(makerId)
            val tenantAuditorRoleId =
                requireNotNull(
                    dsl
                        .select(ROLE.ID)
                        .from(ROLE)
                        .where(ROLE.ORGANISATION_ID.eq(organisationId))
                        .and(ROLE.ROLE_CODE.eq("TENANT_AUDITOR"))
                        .fetchOne(ROLE.ID),
                ) { "TENANT_AUDITOR role was not provisioned for the test organisation." }

            // 1. Create and list a tenant-managed role.
            val roleResult =
                mockMvc
                    .post(ApiPaths.ROLES) {
                        header(
                            IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                            UUID.randomUUID().toString(),
                        )
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                CreateRoleRequest("WEB_IAM", "Web IAM Role", "End-to-end IAM role"),
                            )
                        with(
                            authentication(
                                tenantToken(makerId, organisationId, setOf("role.create")),
                            ),
                        )
                    }.andExpect {
                        status { isCreated() }
                        header { exists("Location") }
                        jsonPath("$.role_code") { value("WEB_IAM") }
                    }.andReturn()
            val roleId = responseUuid(roleResult.response.contentAsString, "id")

            mockMvc
                .get(ApiPaths.ROLES) {
                    param("q", "WEB_IAM")
                    with(authentication(tenantToken(makerId, organisationId, setOf("role.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(roleId.toString()) }
                    jsonPath("$.items[0].role_code") { value("WEB_IAM") }
                }

            // 2. Grant a catalogue permission, then verify the real permission catalogue route.
            mockMvc
                .post("${ApiPaths.ROLES}/$roleId/permissions") {
                    header(
                        IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                        UUID.randomUUID().toString(),
                    )
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AssignPermissionRequest("permission.view"),
                        )
                    with(
                        authentication(
                            tenantToken(makerId, organisationId, setOf("role.assign_permission")),
                        ),
                    )
                }.andExpect {
                    status { isCreated() }
                    header { exists("Location") }
                    jsonPath("$.permission_code") { value("permission.view") }
                }

            mockMvc
                .get(ApiPaths.PERMISSIONS) {
                    param("q", "permission.view")
                    with(
                        authentication(
                            tenantToken(makerId, organisationId, setOf("permission.view")),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].permission_code") { value("permission.view") }
                }

            // 3. Invite a user and assign the newly created role through its dedicated route.
            val inviteResult =
                mockMvc
                    .post(ApiPaths.TENANT_USERS) {
                        header(
                            IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                            UUID.randomUUID().toString(),
                        )
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                InviteUserRequest(
                                    email = "iam-user-$runId@finaxis.test",
                                    username = "iam-user-$runId",
                                    displayName = "IAM Web User",
                                    membershipType = MembershipType.AUDITOR,
                                    roleAssignments =
                                        listOf(
                                            RoleAssignmentEntryDto(
                                                tenantAuditorRoleId,
                                                RoleAssignmentScopeType.TENANT,
                                            ),
                                        ),
                                    sendKeycloakInvite = false,
                                    sendApplicationInvite = false,
                                ),
                            )
                        with(
                            authentication(
                                tenantToken(makerId, organisationId, setOf("user.invite")),
                            ),
                        )
                    }.andExpect {
                        status { isCreated() }
                        header { exists("Location") }
                        jsonPath("$.membership_status") { value("PENDING_APPROVAL") }
                    }.andReturn()
            val invitedUserId = responseUuid(inviteResult.response.contentAsString, "user_id")
            val membershipId = responseUuid(inviteResult.response.contentAsString, "membership_id")

            mockMvc
                .post(ApiPaths.ROLE_ASSIGNMENTS) {
                    header(
                        IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                        UUID.randomUUID().toString(),
                    )
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AssignRoleRequest(
                                invitedUserId,
                                roleId,
                                RoleAssignmentScopeType.TENANT,
                            ),
                        )
                    with(
                        authentication(
                            tenantToken(makerId, organisationId, setOf("user.assign_role")),
                        ),
                    )
                }.andExpect {
                    status { isCreated() }
                    header { exists("Location") }
                    jsonPath("$.user_id") { value(invitedUserId.toString()) }
                    jsonPath("$.role_id") { value(roleId.toString()) }
                    jsonPath("$.scope_type") { value("TENANT") }
                }

            mockMvc
                .get(ApiPaths.ROLE_ASSIGNMENTS) {
                    param("user_id", invitedUserId.toString())
                    with(
                        authentication(
                            tenantToken(makerId, organisationId, setOf("role_assignment.view")),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].user_id") { value(invitedUserId.toString()) }
                    jsonPath("$.items[0].role_id") { value(roleId.toString()) }
                }

            // 4. A distinct checker approves the maker's invitation through the membership route.
            mockMvc
                .post("${ApiPaths.MEMBERSHIPS}/$membershipId/activate") {
                    header(
                        IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER,
                        UUID.randomUUID().toString(),
                    )
                    with(
                        authentication(
                            tenantToken(checkerId, organisationId, setOf("user.approve")),
                        ),
                    )
                }.andExpect {
                    status { isAccepted() }
                    jsonPath("$.id") { value(membershipId.toString()) }
                    jsonPath("$.membership_status") { value("PENDING_APPROVAL") }
                }

            mockMvc
                .get(ApiPaths.TENANT_USERS) {
                    param("q", "iam-user-$runId")
                    with(authentication(tenantToken(makerId, organisationId, setOf("user.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(invitedUserId.toString()) }
                }

            mockMvc
                .get("${ApiPaths.TENANT_USERS}/$invitedUserId") {
                    with(authentication(tenantToken(makerId, organisationId, setOf("user.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(invitedUserId.toString()) }
                }

            mockMvc
                .get(ApiPaths.MEMBERSHIPS) {
                    param("q", "iam-user-$runId")
                    with(
                        authentication(
                            tenantToken(makerId, organisationId, setOf("membership.view")),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(membershipId.toString()) }
                }

            // 5. The platform-nested read is authorized by the maker's real platform membership.
            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$organisationId/users") {
                    param("q", "iam-user-$runId")
                    with(authentication(platformToken(makerId, setOf("user.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(invitedUserId.toString()) }
                }
        }

        private fun seedUserAccount(
            userId: UUID,
            username: String,
            email: String,
        ) {
            val now = OffsetDateTime.now()
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.ID, userId)
                .set(USER_ACCOUNT.USERNAME, username)
                .set(USER_ACCOUNT.EMAIL, email)
                .set(USER_ACCOUNT.DISPLAY_NAME, username)
                .set(USER_ACCOUNT.STATUS, "DRAFT")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
                .execute()
        }

        private fun responseUuid(
            responseBody: String,
            fieldName: String,
        ): UUID =
            UUID.fromString(
                apiJsonCodec.mapper
                    .readTree(responseBody)
                    .get(fieldName)
                    .asString(),
            )

        private fun platformToken(
            userId: UUID,
            permissions: Set<String>,
        ): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "platform-user-$userId",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    email = "platform@finaxis.test",
                    fullName = "Platform Administrator",
                    permissions = permissions,
                ),
            )

        private fun tenantToken(
            userId: UUID,
            organisationId: UUID,
            permissions: Set<String>,
        ): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "tenant-user-$userId",
                    organisationId = organisationId,
                    membershipId = uuidV7(),
                    email = "tenant@finaxis.test",
                    fullName = "Tenant Administrator",
                    permissions = permissions,
                ),
            )
    }
