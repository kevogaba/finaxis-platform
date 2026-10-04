package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.adapter.inbound.web.dto.AssignRoleRequest
import com.finaxis.platform.iam.application.role.RevokeRoleFromUser
import com.finaxis.platform.iam.application.role.RoleAssignmentResult
import com.finaxis.platform.iam.application.role.RoleScopeType
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post

@WebMvcTest(
    controllers = [RoleAssignmentController::class],
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
    RoleAssignmentController::class,
)
class RoleAssignmentControllerTests
    @Autowired
    constructor(
        mockMvc: MockMvc,
        apiJsonCodec: ApiJsonCodec,
    ) : FoundationIamControllerTestSupport(mockMvc, apiJsonCodec) {
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
                    with(
                        authentication(
                            tenantToken(setOf("user.assign_role"), tenantId, branchId),
                        ),
                    )
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
        fun `role assignment assigns a branch scope role outside the selected branch`() {
            val tenantId = uuidV7()
            val selectedBranchId = uuidV7()
            val targetBranchId = uuidV7()
            val assignmentId = uuidV7()
            val userId = uuidV7()
            val roleId = uuidV7()
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
                    targetBranchId,
                    "BRANCH",
                    "ACTIVE",
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
                                targetBranchId,
                            ),
                        )
                    with(
                        authentication(
                            tenantToken(setOf("user.assign_role"), tenantId, selectedBranchId),
                        ),
                    )
                }.andExpect {
                    status { isCreated() }
                }

            // Authorization is evaluated in the target branch's scope, not the selected one.
            verify(permissionGuard).requireBranchPermission(
                any(),
                eq(tenantId),
                eq(targetBranchId),
                eq("user.assign_role"),
            )
            verify(roleManagementService).assignRoleToUser(any())
        }

        @Test
        fun `role assignment revokes a branch scope role outside the selected branch`() {
            val tenantId = uuidV7()
            val selectedBranchId = uuidV7()
            val targetBranchId = uuidV7()
            val assignmentId = uuidV7()
            whenever(
                iamQueryService.getRoleAssignment(eq(tenantId), eq(assignmentId), any()),
            ).thenReturn(
                roleAssignmentDetail(
                    tenantId,
                    assignmentId,
                    uuidV7(),
                    uuidV7(),
                    targetBranchId,
                    "BRANCH",
                    "REVOKED",
                ),
            )

            mockMvc
                .delete("${ApiPaths.ROLE_ASSIGNMENTS}/$assignmentId") {
                    with(
                        authentication(
                            tenantToken(setOf("user.revoke_role"), tenantId, selectedBranchId),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                }

            val commandCaptor = argumentCaptor<RevokeRoleFromUser>()
            verify(roleManagementService).revokeRoleFromUser(commandCaptor.capture())
            kotlin.test.assertEquals(targetBranchId, commandCaptor.firstValue.branchId)
            verify(permissionGuard).requireBranchPermission(
                any(),
                eq(tenantId),
                eq(targetBranchId),
                eq("user.revoke_role"),
            )
        }

        @Test
        fun `assigning a branch scope role without a branch is a 400 naming branch_id`() {
            val tenantId = uuidV7()

            mockMvc
                .post(ApiPaths.ROLE_ASSIGNMENTS) {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"user_id":"${uuidV7()}","role_id":"${uuidV7()}",""" +
                        """"scope_type":"BRANCH"}"""
                    with(authentication(tenantToken(setOf("user.assign_role"), tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                    jsonPath("$.code") { value("validation_failed") }
                    jsonPath("$.violations.length()") { value(1) }
                    jsonPath("$.violations[0].field") { value("branch_id") }
                    jsonPath("$.violations[0].message") {
                        value("A branch is required when the scope type is BRANCH.")
                    }
                }

            verify(roleManagementService, never()).assignRoleToUser(any())
        }

        @Test
        fun `a tenant scope role with a branch is left to the application layer`() {
            val tenantId = uuidV7()
            whenever(roleManagementService.assignRoleToUser(any())).thenThrow(
                InvalidOperationException(),
            )

            mockMvc
                .post(ApiPaths.ROLE_ASSIGNMENTS) {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"user_id":"${uuidV7()}","role_id":"${uuidV7()}",""" +
                        """"scope_type":"TENANT","branch_id":"${uuidV7()}"}"""
                    with(authentication(tenantToken(setOf("user.assign_role"), tenantId)))
                }.andExpect {
                    status { isUnprocessableContent() }
                    jsonPath("$.code") { value("invalid_operation") }
                }
        }
    }
