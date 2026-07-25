package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.idempotency.IdempotencyProperties
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateMembershipRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.RevokeMembershipRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendMembershipRequest
import com.finaxis.platform.lifecycle.application.UserApprovalResult
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.application.query.LifecycleIamReadService
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipSummary
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

private const val MODULITH_RUNTIME_AUTO_CONFIGURATION =
    "org.springframework.modulith.runtime.autoconfigure." +
        "SpringModulithRuntimeAutoConfiguration"
private const val EXCLUDE_MODULITH_RUNTIME =
    "spring.autoconfigure.exclude=$MODULITH_RUNTIME_AUTO_CONFIGURATION"

@WebMvcTest(
    controllers = [MembershipController::class],
    properties = [EXCLUDE_MODULITH_RUNTIME],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    MembershipControllerTests.TestSecurityConfiguration::class,
    MembershipController::class,
)
class MembershipControllerTests
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
        private lateinit var userProvisioningService: UserProvisioningService

        @MockitoBean
        private lateinit var lifecycleIamReadService: LifecycleIamReadService

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `searchMemberships returns a bounded page`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            whenever(
                lifecycleIamReadService.searchMemberships(eq(tenantId), any(), any()),
            ).thenReturn(
                apiPageOf(
                    listOf(
                        LifecycleMembershipSummary(
                            membershipId,
                            uuidV7(),
                            "ACTIVE",
                            "ADMIN",
                            null,
                        ),
                    ),
                    number = 1,
                    size = 10,
                    totalItems = 11,
                ),
            )

            mockMvc
                .get(ApiPaths.MEMBERSHIPS) {
                    param("page", "1")
                    param("size", "10")
                    param("q", "admin")
                    param("membership_status", "ACTIVE")
                    param("membership_type", "ADMIN")
                    with(authentication(tenantToken(setOf("membership.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(membershipId.toString()) }
                    jsonPath("$.page.number") { value(1) }
                    jsonPath("$.page.size") { value(10) }
                }
        }

        @Test
        fun `getMembership returns membership detail`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            stubMembershipDetail(tenantId, membershipId, "ACTIVE")

            mockMvc
                .get("${ApiPaths.MEMBERSHIPS}/$membershipId") {
                    with(authentication(tenantToken(setOf("membership.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(membershipId.toString()) }
                    jsonPath("$.membership_status") { value("ACTIVE") }
                }
        }

        @Test
        fun `getMembership returns safe 404 for a cross tenant membership`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            whenever(
                lifecycleIamReadService.getMembership(eq(tenantId), eq(membershipId), any()),
            ).thenThrow(
                ResourceNotFoundException(safeDetail = "Membership not found: $membershipId"),
            )

            mockMvc
                .get("${ApiPaths.MEMBERSHIPS}/$membershipId") {
                    with(authentication(tenantToken(setOf("membership.view"), tenantId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
        }

        @Test
        fun `activate returns accepted when keycloak provisioning is queued`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            val userId = uuidV7()
            whenever(userProvisioningService.approveUser(any())).thenReturn(
                approvalResult(userId, membershipId, keycloakRequested = true),
            )
            stubMembershipDetail(tenantId, membershipId, "PENDING_APPROVAL", userId)

            mockMvc
                .post("${ApiPaths.MEMBERSHIPS}/$membershipId/activate") {
                    with(authentication(tenantToken(setOf("user.approve"), tenantId)))
                }.andExpect {
                    status { isAccepted() }
                    jsonPath("$.id") { value(membershipId.toString()) }
                }

            verify(userProvisioningService).approveUser(any())
        }

        @Test
        fun `activate returns ok when membership is activated immediately`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            val userId = uuidV7()
            whenever(userProvisioningService.approveUser(any())).thenReturn(
                approvalResult(userId, membershipId, keycloakRequested = false),
            )
            stubMembershipDetail(tenantId, membershipId, "ACTIVE", userId)

            mockMvc
                .post("${ApiPaths.MEMBERSHIPS}/$membershipId/activate") {
                    with(authentication(tenantToken(setOf("user.approve"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.membership_status") { value("ACTIVE") }
                }
        }

        @Test
        fun `suspend returns updated membership detail`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            stubMembershipDetail(tenantId, membershipId, "SUSPENDED")

            mockMvc
                .post("${ApiPaths.MEMBERSHIPS}/$membershipId/suspend") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            SuspendMembershipRequest("Temporary audit closure"),
                        )
                    with(authentication(tenantToken(setOf("membership.suspend"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.membership_status") { value("SUSPENDED") }
                }

            verify(userProvisioningService).suspendMembership(any())
        }

        @Test
        fun `suspend rejects a blank reason`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()

            mockMvc
                .post("${ApiPaths.MEMBERSHIPS}/$membershipId/suspend") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"reason\":\"\"}"
                    with(authentication(tenantToken(setOf("membership.suspend"), tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
        }

        @Test
        fun `membership state conflicts map to safe problem responses`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            whenever(userProvisioningService.approveUser(any())).thenThrow(ConflictException())
            doThrow(ConflictException()).whenever(userProvisioningService).suspendMembership(any())
            doThrow(ConflictException()).whenever(userProvisioningService).reactivateMembership(
                any(),
            )

            listOf(
                Triple(
                    "user.approve",
                    "${ApiPaths.MEMBERSHIPS}/$membershipId/activate",
                    "{}",
                ),
                Triple(
                    "membership.suspend",
                    "${ApiPaths.MEMBERSHIPS}/$membershipId/suspend",
                    apiJsonCodec.mapper.writeValueAsString(
                        SuspendMembershipRequest("Already revoked"),
                    ),
                ),
                Triple(
                    "membership.reactivate",
                    "${ApiPaths.MEMBERSHIPS}/$membershipId/reactivate",
                    apiJsonCodec.mapper.writeValueAsString(
                        ReactivateMembershipRequest("Already revoked"),
                    ),
                ),
            ).forEach { (permission, path, payload) ->
                mockMvc
                    .post(path) {
                        contentType = MediaType.APPLICATION_JSON
                        content = payload
                        with(authentication(tenantToken(setOf(permission), tenantId)))
                    }.andExpect {
                        status { isConflict() }
                        content {
                            contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                        }
                        jsonPath("$.code") { value("conflict") }
                    }
            }
        }

        @Test
        fun `reactivate returns updated membership detail`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            stubMembershipDetail(tenantId, membershipId, "ACTIVE")

            mockMvc
                .post("${ApiPaths.MEMBERSHIPS}/$membershipId/reactivate") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            ReactivateMembershipRequest("Audit completed"),
                        )
                    with(authentication(tenantToken(setOf("membership.reactivate"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.membership_status") { value("ACTIVE") }
                }

            verify(userProvisioningService).reactivateMembership(any())
        }

        @Test
        fun `revoke returns updated membership detail`() {
            val tenantId = uuidV7()
            val membershipId = uuidV7()
            stubMembershipDetail(tenantId, membershipId, "REVOKED")

            mockMvc
                .post("${ApiPaths.MEMBERSHIPS}/$membershipId/revoke") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            RevokeMembershipRequest("Access no longer required"),
                        )
                    with(authentication(tenantToken(setOf("membership.revoke"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.membership_status") { value("REVOKED") }
                }

            verify(userProvisioningService).revokeTenantMembership(any())
        }

        @Test
        fun `membership mutations generate or validate idempotency keys before authentication`() {
            val membershipId = uuidV7()
            val membershipRoute = "${ApiPaths.MEMBERSHIPS}/$membershipId"
            val routes =
                listOf(
                    "$membershipRoute/activate",
                    "$membershipRoute/suspend",
                    "$membershipRoute/reactivate",
                    "$membershipRoute/revoke",
                )

            routes.forEach { path ->
                val payload = membershipMutationPayload(path)
                mockMvc
                    .perform(
                        request(HttpMethod.POST, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload),
                    ).andExpect(status().isUnauthorized)
                mockMvc
                    .perform(
                        request(HttpMethod.POST, path)
                            .header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, "not-a-uuid")
                            .with(authentication(tenantToken(emptySet()))),
                    ).andExpect(status().isBadRequest)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
                mockMvc
                    .perform(
                        request(HttpMethod.POST, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(authentication(tenantToken(emptySet()))),
                    ).andExpect(status().isForbidden)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
            }
        }

        private fun approvalResult(
            userId: UUID,
            membershipId: UUID,
            keycloakRequested: Boolean,
        ) = UserApprovalResult(
            userId = userId,
            membershipId = membershipId,
            userStatus = UserLifecycleState.INVITED,
            membershipStatus = MembershipLifecycleState.PENDING_APPROVAL,
            keycloakProvisioningRequested = keycloakRequested,
            applicationInviteRequested = false,
        )

        private fun stubMembershipDetail(
            tenantId: UUID,
            membershipId: UUID,
            membershipStatus: String,
            userId: UUID = uuidV7(),
        ) {
            whenever(
                lifecycleIamReadService.getMembership(eq(tenantId), eq(membershipId), any()),
            ).thenReturn(
                LifecycleMembershipDetail(
                    id = membershipId,
                    organisationId = tenantId,
                    userId = userId,
                    username = "tenant-admin",
                    email = "admin@tenant.test",
                    displayName = "Tenant Admin",
                    userStatus = "ACTIVE",
                    membershipStatus = membershipStatus,
                    membershipType = "ADMIN",
                    primaryBranchId = null,
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                ),
            )
        }

        private fun membershipMutationPayload(path: String): String =
            when {
                path.endsWith(
                    "/suspend",
                ) || path.endsWith("/revoke") -> "{\"reason\":\"Valid reason\"}"

                else -> "{}"
            }

        private fun tenantToken(
            permissions: Set<String>,
            tenantId: UUID = uuidV7(),
            userId: UUID = uuidV7(),
        ): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "tenant-user",
                    organisationId = tenantId,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "admin@tenant.test",
                    fullName = "Tenant Admin",
                    permissions = permissions,
                ),
            )
    }
