package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ForbiddenOperationException
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
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ActivateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CloseBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SubmitBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendBranchRequest
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.BranchDraftResult
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.query.BranchDetail
import com.finaxis.platform.lifecycle.application.query.BranchSummary
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
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

@WebMvcTest(controllers = [BranchController::class], useDefaultFilters = false)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    BranchControllerTests.TestSecurityConfiguration::class,
    BranchController::class,
)
class BranchControllerTests
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
        private lateinit var foundationQueryService: FoundationQueryService

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `createDraft succeeds with active tenant context`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            whenever(branchProvisioningService.createDraft(any()))
                .thenReturn(BranchDraftResult(branchId, BranchLifecycleState.DRAFT))

            val request =
                CreateBranchRequest(
                    branchCode = "HQ-01",
                    branchName = "Headquarters",
                    branchType = "HEAD_OFFICE",
                    timezone = "Africa/Nairobi",
                )

            val idempotencyKey = UUID.randomUUID().toString()

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context
                            .TenantContext(tenantId),
                ),
            ) {
                mockMvc
                    .post(ApiPaths.BRANCHES) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(request)
                        with(authentication(tenantToken(setOf("branch.create"), tenantId)))
                    }.andExpect {
                        status { isCreated() }
                        header { string("Location", "${ApiPaths.BRANCHES}/$branchId") }
                        jsonPath("$.branch_id") { value(branchId.toString()) }
                        jsonPath("$.status") { value("DRAFT") }
                    }
            }
        }

        @Test
        fun `createDraft rejects platform context`() {
            val request =
                CreateBranchRequest(
                    branchCode = "HQ-01",
                    branchName = "Headquarters",
                    branchType = "HEAD_OFFICE",
                    timezone = "Africa/Nairobi",
                )

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context.TenantContext(
                            PlatformOrganisation.ID,
                        ),
                ),
            ) {
                mockMvc
                    .post(ApiPaths.BRANCHES) {
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(request)
                        with(authentication(platformToken(setOf("branch.create"))))
                    }.andExpect {
                        status { isForbidden() }
                        jsonPath(
                            "$.code",
                        ) {
                            value(
                                "Branch operations are restricted to non-platform tenant context.",
                            )
                        }
                    }
            }
        }

        @Test
        fun `getBranch returns branch detail when context matches`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            val detail =
                BranchDetail(
                    id = branchId,
                    organisationId = tenantId,
                    branchCode = "HQ-01",
                    branchName = "Headquarters",
                    branchType = "HEAD_OFFICE",
                    parentBranchId = null,
                    status = "ACTIVE",
                    timezone = "Africa/Nairobi",
                    addressJson = "{}",
                    openedOn = null,
                    closedOn = null,
                    statusReason = null,
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                )
            whenever(
                foundationQueryService.getBranch(eq(tenantId), eq(branchId), any()),
            ).thenReturn(detail)

            mockMvc
                .get("${ApiPaths.BRANCHES}/$branchId") {
                    with(authentication(tenantToken(setOf("branch.view"), tenantId, branchId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                    jsonPath("$.branch_code") { value("HQ-01") }
                    jsonPath("$.status") { value("ACTIVE") }
                }
        }

        @Test
        fun `searchBranches returns a bounded page`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            whenever(foundationQueryService.searchBranches(eq(tenantId), any(), any())).thenReturn(
                apiPageOf(
                    listOf(
                        BranchSummary(
                            branchId,
                            tenantId,
                            "HQ-01",
                            "Headquarters",
                            "HEAD_OFFICE",
                            "ACTIVE",
                            Instant.parse("2026-07-18T10:00:00Z"),
                        ),
                    ),
                    number = 1,
                    size = 10,
                    totalItems = 11,
                ),
            )

            mockMvc
                .get(ApiPaths.BRANCHES) {
                    param("page", "1")
                    param("size", "10")
                    param("q", "head")
                    with(authentication(tenantToken(setOf("branch.view"), tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(branchId.toString()) }
                    jsonPath("$.page.number") { value(1) }
                    jsonPath("$.page.size") { value(10) }
                }
        }

        @Test
        fun `submit returns updated branch detail`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "PENDING_APPROVAL")

            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/submit") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            SubmitBranchRequest(reason = "Ready for approval"),
                        )
                    with(authentication(tenantToken(setOf("branch.create"), tenantId, branchId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                    jsonPath("$.status") { value("PENDING_APPROVAL") }
                }

            verify(branchProvisioningService).submitForApproval(any())
        }

        @Test
        fun `activate returns updated branch detail`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "ACTIVE")

            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/activate") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            ActivateBranchRequest(reason = "Operational setup complete"),
                        )
                    with(authentication(tenantToken(setOf("branch.activate"), tenantId, branchId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                    jsonPath("$.status") { value("ACTIVE") }
                }

            verify(branchProvisioningService).activate(any())
            verify(permissionGuard).requireBranchPermission(
                org.mockito.kotlin.any(),
                eq(tenantId),
                eq(branchId),
                eq("branch.activate"),
            )
        }

        @Test
        fun `suspend returns updated branch detail`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "SUSPENDED")

            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/suspend") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            SuspendBranchRequest(reason = "Temporary audit closure"),
                        )
                    with(authentication(tenantToken(setOf("branch.suspend"), tenantId, branchId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                    jsonPath("$.status") { value("SUSPENDED") }
                }

            verify(branchProvisioningService).suspend(any())
        }

        @Test
        fun `reactivate returns updated branch detail`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "ACTIVE")

            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/reactivate") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            ReactivateBranchRequest(reason = "Audit complete"),
                        )
                    with(
                        authentication(
                            tenantToken(setOf("branch.reactivate"), tenantId, branchId),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                    jsonPath("$.status") { value("ACTIVE") }
                }

            verify(branchProvisioningService).reactivate(any())
        }

        @Test
        fun `branch maker cannot activate while distinct checker succeeds`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            val makerId = uuidV7()
            val checkerId = uuidV7()
            stubBranchDetail(tenantId, branchId, "ACTIVE")
            doAnswer { invocation ->
                val command = invocation.getArgument<ActivateBranchCommand>(0)
                if (command.actorId == makerId) {
                    throw ForbiddenOperationException()
                }
                Unit
            }.whenever(branchProvisioningService).activate(any())

            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/activate") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                    with(
                        authentication(
                            tenantToken(
                                setOf("branch.activate"),
                                tenantId,
                                branchId,
                                makerId,
                            ),
                        ),
                    )
                }.andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value("forbidden") }
                }

            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/activate") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                    with(
                        authentication(
                            tenantToken(
                                setOf("branch.activate"),
                                tenantId,
                                branchId,
                                checkerId,
                            ),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(branchId.toString()) }
                    jsonPath("$.status") { value("ACTIVE") }
                }
        }

        @Test
        fun `getBranch rejects mismatched branch context`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            val otherBranchId = uuidV7()

            mockMvc
                .get("${ApiPaths.BRANCHES}/$branchId") {
                    with(authentication(tenantToken(setOf("branch.view"), tenantId, otherBranchId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
        }

        @Test
        fun `close branch requires valid reason`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context
                            .TenantContext(tenantId),
                ),
            ) {
                mockMvc
                    .post("${ApiPaths.BRANCHES}/$branchId/close") {
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                CloseBranchRequest(reason = "ab"),
                            )
                        with(authentication(tenantToken(setOf("branch.close"), tenantId, branchId)))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }
        }

        @Test
        fun `branch mutations generate or validate idempotency keys before authentication`() {
            val branchId = uuidV7()
            val branchRoute = "${ApiPaths.BRANCHES}/$branchId"
            val routes =
                listOf(
                    HttpMethod.POST to ApiPaths.BRANCHES,
                    HttpMethod.POST to "$branchRoute/submit",
                    HttpMethod.POST to "$branchRoute/activate",
                    HttpMethod.POST to "$branchRoute/suspend",
                    HttpMethod.POST to "$branchRoute/reactivate",
                    HttpMethod.POST to "$branchRoute/close",
                )

            routes.forEach { (method, path) ->
                val payload = branchMutationPayload(path)
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

        private fun stubBranchDetail(
            tenantId: UUID,
            branchId: UUID,
            lifecycleStatus: String,
        ) {
            whenever(
                foundationQueryService.getBranch(eq(tenantId), eq(branchId), any()),
            ).thenReturn(
                BranchDetail(
                    id = branchId,
                    organisationId = tenantId,
                    branchCode = "HQ-01",
                    branchName = "Headquarters",
                    branchType = "HEAD_OFFICE",
                    parentBranchId = null,
                    status = lifecycleStatus,
                    timezone = "Africa/Nairobi",
                    addressJson = "{}",
                    openedOn = null,
                    closedOn = null,
                    statusReason = null,
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                ),
            )
        }

        private fun branchMutationPayload(path: String): String =
            when {
                path == ApiPaths.BRANCHES -> {
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateBranchRequest(
                            branchCode = "HQ-01",
                            branchName = "Headquarters",
                            branchType = "HEAD_OFFICE",
                            timezone = "Africa/Nairobi",
                        ),
                    )
                }

                path.endsWith("/suspend") || path.endsWith("/close") -> {
                    "{\"reason\":\"Valid reason\"}"
                }

                path.endsWith("/submit") ||
                    path.endsWith("/activate") ||
                    path.endsWith("/reactivate") -> {
                    "{}"
                }

                else -> {
                    ""
                }
            }

        private fun tenantToken(
            permissions: Set<String>,
            tenantId: UUID = uuidV7(),
            branchId: UUID? = null,
            userId: UUID = uuidV7(),
        ): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "tenant-user",
                    organisationId = tenantId,
                    membershipId = uuidV7(),
                    branchId = branchId,
                    email = "admin@tenant.test",
                    fullName = "Tenant Admin",
                    permissions = permissions,
                ),
            )

        private fun platformToken(permissions: Set<String>): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = uuidV7(),
                    keycloakSubject = "platform-user",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    email = "admin@platform.test",
                    fullName = "Platform Admin",
                    permissions = permissions,
                ),
            )
    }
