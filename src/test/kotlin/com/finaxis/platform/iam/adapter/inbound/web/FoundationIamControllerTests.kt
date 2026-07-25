package com.finaxis.platform.iam.adapter.inbound.web

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
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignPermissionRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.CreateRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.UpdateRoleRequest
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.PermissionDetail
import com.finaxis.platform.iam.application.query.PermissionSummary
import com.finaxis.platform.iam.application.query.RoleAssignmentDetail
import com.finaxis.platform.iam.application.query.RoleAssignmentSummary
import com.finaxis.platform.iam.application.query.RoleDetail
import com.finaxis.platform.iam.application.query.RolePermissionDetail
import com.finaxis.platform.iam.application.query.RolePermissionSummary
import com.finaxis.platform.iam.application.query.RoleSummary
import com.finaxis.platform.iam.application.role.AssignRoleToUser
import com.finaxis.platform.iam.application.role.RevokeRoleFromUser
import com.finaxis.platform.iam.application.role.RoleAssignmentResult
import com.finaxis.platform.iam.application.role.RoleManagementService
import com.finaxis.platform.iam.application.role.RoleResult
import com.finaxis.platform.iam.application.role.RoleScopeType
import com.finaxis.platform.iam.domain.RoleStatus
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

private const val MODULITH_RUNTIME_AUTO_CONFIGURATION =
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration"
private const val EXCLUDE_MODULITH_RUNTIME =
    "spring.autoconfigure.exclude=$MODULITH_RUNTIME_AUTO_CONFIGURATION"

