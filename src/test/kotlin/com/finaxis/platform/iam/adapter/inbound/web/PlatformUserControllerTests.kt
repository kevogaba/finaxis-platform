package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.idempotency.IdempotencyProperties
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.DeactivateUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.ReactivateUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.SuspendUserRequest
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.UserInTenantDetail
import com.finaxis.platform.iam.application.query.UserInTenantSummary
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.DeactivateUserCommand
import com.finaxis.platform.lifecycle.application.ReactivateUserCommand
import com.finaxis.platform.lifecycle.application.SuspendUserCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
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
    controllers = [PlatformUserController::class, PlatformUserLifecycleController::class],
    properties = [EXCLUDE_MODULITH_RUNTIME],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    PlatformUserControllerTests.TestSecurityConfiguration::class,
    PlatformUserController::class,
    PlatformUserLifecycleController::class,
)
class PlatformUserControllerTests
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
        private lateinit var iamQueryService: IamQueryService

        @MockitoBean
        private lateinit var userProvisioningService: UserProvisioningService

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `searchUsers lists users within nested tenant route`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            whenever(iamQueryService.searchUsers(eq(tenantId), any(), any())).thenReturn(
                apiPageOf(listOf(userSummary(userId)), 0, 25, 1),
            )

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$tenantId/users") {
                    with(authentication(platformToken(setOf("user.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(userId.toString()) }
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
                .get("${ApiPaths.PLATFORM_TENANTS}/$tenantId/users/$userId") {
                    with(authentication(platformToken(setOf("user.view"))))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
        }

        @Test
        fun `getUser returns tenant scoped detail`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            whenever(iamQueryService.getUserInTenant(eq(tenantId), eq(userId), any())).thenReturn(
                userDetail(userId),
            )

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$tenantId/users/$userId") {
                    with(authentication(platformToken(setOf("user.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.username") { value("platform-user") }
                }
        }

        @Test
        fun `platform lifecycle endpoints return their target statuses`() {
            val userId = uuidV7()
            val routes =
                listOf(
                    "suspend" to SuspendUserRequest("Security investigation"),
                    "reactivate" to ReactivateUserRequest("Investigation complete"),
                    "deactivate" to DeactivateUserRequest("No longer eligible"),
                )
            val permissions = listOf("user.suspend", "user.activate", "user.deactivate")
            val statuses = listOf("SUSPENDED", "ACTIVE", "DEACTIVATED")

            routes.forEachIndexed { index, (action, request) ->
                mockMvc
                    .post("${ApiPaths.PLATFORM_USERS}/$userId/$action") {
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(request)
                        with(authentication(platformToken(setOf(permissions[index]))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.status") { value(statuses[index]) }
                    }
            }

            verify(userProvisioningService).suspendUser(any<SuspendUserCommand>())
            verify(userProvisioningService).reactivateUser(any<ReactivateUserCommand>())
            val commandCaptor = argumentCaptor<DeactivateUserCommand>()
            verify(userProvisioningService).deactivateUser(commandCaptor.capture())
            kotlin.test.assertEquals(
                PlatformOrganisation.ID,
                commandCaptor.firstValue.organisationId,
            )
        }

        @Test
        fun `suspendUser rejects blank reason`() {
            mockMvc
                .post("${ApiPaths.PLATFORM_USERS}/${uuidV7()}/suspend") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"reason\":\"\"}"
                    with(authentication(platformToken(setOf("user.suspend"))))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
        }

        private fun userSummary(userId: UUID) =
            UserInTenantSummary(
                userId,
                "platform-user",
                "user@tenant.test",
                "Platform User",
                "ACTIVE",
                "ACTIVE",
            )

        private fun userDetail(userId: UUID) =
            UserInTenantDetail(
                userId,
                "platform-user",
                "user@tenant.test",
                "Platform User",
                "ACTIVE",
                "ACTIVE",
            )

        private fun platformToken(permissions: Set<String>) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = uuidV7(),
                    keycloakSubject = "platform-user",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "platform@finaxis.test",
                    fullName = "Platform Administrator",
                    permissions = permissions,
                ),
            )
    }
