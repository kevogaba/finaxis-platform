package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.versioning.ApiPaths
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [
        RoleController::class,
        RoleAssignmentController::class,
        PermissionController::class,
    ],
    properties = [FOUNDATION_IAM_EXCLUDE_MODULITH_RUNTIME],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    FoundationIamTestSecurityConfiguration::class,
    RoleController::class,
    RoleAssignmentController::class,
    PermissionController::class,
)
class FoundationIamControllerTests
    @Autowired
    constructor(
        mockMvc: MockMvc,
        apiJsonCodec: ApiJsonCodec,
    ) : FoundationIamControllerTestSupport(mockMvc, apiJsonCodec) {
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
            Triple(org.springframework.http.HttpMethod.POST, ApiPaths.ROLES, "role.create"),
            Triple(
                org.springframework.http.HttpMethod.PATCH,
                "${ApiPaths.ROLES}/$roleId",
                "role.update",
            ),
            Triple(
                org.springframework.http.HttpMethod.POST,
                "${ApiPaths.ROLES}/$roleId/activate",
                "role.activate",
            ),
            Triple(
                org.springframework.http.HttpMethod.POST,
                "${ApiPaths.ROLES}/$roleId/deactivate",
                "role.deactivate",
            ),
            Triple(
                org.springframework.http.HttpMethod.POST,
                "${ApiPaths.ROLES}/$roleId/permissions",
                "role.assign_permission",
            ),
            Triple(
                org.springframework.http.HttpMethod.DELETE,
                "${ApiPaths.ROLES}/$roleId/permissions/$grantId",
                "role.remove_permission",
            ),
            Triple(
                org.springframework.http.HttpMethod.POST,
                ApiPaths.ROLE_ASSIGNMENTS,
                "user.assign_role",
            ),
            Triple(
                org.springframework.http.HttpMethod.DELETE,
                "${ApiPaths.ROLE_ASSIGNMENTS}/$assignmentId",
                "user.revoke_role",
            ),
        )
    }