@WebMvcTest(
    controllers = [
        RoleController::class,
        RoleAssignmentController::class,
        PermissionController::class,
    ],
    properties = [EXCLUDE_MODULITH_RUNTIME],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    FoundationIamControllerTests.TestSecurityConfiguration::class,
    RoleController::class,
    RoleAssignmentController::class,
    PermissionController::class,
)
class FoundationIamControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
    ) {
        /** Minimal security and idempotency configuration for MVC controller tests. */
        @TestConfiguration
        @EnableMethodSecurity
        class TestSecurityConfiguration {
            /** Configures an authenticated-only test filter chain. */
            @Bean
            fun testSecurityFilterChain(http: HttpSecurity) =
                http
                    .csrf { it.disable() }
                    .authorizeHttpRequests { it.anyRequest().authenticated() }
                    .exceptionHandling {
                        it.authenticationEntryPoint(
                            org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                HttpStatus.UNAUTHORIZED,
                            ),
                        )
                    }.build()

            /** Registers the production idempotency-key validation filter. */
            @Bean
            fun idempotencyKeyFilter(
                problemFactory: ApiProblemFactory,
                jsonCodec: ApiJsonCodec,
            ) = org.springframework.boot.web.servlet.FilterRegistrationBean(
                IdempotencyKeyFilter(
                    IdempotencyProperties(),
                    ApiProblemWriter(problemFactory, jsonCodec),
                ),
            )
        }

        @MockitoBean
        private lateinit var roleManagementService: RoleManagementService

        @MockitoBean
        private lateinit var iamQueryService: IamQueryService

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `list endpoints return bounded pages`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val assignmentId = uuidV7()
            val permissionId = uuidV7()
            whenever(iamQueryService.searchRoles(eq(tenantId), any(), any())).thenReturn(
                apiPageOf(listOf(roleSummary(roleId)), number = 1, size = 10, totalItems = 11),
            )
            whenever(iamQueryService.searchRoleAssignments(eq(tenantId), any(), any())).thenReturn(
                apiPageOf(
                    listOf(roleAssignmentSummary(assignmentId)),
                    number = 1,
                    size = 10,
                    totalItems = 11,
                ),
            )
            whenever(iamQueryService.searchPermissions(eq(tenantId), any(), any())).thenReturn(
                apiPageOf(
                    listOf(permissionSummary(permissionId)),
                    number = 1,
                    size = 10,
                    totalItems = 11,
                ),
            )

            listOf(
                ApiPaths.ROLES to "role.view",
                ApiPaths.ROLE_ASSIGNMENTS to "role_assignment.view",
                ApiPaths.PERMISSIONS to "permission.view",
            ).forEach { (path, permission) ->
                mockMvc
                    .get(path) {
                        param("page", "1")
                        param("size", "10")
                        with(authentication(tenantToken(setOf(permission), tenantId)))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.page.number") { value(1) }
                        jsonPath("$.page.size") { value(10) }
                    }
            }
        }

        @Test
        fun `detail endpoints return their tenant scoped resources`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val assignmentId = uuidV7()
            val permissionId = uuidV7()
            whenever(iamQueryService.getRole(eq(tenantId), eq(roleId), any())).thenReturn(
                roleDetail(tenantId, roleId),
            )
            whenever(
                iamQueryService.getRoleAssignment(eq(tenantId), eq(assignmentId), any()),
            ).thenReturn(roleAssignmentDetail(tenantId, assignmentId))
            whenever(
                iamQueryService.getPermission(eq(tenantId), eq(permissionId), any()),
            ).thenReturn(permissionDetail(permissionId))

            detailRequests(roleId, assignmentId, permissionId)
                .forEach { (path, permission) ->
                    mockMvc
                        .get(path) {
                            with(authentication(tenantToken(setOf(permission), tenantId)))
                        }.andExpect {
                            status { isOk() }
                        }
                }
        }

        @Test
        fun `detail endpoints return safe 404 for cross tenant resources`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val assignmentId = uuidV7()
            val permissionId = uuidV7()
            whenever(iamQueryService.getRole(eq(tenantId), eq(roleId), any())).thenThrow(
                ResourceNotFoundException(),
            )
            whenever(
                iamQueryService.getRoleAssignment(eq(tenantId), eq(assignmentId), any()),
            ).thenThrow(ResourceNotFoundException())
            whenever(
                iamQueryService.getPermission(eq(tenantId), eq(permissionId), any()),
            ).thenThrow(ResourceNotFoundException())

            detailRequests(roleId, assignmentId, permissionId)
                .forEach { (path, permission) ->
                    mockMvc
                        .get(path) {
                            with(authentication(tenantToken(setOf(permission), tenantId)))
                        }.andExpect {
                            status { isNotFound() }
                            jsonPath("$.code") { value("resource_not_found") }
                        }
                }
        }

        @Test
        fun `role create update activate and deactivate return re fetched details`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            whenever(roleManagementService.createTenantRole(any())).thenReturn(
                RoleResult(roleId, RoleStatus.ACTIVE),
            )
            whenever(roleManagementService.updateTenantRole(any())).thenReturn(
                RoleResult(roleId, RoleStatus.ACTIVE),
            )
            whenever(roleManagementService.activateRole(any())).thenReturn(
                RoleResult(roleId, RoleStatus.ACTIVE),
            )
            whenever(roleManagementService.deactivateRole(any())).thenReturn(
                RoleResult(roleId, RoleStatus.DISABLED),
            )
            whenever(iamQueryService.getRole(eq(tenantId), eq(roleId), any())).thenReturn(
                roleDetail(tenantId, roleId),
            )

            mockMvc
                .post(ApiPaths.ROLES) {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            CreateRoleRequest("OPS", "Operations"),
                        )
                    with(authentication(tenantToken(setOf("role.create"), tenantId)))
                }.andExpect {
                    status { isCreated() }
                    header { string("Location", "${ApiPaths.ROLES}/$roleId") }
                    jsonPath("$.id") { value(roleId.toString()) }
                }
            mockMvc
                .patch("${ApiPaths.ROLES}/$roleId") {
                    contentType = MediaType.APPLICATION_JSON
                    content = apiJsonCodec.mapper.writeValueAsString(UpdateRoleRequest("Ops", null))
                    with(authentication(tenantToken(setOf("role.update"), tenantId)))
                }.andExpect { status { isOk() } }
            mockMvc
                .post("${ApiPaths.ROLES}/$roleId/activate") {
                    with(authentication(tenantToken(setOf("role.activate"), tenantId)))
                }.andExpect { status { isOk() } }
            mockMvc
                .post("${ApiPaths.ROLES}/$roleId/deactivate") {
                    with(authentication(tenantToken(setOf("role.deactivate"), tenantId)))
                }.andExpect { status { isOk() } }
        }

        @Test
        fun `immutable system role mutations map to conflict responses`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            whenever(roleManagementService.updateTenantRole(any())).thenThrow(ConflictException())
            whenever(roleManagementService.deactivateRole(any())).thenThrow(ConflictException())
            whenever(
                roleManagementService.assignPermissionToRole(any()),
            ).thenThrow(ConflictException())
            whenever(
                roleManagementService.removePermissionFromRole(any()),
            ).thenThrow(ConflictException())
            whenever(iamQueryService.getRolePermission(eq(tenantId), any(), any())).thenReturn(
                rolePermissionDetail(tenantId, roleId = roleId),
            )

            listOf(
                HttpMethod.PATCH to "${ApiPaths.ROLES}/$roleId",
                HttpMethod.POST to "${ApiPaths.ROLES}/$roleId/deactivate",
                HttpMethod.POST to "${ApiPaths.ROLES}/$roleId/permissions",
                HttpMethod.DELETE to "${ApiPaths.ROLES}/$roleId/permissions/${uuidV7()}",
            ).forEach { (method, path) ->
                mockMvc
                    .perform(
                        request(method, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(roleMutationPayload(method, path))
                            .with(
                                authentication(
                                    tenantToken(setOf(permissionFor(method, path)), tenantId),
                                ),
                            ),
                    ).andExpect(status().isConflict)
                    .andExpect(jsonPath("$.code").value("conflict"))
            }
        }

        @Test
        fun `role permission grant and removal use server resolved grant data`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val grantId = uuidV7()
            val grant = rolePermissionSummary(grantId, roleId, "permission.view")
            whenever(
                iamQueryService.listRolePermissions(eq(tenantId), eq(roleId), any(), any()),
            ).thenReturn(apiPageOf(listOf(grant), number = 0, size = 20, totalItems = 1))
            whenever(
                iamQueryService.getRolePermission(eq(tenantId), eq(grantId), any()),
            ).thenReturn(rolePermissionDetail(tenantId, grantId, roleId, "permission.view"))

            mockMvc
                .post("${ApiPaths.ROLES}/$roleId/permissions") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AssignPermissionRequest("permission.view"),
                        )
                    with(authentication(tenantToken(setOf("role.assign_permission"), tenantId)))
                }.andExpect {
                    status { isCreated() }
                    header { string("Location", "${ApiPaths.ROLES}/$roleId/permissions/$grantId") }
                }
            mockMvc
                .delete("${ApiPaths.ROLES}/$roleId/permissions/$grantId") {
                    param("permission_code", "client-supplied-and-ignored")
                    with(authentication(tenantToken(setOf("role.remove_permission"), tenantId)))
                }.andExpect { status { isOk() } }

            val commandCaptor =
                argumentCaptor<com.finaxis.platform.iam.application.role.RemovePermissionFromRole>()
            verify(roleManagementService).removePermissionFromRole(commandCaptor.capture())
            kotlin.test.assertEquals("permission.view", commandCaptor.firstValue.permissionCode)
        }

        @Test
        fun `listRolePermissions returns the paginated grants for a role`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val grantId = uuidV7()
            whenever(
                iamQueryService.listRolePermissions(eq(tenantId), eq(roleId), any(), any()),
            ).thenReturn(
                apiPageOf(
                    listOf(rolePermissionSummary(grantId, roleId, "permission.view")),
                    number = 0,
                    size = 25,
                    totalItems = 1,
                ),
            )

            mockMvc
                .get("${ApiPaths.ROLES}/$roleId/permissions") {
                    with(authentication(tenantToken(setOf("role.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(grantId.toString()) }
                    jsonPath("$.items[0].permission_code") { value("permission.view") }
                }
        }

        @Test
        fun `assignPermission surfaces a safe not found when the grant cannot be resolved`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            whenever(
                iamQueryService.listRolePermissions(eq(tenantId), eq(roleId), any(), any()),
            ).thenReturn(apiPageOf(emptyList(), number = 0, size = 100, totalItems = 0))

            mockMvc
                .post("${ApiPaths.ROLES}/$roleId/permissions") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AssignPermissionRequest("permission.view"),
                        )
                    with(authentication(tenantToken(setOf("role.assign_permission"), tenantId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
        }

        @Test
        fun `removePermission rejects a grant id owned by a different role`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val otherRoleId = uuidV7()
            val grantId = uuidV7()
            whenever(iamQueryService.getRolePermission(eq(tenantId), eq(grantId), any()))
                .thenReturn(rolePermissionDetail(tenantId, grantId, otherRoleId, "permission.view"))

            mockMvc
                .delete("${ApiPaths.ROLES}/$roleId/permissions/$grantId") {
                    with(authentication(tenantToken(setOf("role.remove_permission"), tenantId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }

            verify(roleManagementService, org.mockito.kotlin.never())
                .removePermissionFromRole(any())
        }

        @Test
        fun `role assignment assign and revoke use command result and resolved tuple`() {
            val tenantId = uuidV7()
            val assignmentId = uuidV7()
            val userId = uuidV7()
            val roleId = uuidV7()
            val branchId = uuidV7()
            whenever(roleManagementService.assignRoleToUser(any())).thenReturn(
                RoleAssignmentResult(assignmentId, "ACTIVE"),
            )
            whenever(
                iamQueryService.getRoleAssignment(eq(tenantId), eq(assignmentId), any()),
            ).thenReturn(
                roleAssignmentDetail(
                    tenantId,
                    assignmentId,
                    userId,
                    roleId,
                    branchId,
                    "BRANCH",
                    "REVOKED",
                ),
            )

            mockMvc
                .post(ApiPaths.ROLE_ASSIGNMENTS) {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AssignRoleRequest(
                                userId,
                                roleId,
                                RoleAssignmentScopeType.BRANCH,
                                branchId,
                            ),
                        )
                    with(authentication(tenantToken(setOf("user.assign_role"), tenantId)))
                }.andExpect {
                    status { isCreated() }
                    header { string("Location", "${ApiPaths.ROLE_ASSIGNMENTS}/$assignmentId") }
                }
            mockMvc
                .delete("${ApiPaths.ROLE_ASSIGNMENTS}/$assignmentId") {
                    param("user_id", uuidV7().toString())
                    param("role_id", uuidV7().toString())
                    with(authentication(tenantToken(setOf("user.revoke_role"), tenantId)))
                }.andExpect { status { isOk() } }

            val commandCaptor = argumentCaptor<RevokeRoleFromUser>()
            verify(roleManagementService).revokeRoleFromUser(commandCaptor.capture())
            kotlin.test.assertEquals(userId, commandCaptor.firstValue.userId)
            kotlin.test.assertEquals(roleId, commandCaptor.firstValue.roleId)
            kotlin.test.assertEquals(RoleScopeType.BRANCH, commandCaptor.firstValue.scopeType)
            kotlin.test.assertEquals(branchId, commandCaptor.firstValue.branchId)
        }

        @Test
        fun `role mutation requests enforce bean validation`() {
            val tenantId = uuidV7()
            mockMvc
                .post(ApiPaths.ROLES) {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"role_code\":\"lowercase\",\"role_name\":\"\"}"
                    with(authentication(tenantToken(setOf("role.create"), tenantId)))
                }.andExpect { status { isBadRequest() } }
            mockMvc
                .post(ApiPaths.ROLE_ASSIGNMENTS) {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"role_id\":\"${uuidV7()}\",\"scope_type\":\"TENANT\"}"
                    with(authentication(tenantToken(setOf("user.assign_role"), tenantId)))
                }.andExpect { status { isBadRequest() } }
        }

        @Test
        fun `all mutation routes enforce authentication permission and idempotency key shape`() {
            val roleId = uuidV7()
            val grantId = uuidV7()
            val assignmentId = uuidV7()
            mutationRoutes(roleId, grantId, assignmentId).forEach { (method, path, permission) ->
                val payload = roleMutationPayload(method, path)
                mockMvc
                    .perform(
                        request(method, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload),
                    ).andExpect(status().isUnauthorized)
                mockMvc
                    .perform(
                        request(method, path)
                            .header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, "not-a-uuid")
                            .with(authentication(tenantToken(emptySet()))),
                    ).andExpect(status().isBadRequest)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
                mockMvc
                    .perform(
                        request(method, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(authentication(tenantToken(emptySet()))),
                    ).andExpect(status().isForbidden)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
                kotlin.test.assertTrue(permission.isNotBlank())
            }
        }

        private fun detailRequests(
            roleId: UUID,
            assignmentId: UUID,
            permissionId: UUID,
        ) = listOf(
            "${ApiPaths.ROLES}/$roleId" to "role.view",
            "${ApiPaths.ROLE_ASSIGNMENTS}/$assignmentId" to "role_assignment.view",
            "${ApiPaths.PERMISSIONS}/$permissionId" to "permission.view",
        )

        private fun mutationRoutes(
            roleId: UUID,
            grantId: UUID,
            assignmentId: UUID,
        ) = listOf(
            Triple(HttpMethod.POST, ApiPaths.ROLES, "role.create"),
            Triple(HttpMethod.PATCH, "${ApiPaths.ROLES}/$roleId", "role.update"),
            Triple(HttpMethod.POST, "${ApiPaths.ROLES}/$roleId/activate", "role.activate"),
            Triple(HttpMethod.POST, "${ApiPaths.ROLES}/$roleId/deactivate", "role.deactivate"),
            Triple(
                HttpMethod.POST,
                "${ApiPaths.ROLES}/$roleId/permissions",
                "role.assign_permission",
            ),
            Triple(
                HttpMethod.DELETE,
                "${ApiPaths.ROLES}/$roleId/permissions/$grantId",
                "role.remove_permission",
            ),
            Triple(HttpMethod.POST, ApiPaths.ROLE_ASSIGNMENTS, "user.assign_role"),
            Triple(
                HttpMethod.DELETE,
                "${ApiPaths.ROLE_ASSIGNMENTS}/$assignmentId",
                "user.revoke_role",
            ),
        )

        private fun roleMutationPayload(
            method: HttpMethod,
            path: String,
        ): String =
            when {
                method == HttpMethod.PATCH -> {
                    apiJsonCodec.mapper.writeValueAsString(UpdateRoleRequest("Operations", null))
                }

                method == HttpMethod.POST && path.endsWith("/permissions") -> {
                    apiJsonCodec.mapper.writeValueAsString(
                        AssignPermissionRequest("permission.view"),
                    )
                }

                method == HttpMethod.POST && path == ApiPaths.ROLE_ASSIGNMENTS -> {
                    apiJsonCodec.mapper.writeValueAsString(
                        AssignRoleRequest(uuidV7(), uuidV7(), RoleAssignmentScopeType.TENANT, null),
                    )
                }

                method == HttpMethod.POST && path == ApiPaths.ROLES -> {
                    apiJsonCodec.mapper.writeValueAsString(CreateRoleRequest("OPS", "Operations"))
                }

                else -> {
                    ""
                }
            }

        private fun permissionFor(
            method: HttpMethod,
            path: String,
        ): String =
            when {
                method == HttpMethod.PATCH -> "role.update"
                method == HttpMethod.DELETE -> "role.remove_permission"
                path.endsWith("/deactivate") -> "role.deactivate"
                else -> "role.assign_permission"
            }

        private fun roleSummary(roleId: UUID) =
            RoleSummary(roleId, "OPS", "Operations", false, "ACTIVE")

        private fun roleDetail(
            tenantId: UUID,
            roleId: UUID,
        ) = RoleDetail(
            roleId,
            tenantId,
            "OPS",
            "Operations",
            null,
            false,
            "ACTIVE",
            NOW,
            NOW,
        )

        private fun roleAssignmentSummary(assignmentId: UUID) =
            RoleAssignmentSummary(assignmentId, uuidV7(), uuidV7(), null, "TENANT", "ACTIVE")

        private fun roleAssignmentDetail(
            tenantId: UUID,
            assignmentId: UUID,
            userId: UUID = uuidV7(),
            roleId: UUID = uuidV7(),
            branchId: UUID? = null,
            scopeType: String = "TENANT",
            status: String = "ACTIVE",
        ) = RoleAssignmentDetail(
            assignmentId,
            tenantId,
            userId,
            roleId,
            branchId,
            scopeType,
            status,
            NOW,
            uuidV7(),
            null,
            null,
            NOW,
            NOW,
        )

        private fun permissionSummary(permissionId: UUID) =
            PermissionSummary(
                permissionId,
                "permission.view",
                "View permission",
                "IAM",
                "LOW",
                "ACTIVE",
            )

        private fun permissionDetail(permissionId: UUID) =
            PermissionDetail(
                permissionId,
                "permission.view",
                "View permission",
                "IAM",
                null,
                "LOW",
                "ACTIVE",
                NOW,
                NOW,
            )

        private fun rolePermissionSummary(
            grantId: UUID,
            roleId: UUID,
            permissionCode: String,
        ) = RolePermissionSummary(grantId, roleId, uuidV7(), permissionCode, NOW)

        private fun rolePermissionDetail(
            tenantId: UUID,
            grantId: UUID = uuidV7(),
            roleId: UUID = uuidV7(),
            permissionCode: String = "permission.view",
        ) = RolePermissionDetail(
            grantId,
            tenantId,
            roleId,
            uuidV7(),
            permissionCode,
            NOW,
            uuidV7(),
            NOW,
            NOW,
        )

        private fun tenantToken(
            permissions: Set<String>,
            tenantId: UUID = uuidV7(),
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = uuidV7(),
                keycloakSubject = "tenant-user",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "admin@tenant.test",
                fullName = "Tenant Admin",
                permissions = permissions,
            ),
        )

        private companion object {
            val NOW: Instant = Instant.parse("2026-07-24T10:00:00Z")
        }
    }
