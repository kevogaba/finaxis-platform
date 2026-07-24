package com.finaxis.platform.lifecycle.adapter.inbound.web

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
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AssignBranchRequest
import com.finaxis.platform.lifecycle.application.AssignUserToBranchCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.RevokeUserBranchAssignmentCommand
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentSummary
import com.finaxis.platform.lifecycle.application.query.LifecycleIamReadService
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
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
    controllers = [BranchAssignmentController::class],
    properties = [EXCLUDE_MODULITH_RUNTIME],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    BranchAssignmentControllerTests.TestSecurityConfiguration::class,
    BranchAssignmentController::class,
)
class BranchAssignmentControllerTests
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
        private lateinit var lifecycleIamReadService: LifecycleIamReadService

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `searchBranchAssignments returns a bounded page`() {
            val tenantId = uuidV7()
            val assignmentId = uuidV7()
            whenever(
                lifecycleIamReadService.searchBranchAssignments(eq(tenantId), any(), any()),
            ).thenReturn(
                apiPageOf(
                    listOf(
                        LifecycleBranchAssignmentSummary(
                            assignmentId,
                            uuidV7(),
                            uuidV7(),
                            "OPERATE",
                            "ACTIVE",
                        ),
                    ),
                    number = 1,
                    size = 10,
                    totalItems = 11,
                ),
            )

            mockMvc
                .get(ApiPaths.BRANCH_ASSIGNMENTS) {
                    param("page", "1")
                    param("size", "10")
                    param("assignment_type", "OPERATE")
                    param("status", "ACTIVE")
                    with(authentication(tenantToken(setOf("branch_assignment.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(assignmentId.toString()) }
                    jsonPath("$.page.number") { value(1) }
                    jsonPath("$.page.size") { value(10) }
                }
        }

        @Test
        fun `getBranchAssignment returns assignment detail`() {
            val tenantId = uuidV7()
            val assignmentId = uuidV7()
            stubAssignmentDetail(tenantId, assignmentId)

            mockMvc
                .get("${ApiPaths.BRANCH_ASSIGNMENTS}/$assignmentId") {
                    with(authentication(tenantToken(setOf("branch_assignment.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(assignmentId.toString()) }
                    jsonPath("$.status") { value("ACTIVE") }
                }
        }

        @Test
        fun `getBranchAssignment returns safe 404 for a cross tenant assignment`() {
            val tenantId = uuidV7()
            val assignmentId = uuidV7()
            whenever(
                lifecycleIamReadService.getBranchAssignment(eq(tenantId), eq(assignmentId), any()),
            ).thenThrow(ResourceNotFoundException(safeDetail = "Branch assignment not found"))

            mockMvc
                .get("${ApiPaths.BRANCH_ASSIGNMENTS}/$assignmentId") {
                    with(authentication(tenantToken(setOf("branch_assignment.view"), tenantId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
        }

        @Test
        fun `assign creates an assignment and resolves its generated identifier`() {
            val tenantId = uuidV7()
            val userId = uuidV7()
            val branchId = uuidV7()
            val assignmentId = uuidV7()
            val request = AssignBranchRequest(userId, branchId, BranchAssignmentType.OPERATE)
            whenever(
                lifecycleIamReadService.searchBranchAssignments(eq(tenantId), any(), any()),
            ).thenReturn(
                apiPageOf(
                    listOf(
                        LifecycleBranchAssignmentSummary(
                            assignmentId,
                            userId,
                            branchId,
                            "OPERATE",
                            "ACTIVE",
                        ),
                    ),
                    number = 0,
                    size = 1,
                    totalItems = 1,
                ),
            )

            mockMvc
                .post(ApiPaths.BRANCH_ASSIGNMENTS) {
                    contentType = MediaType.APPLICATION_JSON
                    content = apiJsonCodec.mapper.writeValueAsString(request)
                    with(authentication(tenantToken(setOf("user.assign_branch"), tenantId)))
                }.andExpect {
                    status { isCreated() }
                    header { string("Location", "${ApiPaths.BRANCH_ASSIGNMENTS}/$assignmentId") }
                    jsonPath("$.id") { value(assignmentId.toString()) }
                }

            verify(branchProvisioningService).assignUser(any<AssignUserToBranchCommand>())
        }

        @Test
        fun `assign rejects a missing user identifier`() {
            val tenantId = uuidV7()

            mockMvc
                .post(ApiPaths.BRANCH_ASSIGNMENTS) {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{\"branch_id\":\"${uuidV7()}\",\"assignment_type\":\"OPERATE\"}"
                    with(authentication(tenantToken(setOf("user.assign_branch"), tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_json") }
                }
        }

        @Test
        fun `revoke resolves the assignment tuple instead of trusting client supplied values`() {
            val tenantId = uuidV7()
            val assignmentId = uuidV7()
            val resolvedUserId = uuidV7()
            val resolvedBranchId = uuidV7()
            val detail =
                assignmentDetail(
                    tenantId = tenantId,
                    assignmentId = assignmentId,
                    userId = resolvedUserId,
                    branchId = resolvedBranchId,
                    assignmentType = "APPROVE",
                    status = "REVOKED",
                )
            whenever(
                lifecycleIamReadService.getBranchAssignment(eq(tenantId), eq(assignmentId), any()),
            ).thenReturn(detail)

            mockMvc
                .delete("${ApiPaths.BRANCH_ASSIGNMENTS}/$assignmentId") {
                    param("user_id", uuidV7().toString())
                    param("branch_id", uuidV7().toString())
                    param("assignment_type", "VIEW")
                    with(authentication(tenantToken(setOf("user.revoke_branch"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(assignmentId.toString()) }
                    jsonPath("$.status") { value("REVOKED") }
                }

            val commandCaptor = argumentCaptor<RevokeUserBranchAssignmentCommand>()
            verify(branchProvisioningService).revokeUserAssignment(commandCaptor.capture())
            kotlin.test.assertEquals(resolvedUserId, commandCaptor.firstValue.userId)
            kotlin.test.assertEquals(resolvedBranchId, commandCaptor.firstValue.branchId)
            kotlin.test.assertEquals(
                BranchAssignmentType.APPROVE,
                commandCaptor.firstValue.assignmentType,
            )
        }

        @Test
        fun `branch assignment mutations validate keys before authentication`() {
            val assignmentId = uuidV7()
            val routes =
                listOf(
                    HttpMethod.POST to
                        ApiPaths.BRANCH_ASSIGNMENTS,
                    HttpMethod.DELETE to "${ApiPaths.BRANCH_ASSIGNMENTS}/$assignmentId",
                )

            routes.forEach { (method, path) ->
                val payload = branchAssignmentMutationPayload(method)
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
            }
        }

        private fun stubAssignmentDetail(
            tenantId: UUID,
            assignmentId: UUID,
        ) {
            whenever(
                lifecycleIamReadService.getBranchAssignment(eq(tenantId), eq(assignmentId), any()),
            ).thenReturn(assignmentDetail(tenantId, assignmentId))
        }

        private fun assignmentDetail(
            tenantId: UUID,
            assignmentId: UUID,
            userId: UUID = uuidV7(),
            branchId: UUID = uuidV7(),
            assignmentType: String = "OPERATE",
            status: String = "ACTIVE",
        ) = LifecycleBranchAssignmentDetail(
            id = assignmentId,
            organisationId = tenantId,
            userId = userId,
            branchId = branchId,
            assignmentType = assignmentType,
            status = status,
            assignedAt = Instant.parse("2026-07-18T10:00:00Z"),
            assignedBy = uuidV7(),
            revokedAt = null,
            revokedBy = null,
            createdAt = Instant.parse("2026-07-18T10:00:00Z"),
            updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
        )

        private fun branchAssignmentMutationPayload(method: HttpMethod): String =
            if (method == HttpMethod.POST) {
                apiJsonCodec.mapper.writeValueAsString(
                    AssignBranchRequest(uuidV7(), uuidV7(), BranchAssignmentType.OPERATE),
                )
            } else {
                ""
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
