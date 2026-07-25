package com.finaxis.platform.iam.adapter.inbound.web

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
        fun `role assignment rejects a branch outside the active branch context`() {
            val tenantId = uuidV7()
            val activeBranchId = uuidV7()
            val requestedBranchId = uuidV7()

            mockMvc
                .post(ApiPaths.ROLE_ASSIGNMENTS) {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AssignRoleRequest(
                                uuidV7(),
                                uuidV7(),
                                RoleAssignmentScopeType.BRANCH,
                                requestedBranchId,
                            ),
                        )
                    with(
                        authentication(
                            tenantToken(
                                setOf("user.assign_role"),
                                tenantId,
                                activeBranchId,
                            ),
                        ),
                    )
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }

            verify(roleManagementService, org.mockito.kotlin.never()).assignRoleToUser(any())
        }
    }
