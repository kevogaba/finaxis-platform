package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.idempotency.IdempotencyProperties
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.application.ActingScope
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.BranchDraftResult
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.ReturnBranchCommand
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.UserApprovalResult
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.query.BranchDetail
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.application.query.LifecycleIamReadService
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipDetail
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

private const val RETURN_BODY = "{\"reason\":\"Branch code has a typo.\"}"

/**
 * Web contract of the platform-checker routes (#153): coarse authority gate, platform context
 * only, the application services receive the PLATFORM scope and the path tenant, and the
 * application error mapping (403/404/409) reaches the wire.
 */
@WebMvcTest(
    controllers =
        [PlatformTenantBranchController::class, PlatformTenantMembershipController::class],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    PlatformTenantCheckerControllerTests.TestSecurityConfiguration::class,
    PlatformTenantBranchController::class,
    PlatformTenantMembershipController::class,
)
class PlatformTenantCheckerControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
    ) {
        @org.springframework.boot.test.context.TestConfiguration
        @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
        class TestSecurityConfiguration {
            @org.springframework.context.annotation.Bean
            fun testSecurityFilterChain(
                http: org.springframework.security.config.annotation.web.builders.HttpSecurity,
            ): org.springframework.security.web.SecurityFilterChain =
                http
                    .csrf { it.disable() }
                    .authorizeHttpRequests { it.anyRequest().authenticated() }
                    .exceptionHandling {
                        it.authenticationEntryPoint(
                            org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                org.springframework.http.HttpStatus.UNAUTHORIZED,
                            ),
                        )
                    }.build()

            @org.springframework.context.annotation.Bean
            fun idempotencyKeyFilter(
                problemFactory: ApiProblemFactory,
                jsonCodec: ApiJsonCodec,
            ): org.springframework.boot.web.servlet.FilterRegistrationBean<IdempotencyKeyFilter> =
                org.springframework.boot.web.servlet.FilterRegistrationBean(
                    IdempotencyKeyFilter(
                        IdempotencyProperties(),
                        ApiProblemWriter(problemFactory, jsonCodec),
                    ),
                )
        }

        @MockitoBean
        private lateinit var branchProvisioningService: BranchProvisioningService

        @MockitoBean
        private lateinit var foundationQueryService: FoundationQueryService

        @MockitoBean
        private lateinit var userProvisioningService: UserProvisioningService

        @MockitoBean
        private lateinit var lifecycleIamReadService: LifecycleIamReadService

        private val tenantId = uuidV7()
        private val branchId = uuidV7()
        private val membershipId = uuidV7()
        private val actorId = uuidV7()

        @Test
        fun `platform branch creation reaches the service in the platform scope`() {
            whenever(branchProvisioningService.createDraft(any()))
                .thenReturn(BranchDraftResult(branchId, BranchLifecycleState.DRAFT))

            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            CreateBranchRequest("HQ-01", "Head Office", "HEAD_OFFICE", null, "UTC"),
                        )
                    with(authentication(platformToken(setOf("branch.create"))))
                }.andExpect {
                    status { isCreated() }
                    jsonPath("$.branch_id") { value(branchId.toString()) }
                }

            verify(branchProvisioningService).createDraft(
                argThat<CreateBranchCommand> {
                    organisationId == tenantId &&
                        requestedBy == actorId &&
                        scope == ActingScope.PLATFORM
                },
            )
        }

        @Test
        fun `platform branch submit and activate pass the path tenant and the platform scope`() {
            stubBranchDetail("PENDING_APPROVAL")

            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/submit") {
                    with(authentication(platformToken(setOf("branch.create"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                }
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/activate") {
                    with(authentication(platformToken(setOf("branch.activate"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                }

            verify(branchProvisioningService).submitForApproval(
                argThat<SubmitBranchForApprovalCommand> {
                    organisationId == tenantId &&
                        this.branchId == this@PlatformTenantCheckerControllerTests.branchId &&
                        actorId == this@PlatformTenantCheckerControllerTests.actorId &&
                        scope == ActingScope.PLATFORM
                },
            )
            verify(branchProvisioningService).activate(
                argThat<ActivateBranchCommand> {
                    organisationId == tenantId &&
                        this.branchId == this@PlatformTenantCheckerControllerTests.branchId &&
                        actorId == this@PlatformTenantCheckerControllerTests.actorId &&
                        scope == ActingScope.PLATFORM
                },
            )
            // The response is the already-authorised projection, never the permission-gated read
            // (branch.view), which would roll the mutation back for a role holding only the
            // advertised permission.
            verify(foundationQueryService, times(2))
                .getBranchAfterAuthorizedMutation(eq(tenantId), eq(branchId))
            verify(foundationQueryService, never()).getBranch(any(), any(), any())
        }

        @Test
        fun `an over-long reason on platform submit or activate is a 400 before the service`() {
            val tooLong =
                apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to "x".repeat(501)))
            branchActions.forEach { (action, authority) ->
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/$action") {
                        contentType = MediaType.APPLICATION_JSON
                        content = tooLong
                        with(authentication(platformToken(setOf(authority))))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }

            verify(branchProvisioningService, never()).submitForApproval(any())
            verify(branchProvisioningService, never()).activate(any())
        }

        @Test
        fun `a reason within the limit and an absent body both reach submit and activate`() {
            stubBranchDetail("PENDING_APPROVAL")
            val withReason =
                apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to "x".repeat(500)))
            branchActions.forEach { (action, authority) ->
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/$action") {
                        contentType = MediaType.APPLICATION_JSON
                        content = withReason
                        with(authentication(platformToken(setOf(authority))))
                    }.andExpect { status { isOk() } }
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/$action") {
                        with(authentication(platformToken(setOf(authority))))
                    }.andExpect { status { isOk() } }
            }

            verify(branchProvisioningService, times(2)).submitForApproval(any())
            verify(branchProvisioningService, times(2)).activate(any())
            verify(branchProvisioningService).submitForApproval(
                argThat<SubmitBranchForApprovalCommand> { reason == "x".repeat(500) },
            )
        }

        @Test
        fun `platform membership activation approves in the platform scope`() {
            whenever(userProvisioningService.approveUser(any())).thenReturn(approval(false))
            stubMembershipDetail("ACTIVE")

            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships/$membershipId/activate") {
                    with(authentication(platformToken(setOf("user.approve"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(membershipId.toString()) }
                    jsonPath("$.membership_status") { value("ACTIVE") }
                }

            verify(lifecycleIamReadService, never()).getMembership(any(), any(), any())
            verify(userProvisioningService).approveUser(
                argThat<ApproveUserCommand> {
                    organisationId == tenantId &&
                        membershipId == this@PlatformTenantCheckerControllerTests.membershipId &&
                        approvedBy == actorId &&
                        scope == ActingScope.PLATFORM
                },
            )
        }

        @Test
        fun `platform membership activation passes an optional decision remark`() {
            whenever(userProvisioningService.approveUser(any())).thenReturn(approval(false))
            stubMembershipDetail("ACTIVE")
            val route = "${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships/$membershipId/activate"

            mockMvc
                .post(route) {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"reason\":\"${"x".repeat(500)}\"}"
                    with(authentication(platformToken(setOf("user.approve"))))
                }.andExpect { status { isOk() } }
            mockMvc
                .post(route) { with(authentication(platformToken(setOf("user.approve")))) }
                .andExpect { status { isOk() } }

            verify(userProvisioningService).approveUser(
                argThat<ApproveUserCommand> {
                    reason == "x".repeat(500) && scope == ActingScope.PLATFORM
                },
            )
            verify(userProvisioningService).approveUser(
                argThat<ApproveUserCommand> { reason == null },
            )
        }

        @Test
        fun `platform membership activation rejects a remark over 500 characters`() {
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships/$membershipId/activate") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"reason\":\"${"x".repeat(501)}\"}"
                    with(authentication(platformToken(setOf("user.approve"))))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            verify(userProvisioningService, never()).approveUser(any())
        }

        @Test
        fun `platform membership activation answers 202 while identity provisioning is queued`() {
            whenever(userProvisioningService.approveUser(any())).thenReturn(approval(true))
            stubMembershipDetail("PENDING_APPROVAL")

            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships/$membershipId/activate") {
                    with(authentication(platformToken(setOf("user.approve"))))
                }.andExpect { status { isAccepted() } }
        }

        @Test
        fun `a membership approval refused by the service for the permission is 403`() {
            whenever(userProvisioningService.approveUser(any()))
                .thenThrow(AccessDeniedException("no"))

            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships/$membershipId/activate") {
                    with(authentication(platformToken(setOf("user.approve"))))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `a token without the coarse authority is refused on every checker route`() {
            checkerRoutes().forEach { path ->
                mockMvc
                    .post(path) { with(authentication(platformToken(setOf("audit.view")))) }
                    .andExpect { status { isForbidden() } }
            }

            verify(userProvisioningService, never()).approveUser(any())
            verify(branchProvisioningService, never()).activate(any())
            verify(branchProvisioningService, never()).submitForApproval(any())
        }

        @Test
        fun `a tenant context cannot use the checker routes even holding the authority`() {
            val authorities = setOf("user.approve", "branch.activate", "branch.create")
            checkerRoutes().forEach { path ->
                mockMvc
                    .post(path) { with(authentication(tenantToken(authorities))) }
                    .andExpect { status { isForbidden() } }
            }

            verify(userProvisioningService, never()).approveUser(any())
            verify(branchProvisioningService, never()).activate(any())
            verify(branchProvisioningService, never()).submitForApproval(any())
        }

        @Test
        fun `application failures map to 403 404 and 409`() {
            val authorities = setOf("user.approve", "branch.activate")
            whenever(userProvisioningService.approveUser(any()))
                .thenThrow(ResourceNotFoundException())
            doThrow(ForbiddenOperationException())
                .whenever(branchProvisioningService)
                .activate(any())

            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships/$membershipId/activate") {
                    with(authentication(platformToken(authorities)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/activate") {
                    with(authentication(platformToken(authorities)))
                }.andExpect { status { isForbidden() } }

            doThrow(ConflictException())
                .whenever(branchProvisioningService)
                .activate(any())
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/activate") {
                    with(authentication(platformToken(authorities)))
                }.andExpect { status { isConflict() } }
        }

        @Test
        fun `checker mutations validate the idempotency key and authentication`() {
            checkerRoutes().forEach { path ->
                mockMvc
                    .post(path)
                    .andExpect { status { isUnauthorized() } }
                mockMvc
                    .post(path) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, "not-a-uuid")
                        with(authentication(platformToken(emptySet())))
                    }.andExpect { status { isBadRequest() } }
            }
        }

        @Test
        fun `platform return passes the path tenant the reason and the platform scope`() {
            stubBranchDetail("DRAFT")

            returnBranch(setOf("branch.activate")).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
                jsonPath("$.status") { value("DRAFT") }
            }

            verify(branchProvisioningService).returnForChanges(
                argThat<ReturnBranchCommand> {
                    organisationId == tenantId &&
                        this.branchId == this@PlatformTenantCheckerControllerTests.branchId &&
                        actorId == this@PlatformTenantCheckerControllerTests.actorId &&
                        reason == "Branch code has a typo." &&
                        scope == ActingScope.PLATFORM
                },
            )
            // Never the branch.view gated read: a role holding only the mutation's permission
            // would otherwise have the return rolled back.
            verify(foundationQueryService)
                .getBranchAfterAuthorizedMutation(eq(tenantId), eq(branchId))
            verify(foundationQueryService, never()).getBranch(any(), any(), any())
        }

        @Test
        fun `platform return is open to the maker or the checker authority and nobody else`() {
            stubBranchDetail("DRAFT")

            listOf("branch.create", "branch.activate").forEach { authority ->
                returnBranch(setOf(authority)).andExpect { status { isOk() } }
            }
            returnBranch(setOf("audit.view")).andExpect { status { isForbidden() } }
            // A tenant context never reaches the platform route, whatever it holds.
            mockMvc
                .post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/return") {
                    contentType = MediaType.APPLICATION_JSON
                    content = RETURN_BODY
                    with(authentication(tenantToken(setOf("branch.activate", "branch.create"))))
                }.andExpect { status { isForbidden() } }

            verify(branchProvisioningService, times(2)).returnForChanges(any())
        }

        @Test
        fun `platform return requires a body with a reason of three to 500 characters`() {
            listOf(null, "{}", "{\"reason\":null}").forEach { body ->
                returnBranch(setOf("branch.activate"), body).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_json") }
                }
            }
            listOf("", "  ", "ab", "x".repeat(501)).forEach { reason ->
                returnBranch(
                    setOf("branch.activate"),
                    apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason)),
                ).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
            }

            verify(branchProvisioningService, never()).returnForChanges(any())
        }

        @Test
        fun `platform return maps the service refusals and validates the idempotency key`() {
            listOf(
                ForbiddenOperationException() to 403,
                ResourceNotFoundException() to 404,
                ConflictException() to 409,
            ).forEach { (refusal, expected) ->
                doThrow(refusal).whenever(branchProvisioningService).returnForChanges(any())
                returnBranch(setOf("branch.activate")).andExpect {
                    status { isEqualTo(expected) }
                }
            }
            val path = "${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/return"
            mockMvc.post(path).andExpect { status { isUnauthorized() } }
            mockMvc
                .post(path) {
                    header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, "not-a-uuid")
                    with(authentication(platformToken(emptySet())))
                }.andExpect { status { isBadRequest() } }
        }

        private fun returnBranch(
            authorities: Set<String>,
            body: String? = RETURN_BODY,
        ) = mockMvc.post("${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/return") {
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            with(authentication(platformToken(authorities)))
        }

        private val branchActions =
            mapOf("submit" to "branch.create", "activate" to "branch.activate")

        private fun checkerRoutes() =
            listOf(
                "${ApiPaths.PLATFORM_TENANTS}/$tenantId/memberships/$membershipId/activate",
                "${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/submit",
                "${ApiPaths.PLATFORM_TENANTS}/$tenantId/branches/$branchId/activate",
            )

        private fun approval(keycloakRequested: Boolean) =
            UserApprovalResult(
                userId = uuidV7(),
                membershipId = membershipId,
                userStatus = UserLifecycleState.INVITED,
                membershipStatus = MembershipLifecycleState.PENDING_APPROVAL,
                keycloakProvisioningRequested = keycloakRequested,
                applicationInviteRequested = false,
            )

        private fun stubBranchDetail(status: String) {
            whenever(
                foundationQueryService.getBranchAfterAuthorizedMutation(eq(tenantId), eq(branchId)),
            ).thenReturn(
                BranchDetail(
                    id = branchId,
                    organisationId = tenantId,
                    branchCode = "HQ-01",
                    branchName = "Head Office",
                    branchType = "HEAD_OFFICE",
                    parentBranchId = null,
                    status = status,
                    timezone = "UTC",
                    addressJson = "{}",
                    openedOn = null,
                    closedOn = null,
                    statusReason = null,
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                ),
            )
        }

        private fun stubMembershipDetail(status: String) {
            whenever(
                lifecycleIamReadService.getMembershipAfterAuthorizedMutation(
                    eq(tenantId),
                    eq(membershipId),
                ),
            ).thenReturn(
                LifecycleMembershipDetail(
                    id = membershipId,
                    organisationId = tenantId,
                    userId = uuidV7(),
                    username = "second.user",
                    email = "second@tenant.test",
                    displayName = "Second User",
                    userStatus = "ACTIVE",
                    membershipStatus = status,
                    membershipType = "STAFF",
                    primaryBranchId = null,
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                ),
            )
        }

        private fun platformToken(permissions: Set<String>) =
            token(PlatformOrganisation.ID, permissions)

        private fun tenantToken(permissions: Set<String>) = token(tenantId, permissions)

        private fun token(
            organisationId: UUID,
            permissions: Set<String>,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = actorId,
                keycloakSubject = "checker-$actorId",
                organisationId = organisationId,
                membershipId = uuidV7(),
                email = "checker@platform.test",
                fullName = "Checker",
                permissions = permissions,
            ),
        )
    }
