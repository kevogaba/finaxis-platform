package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.context.PlatformOrganisation
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
import com.finaxis.platform.iam.adapter.inbound.web.dto.DeactivateUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.ReactivateUserRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.SuspendUserRequest
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.query.GlobalUserDetail
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.UserInTenantDetail
import com.finaxis.platform.iam.application.query.UserInTenantSummary
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.application.DeactivateUserCommand
import com.finaxis.platform.lifecycle.application.ReactivateUserCommand
import com.finaxis.platform.lifecycle.application.SuspendUserCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
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

        @Test
        fun `read routes enforce authentication and permission`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            val routes =
                listOf(
                    "${ApiPaths.PLATFORM_TENANTS}/$tenantId/users",
                    "${ApiPaths.PLATFORM_TENANTS}/$tenantId/users/$userId",
                )

            routes.forEach { path ->
                mockMvc
                    .get(path)
                    .andExpect { status { isUnauthorized() } }
                mockMvc
                    .get(path) {
                        with(authentication(platformToken(emptySet())))
                    }.andExpect {
                        status { isForbidden() }
                    }
            }
        }

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
                readBackAs(userId, statuses[index])
                mockMvc
                    .post("${ApiPaths.PLATFORM_USERS}/$userId/$action") {
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(request)
                        with(authentication(platformToken(setOf(permissions[index]))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.user_id") { value(userId.toString()) }
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
        fun `the lifecycle routes hand the caller to the service and read back as that caller`() {
            val userId = uuidV7()
            val actorId = uuidV7()
            readBackAs(userId, "SUSPENDED")

            mockMvc
                .post("${ApiPaths.PLATFORM_USERS}/$userId/suspend") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"Security investigation"}"""
                    with(authentication(platformToken(setOf("user.suspend"), actorId)))
                }.andExpect { status { isOk() } }

            val command = argumentCaptor<SuspendUserCommand>()
            verify(userProvisioningService).suspendUser(command.capture())
            kotlin.test.assertEquals(actorId, command.firstValue.actorId)
            verify(iamQueryService)
                .getGlobalUser(userId, PlatformCaller(actorId, PlatformOrganisation.ID))
        }

        @Test
        fun `a service refusal names the missing permission and nothing is read back`() {
            val userId = uuidV7()
            doThrow(MissingPermissionException("user.view"))
                .whenever(userProvisioningService)
                .suspendUser(any())

            mockMvc
                .post("${ApiPaths.PLATFORM_USERS}/$userId/suspend") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"Security investigation"}"""
                    with(authentication(platformToken(setOf("user.suspend"))))
                }.andExpect {
                    status { isForbidden() }
                    jsonPath("$.detail") { value("Missing permission: user.view.") }
                }

            verify(iamQueryService, never()).getGlobalUser(any(), any())
        }

        @Test
        fun `platform lifecycle mutation routes enforce authentication and permission`() {
            val userId = uuidV7()
            platformLifecycleMutationRoutes(userId).forEach { (method, path, permission) ->
                val payload = platformLifecycleMutationPayload(path)
                mockMvc
                    .perform(
                        request(method, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload),
                    ).andExpect(status().isUnauthorized)
                mockMvc
                    .perform(
                        request(method, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(authentication(platformToken(emptySet()))),
                    ).andExpect(status().isForbidden)
                kotlin.test.assertTrue(permission.isNotBlank())
            }
        }

        @Test
        fun `reactivateUser accepts no body an empty body or a reason`() {
            val userId = uuidV7()
            readBackAs(userId, "ACTIVE")
            val bodies = listOf(null, "{}", """{"reason":"Investigation complete"}""")

            bodies.forEach { body ->
                mockMvc
                    .post("${ApiPaths.PLATFORM_USERS}/$userId/reactivate") {
                        if (body != null) {
                            contentType = MediaType.APPLICATION_JSON
                            content = body
                        }
                        with(authentication(platformToken(setOf("user.activate"))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.status") { value("ACTIVE") }
                    }
            }

            val captor = argumentCaptor<ReactivateUserCommand>()
            verify(userProvisioningService, times(3)).reactivateUser(captor.capture())
            kotlin.test.assertEquals(
                listOf(null, null, "Investigation complete"),
                captor.allValues.map { it.reason?.value },
            )
        }

        @Test
        fun `reactivateUser still rejects a reason over 500 characters`() {
            mockMvc
                .post("${ApiPaths.PLATFORM_USERS}/${uuidV7()}/reactivate") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"reason":"${"x".repeat(501)}"}"""
                    with(authentication(platformToken(setOf("user.activate"))))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            verify(userProvisioningService, never()).reactivateUser(any())
        }

        @Test
        fun `an unknown user is a 404 problem on every platform lifecycle route`() {
            val userId = uuidV7()
            val notFound = ResourceNotFoundException(safeDetail = "User account was not found.")
            doThrow(notFound).whenever(userProvisioningService).suspendUser(any())
            doThrow(notFound).whenever(userProvisioningService).reactivateUser(any())
            doThrow(notFound).whenever(userProvisioningService).deactivateUser(any())

            listOf(
                Triple("suspend", "user.suspend", """{"reason":"Security investigation"}"""),
                Triple("reactivate", "user.activate", null),
                Triple("deactivate", "user.deactivate", """{"reason":"No longer eligible"}"""),
            ).forEach { (action, permission, body) ->
                mockMvc
                    .post("${ApiPaths.PLATFORM_USERS}/$userId/$action") {
                        if (body != null) {
                            contentType = MediaType.APPLICATION_JSON
                            content = body
                        }
                        with(authentication(platformToken(setOf(permission))))
                    }.andExpect {
                        status { isNotFound() }
                        content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                        jsonPath("$.code") { value("resource_not_found") }
                        jsonPath("$.detail") { value("User account was not found.") }
                        jsonPath(
                            "$.instance",
                        ) { value("${ApiPaths.PLATFORM_USERS}/$userId/$action") }
                    }
            }
        }

        @Test
        fun `a caller without the permission gets 403 before any 404 for an unknown user`() {
            val userId = uuidV7()
            val notFound = ResourceNotFoundException(safeDetail = "User account was not found.")
            doThrow(notFound).whenever(userProvisioningService).suspendUser(any())
            doThrow(notFound).whenever(userProvisioningService).reactivateUser(any())
            doThrow(notFound).whenever(userProvisioningService).deactivateUser(any())

            listOf("suspend", "reactivate", "deactivate").forEach { action ->
                mockMvc
                    .post("${ApiPaths.PLATFORM_USERS}/$userId/$action") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"reason":"Valid reason"}"""
                        with(authentication(platformToken(emptySet())))
                    }.andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("forbidden") }
                    }
            }

            verifyNoInteractions(userProvisioningService)
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

        @Test
        fun `reactivateUser keeps its reason optional`() {
            val userId = uuidV7()
            readBackAs(userId, "ACTIVE")

            listOf("{}", "{\"reason\":null}", "{\"reason\":\"ok\"}", "{\"reason\":\"  \"}")
                .forEach { body ->
                    reactivateUser(userId, body).andExpect {
                        status { isOk() }
                        jsonPath("$.status") { value("ACTIVE") }
                    }
                }

            val captor = argumentCaptor<ReactivateUserCommand>()
            verify(userProvisioningService, org.mockito.kotlin.times(4))
                .reactivateUser(captor.capture())
            kotlin.test.assertEquals(
                listOf(null, null, "ok", null),
                captor.allValues.map { it.reason?.value },
            )
        }

        @Test
        fun `reactivateUser rejects a reason over 500 characters`() {
            reactivateUser(uuidV7(), "{\"reason\":\"${"x".repeat(501)}\"}").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
            }

            verify(userProvisioningService, org.mockito.kotlin.never())
                .reactivateUser(any<ReactivateUserCommand>())
        }

        private fun readBackAs(
            userId: UUID,
            status: String,
        ) {
            whenever(iamQueryService.getGlobalUser(eq(userId), any())).thenReturn(
                GlobalUserDetail(userId, status),
            )
        }

        private fun reactivateUser(
            userId: UUID,
            body: String,
        ) = mockMvc.post("${ApiPaths.PLATFORM_USERS}/$userId/reactivate") {
            contentType = MediaType.APPLICATION_JSON
            content = body
            with(authentication(platformToken(setOf("user.activate"))))
        }

        private fun platformLifecycleMutationRoutes(userId: UUID) =
            listOf(
                Triple(
                    HttpMethod.POST,
                    "${ApiPaths.PLATFORM_USERS}/$userId/suspend",
                    "user.suspend",
                ),
                Triple(
                    HttpMethod.POST,
                    "${ApiPaths.PLATFORM_USERS}/$userId/reactivate",
                    "user.activate",
                ),
                Triple(
                    HttpMethod.POST,
                    "${ApiPaths.PLATFORM_USERS}/$userId/deactivate",
                    "user.deactivate",
                ),
            )

        private fun platformLifecycleMutationPayload(path: String): String =
            when {
                path.endsWith("/suspend") -> {
                    apiJsonCodec.mapper.writeValueAsString(
                        SuspendUserRequest("Security investigation"),
                    )
                }

                path.endsWith("/reactivate") -> {
                    apiJsonCodec.mapper.writeValueAsString(
                        ReactivateUserRequest("Investigation complete"),
                    )
                }

                else -> {
                    apiJsonCodec.mapper.writeValueAsString(
                        DeactivateUserRequest("No longer eligible"),
                    )
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

        private fun platformToken(
            permissions: Set<String>,
            userId: UUID = uuidV7(),
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = userId,
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
