package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidRequestException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignPermissionRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.CreateRoleRequest
import com.finaxis.platform.iam.adapter.inbound.web.dto.UpdateRoleRequest
import com.finaxis.platform.iam.application.role.RoleResult
import com.finaxis.platform.iam.domain.RoleStatus
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [RoleController::class],
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
)
class RoleControllerTests
    @Autowired
    constructor(
        mockMvc: MockMvc,
        apiJsonCodec: ApiJsonCodec,
    ) : FoundationIamControllerTestSupport(mockMvc, apiJsonCodec) {
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
        fun `role list and detail publish the missing view permissions`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val compliantId = uuidV7()
            whenever(iamQueryService.searchRoles(eq(tenantId), any(), any())).thenReturn(
                apiPageOf(
                    listOf(
                        roleSummary(roleId, listOf("branch.view", "membership.view")),
                        roleSummary(compliantId),
                    ),
                    number = 0,
                    size = 25,
                    totalItems = 2,
                ),
            )
            whenever(iamQueryService.getRole(eq(tenantId), eq(roleId), any())).thenReturn(
                roleDetail(tenantId, roleId, listOf("branch.view")),
            )

            mockMvc
                .get(ApiPaths.ROLES) {
                    with(authentication(tenantToken(setOf("role.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].missing_view_permissions[0]") { value("branch.view") }
                    jsonPath("$.items[0].missing_view_permissions[1]") { value("membership.view") }
                    jsonPath("$.items[1].missing_view_permissions") { isEmpty() }
                }
            mockMvc
                .get("${ApiPaths.ROLES}/$roleId") {
                    with(authentication(tenantToken(setOf("role.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.missing_view_permissions[0]") { value("branch.view") }
                    jsonPath("$.missing_view_permissions.length()") { value(1) }
                }
        }

        @Test
        fun `a refused role composition answers validation failed naming the views`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val grantId = uuidV7()
            whenever(roleManagementService.assignPermissionToRole(any())).thenThrow(
                InvalidRequestException(
                    "validation_failed",
                    "Missing view permissions: branch.view (required by branch.suspend).",
                ),
            )
            whenever(roleManagementService.removePermissionFromRole(any())).thenThrow(
                InvalidRequestException(
                    "validation_failed",
                    "Permission branch.view is required by held permissions: branch.suspend.",
                ),
            )

            mockMvc
                .post("${ApiPaths.ROLES}/$roleId/permissions") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AssignPermissionRequest("branch.suspend"),
                        )
                    with(authentication(tenantToken(setOf("role.assign_permission"), tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                    jsonPath("$.detail") {
                        value("Missing view permissions: branch.view (required by branch.suspend).")
                    }
                }
            mockMvc
                .delete("${ApiPaths.ROLES}/$roleId/permissions/$grantId") {
                    with(authentication(tenantToken(setOf("role.remove_permission"), tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                    jsonPath("$.detail") {
                        value(
                            "Permission branch.view is required by held permissions: " +
                                "branch.suspend.",
                        )
                    }
                }
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
            // The grant is read back by its key (role and permission), not out of a page.
            whenever(
                iamQueryService.getRolePermissionByCode(
                    eq(tenantId),
                    eq(roleId),
                    eq("permission.view"),
                    any(),
                ),
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
            // The route passes the grant's id and role; the service resolves its permission code
            // after authorising, so a client-supplied code is never trusted.
            kotlin.test.assertEquals(grantId, commandCaptor.firstValue.rolePermissionId)
            kotlin.test.assertEquals(roleId, commandCaptor.firstValue.roleId)
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
                iamQueryService.getRolePermissionByCode(
                    eq(tenantId),
                    eq(roleId),
                    eq("permission.view"),
                    any(),
                ),
            ).thenThrow(ResourceNotFoundException(safeDetail = "Role permission not found"))

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
        fun `removePermission answers not found for a grant id the role does not own`() {
            val tenantId = uuidV7()
            val roleId = uuidV7()
            val grantId = uuidV7()
            // Ownership is the service's rule, applied after it authorises the caller.
            whenever(roleManagementService.removePermissionFromRole(any()))
                .thenThrow(ResourceNotFoundException(safeDetail = "Role permission not found"))

            mockMvc
                .delete("${ApiPaths.ROLES}/$roleId/permissions/$grantId") {
                    with(authentication(tenantToken(setOf("role.remove_permission"), tenantId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }

            val commandCaptor =
                argumentCaptor<com.finaxis.platform.iam.application.role.RemovePermissionFromRole>()
            verify(roleManagementService).removePermissionFromRole(commandCaptor.capture())
            kotlin.test.assertEquals(roleId, commandCaptor.firstValue.roleId)
            kotlin.test.assertEquals(grantId, commandCaptor.firstValue.rolePermissionId)
        }
    }
