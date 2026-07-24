package com.finaxis.platform.iam.adapter.inbound.web

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
import com.finaxis.platform.iam.adapter.inbound.web.dto.InviteUserRequest
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.UserInTenantDetail
import com.finaxis.platform.iam.application.query.UserInTenantSummary
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.UserInvitationResult
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleState
import com.finaxis.platform.lifecycle.domain.UserLifecycleState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

private const val MODULITH_RUNTIME_AUTO_CONFIGURATION =
    "org.springframework.modulith.runtime.autoconfigure." +
        "SpringModulithRuntimeAutoConfiguration"
private const val EXCLUDE_MODULITH_RUNTIME =
    "spring.autoconfigure.exclude=$MODULITH_RUNTIME_AUTO_CONFIGURATION"

@WebMvcTest(
    controllers = [TenantUserController::class],
    properties = [EXCLUDE_MODULITH_RUNTIME],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    TenantUserControllerTests.TestSecurityConfiguration::class,
    TenantUserController::class,
)
class TenantUserControllerTests
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
        private lateinit var iamQueryService: IamQueryService

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `searchUsers returns tenant scoped paginated users`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            whenever(iamQueryService.searchUsers(eq(tenantId), any(), any())).thenReturn(
                apiPageOf(
                    listOf(userSummary(userId)),
                    number = 1,
                    size = 10,
                    totalItems = 11,
                ),
            )

            mockMvc
                .get(ApiPaths.TENANT_USERS) {
                    param("q", "admin")
                    param("user_status", "ACTIVE")
                    param("membership_status", "ACTIVE")
                    param("page", "1")
                    param("size", "10")
                    with(authentication(tenantToken(setOf("user.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(userId.toString()) }
                    jsonPath("$.page.number") { value(1) }
                }
        }

        @Test
        fun `inviteUser creates invitation and returns user location`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            val membershipId = uuidV7()
            whenever(userProvisioningService.inviteUser(any())).thenReturn(
                UserInvitationResult(
                    userId,
                    membershipId,
                    UserLifecycleState.INVITED,
                    MembershipLifecycleState.PENDING_APPROVAL,
                ),
            )

            mockMvc
                .post(ApiPaths.TENANT_USERS) {
                    contentType = MediaType.APPLICATION_JSON
                    content = apiJsonCodec.mapper.writeValueAsString(inviteRequest())
                    with(authentication(tenantToken(setOf("user.invite"), tenantId)))
                }.andExpect {
                    status { isCreated() }
                    header { string("Location", "${ApiPaths.TENANT_USERS}/$userId") }
                    jsonPath("$.membership_id") { value(membershipId.toString()) }
                }

            verify(userProvisioningService).inviteUser(any())
        }

        @Test
        fun `inviteUser rejects invalid email before invoking the service`() {
            val tenantId = uuidV7()

            mockMvc
                .post(ApiPaths.TENANT_USERS) {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"email":"invalid","username":"admin","display_name":"Admin", "membership_type":"ADMIN"}"""
                    with(authentication(tenantToken(setOf("user.invite"), tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
        }

        @Test
        fun `getUser returns safe 404 for cross tenant user`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            whenever(iamQueryService.getUserInTenant(eq(tenantId), eq(userId), any())).thenThrow(
                ResourceNotFoundException(safeDetail = "User not found: $userId"),
            )

            mockMvc
                .get("${ApiPaths.TENANT_USERS}/$userId") {
                    with(authentication(tenantToken(setOf("user.view"), tenantId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
        }

        @Test
        fun `getUser returns user detail`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            whenever(iamQueryService.getUserInTenant(eq(tenantId), eq(userId), any())).thenReturn(
                userDetail(userId),
            )

            mockMvc
                .get("${ApiPaths.TENANT_USERS}/$userId") {
                    with(authentication(tenantToken(setOf("user.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.email") { value("admin@tenant.test") }
                }
        }

        private fun inviteRequest() =
            InviteUserRequest(
                email = "admin@tenant.test",
                username = "admin",
                displayName = "Tenant Administrator",
                membershipType = MembershipType.ADMIN,
            )

        private fun userSummary(userId: UUID) =
            UserInTenantSummary(
                userId,
                "admin",
                "admin@tenant.test",
                "Tenant Administrator",
                "ACTIVE",
                "ACTIVE",
            )

        private fun userDetail(userId: UUID) =
            UserInTenantDetail(
                userId,
                "admin",
                "admin@tenant.test",
                "Tenant Administrator",
                "ACTIVE",
                "ACTIVE",
            )

        private fun tenantToken(
            permissions: Set<String>,
            tenantId: UUID,
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = uuidV7(),
                keycloakSubject = "tenant-user",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "admin@tenant.test",
                fullName = "Tenant Administrator",
                permissions = permissions,
            ),
        )
    }
