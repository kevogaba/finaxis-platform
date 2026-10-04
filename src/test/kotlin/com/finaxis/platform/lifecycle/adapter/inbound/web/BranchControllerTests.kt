package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
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
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ActivateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CloseBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SubmitBranchRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendBranchRequest
import com.finaxis.platform.lifecycle.application.ActingScope
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.BranchDraftResult
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.ReactivateBranchCommand
import com.finaxis.platform.lifecycle.application.ReturnBranchCommand
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.UpdateBranchCommand
import com.finaxis.platform.lifecycle.application.query.BranchDetail
import com.finaxis.platform.lifecycle.application.query.BranchSummary
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val REASON_BODY = "{\"reason\":\"Valid reason\"}"

@Suppress("LargeClass") // One endpoint-per-test suite for one controller; splitting buys nothing.
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
                    with(authentication(tenantToken(setOf("branch.approve"), tenantId, branchId)))
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
                eq("branch.approve"),
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
                                setOf("branch.approve"),
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
                                setOf("branch.approve"),
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
        fun `getBranch administers a branch other than the selected one`() {
            val tenantId = uuidV7()
            val targetBranchId = uuidV7()
            val selectedBranchId = uuidV7()
            stubBranchDetail(tenantId, targetBranchId, "ACTIVE")

            mockMvc
                .get("${ApiPaths.BRANCHES}/$targetBranchId") {
                    with(
                        authentication(
                            tenantToken(setOf("branch.view"), tenantId, selectedBranchId),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(targetBranchId.toString()) }
                }

            val callerCaptor = org.mockito.kotlin.argumentCaptor<TenantCaller>()
            verify(foundationQueryService)
                .getBranch(eq(tenantId), eq(targetBranchId), callerCaptor.capture())
            assertEquals(selectedBranchId, callerCaptor.firstValue.activeBranchId)
        }

        @Test
        fun `submit administers a branch other than the selected one`() {
            assertAdministersOtherBranch("submit", "branch.create", "{}") { targetBranchId ->
                verify(branchProvisioningService).submitForApproval(
                    argThat {
                        branchId ==
                            targetBranchId
                    },
                )
            }
        }

        @Test
        fun `activate administers a branch other than the selected one`() {
            assertAdministersOtherBranch("activate", "branch.approve", "{}") { targetBranchId ->
                verify(branchProvisioningService).activate(argThat { branchId == targetBranchId })
            }
        }

        @Test
        fun `suspend administers a branch other than the selected one`() {
            assertAdministersOtherBranch(
                "suspend",
                "branch.suspend",
                REASON_BODY,
            ) { targetBranchId ->
                verify(branchProvisioningService).suspend(argThat { branchId == targetBranchId })
            }
        }

        @Test
        fun `reactivate administers a branch other than the selected one`() {
            assertAdministersOtherBranch(
                "reactivate",
                "branch.reactivate",
                "{}",
            ) { targetBranchId ->
                verify(branchProvisioningService).reactivate(argThat { branchId == targetBranchId })
            }
        }

        @Test
        fun `close administers a branch other than the selected one`() {
            assertAdministersOtherBranch("close", "branch.close", REASON_BODY) { targetBranchId ->
                verify(branchProvisioningService).close(argThat { branchId == targetBranchId })
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
                    HttpMethod.POST to "$branchRoute/return",
                    HttpMethod.POST to "$branchRoute/suspend",
                    HttpMethod.POST to "$branchRoute/reactivate",
                    HttpMethod.POST to "$branchRoute/close",
                    HttpMethod.PATCH to branchRoute,
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

        @Test
        fun `update changes the name only and leaves the parent alone`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubUpdateResponse(tenantId, branchId, "ACTIVE")

            patch(branchId, tenantId, "{\"branch_name\":\"Riverside\"}").andExpect {
                status { isOk() }
                jsonPath("$.id") { value(branchId.toString()) }
            }

            val command = captureUpdate()
            assertEquals(tenantId, command.organisationId)
            assertEquals(branchId, command.branchId)
            assertEquals("Riverside", command.branchName)
            assertEquals(false, command.changesParent)
            assertEquals(null, command.timezone)
            assertEquals(null, command.address)
        }

        @Test
        fun `update changes timezone and address and a parent`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            val parentId = uuidV7()
            stubUpdateResponse(tenantId, branchId, "DRAFT")

            patch(
                branchId,
                tenantId,
                "{\"timezone\":\"Africa/Kampala\",\"parent_branch_id\":\"$parentId\"," +
                    "\"address\":{\"city\":\"Kampala\"}}",
            ).andExpect { status { isOk() } }

            val command = captureUpdate()
            assertEquals(null, command.branchName)
            assertEquals("Africa/Kampala", command.timezone)
            assertEquals(true, command.changesParent)
            assertEquals(parentId, command.parentBranchId)
            assertEquals(mapOf("city" to "Kampala"), command.address)
        }

        @Test
        fun `update treats an explicit null parent as detaching the branch`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubUpdateResponse(tenantId, branchId, "ACTIVE")

            patch(branchId, tenantId, "{\"parent_branch_id\":null}").andExpect {
                status { isOk() }
            }

            val command = captureUpdate()
            assertEquals(true, command.changesParent)
            assertEquals(null, command.parentBranchId)
        }

        @Test
        fun `update returns the stored address and dates in the response`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubUpdateResponse(tenantId, branchId, "ACTIVE")

            patch(branchId, tenantId, "{\"branch_name\":\"Riverside\"}").andExpect {
                jsonPath("$.status") { value("ACTIVE") }
                jsonPath("$.timezone") { value("Africa/Nairobi") }
                jsonPath("$.address") { isMap() }
                jsonPath("$.opened_on") { value(null) }
            }
        }

        @Test
        fun `update rejects an empty object and a missing body`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()

            patch(branchId, tenantId, "{}").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
            }
            patch(branchId, tenantId, null).andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("invalid_json") }
            }

            verify(branchProvisioningService, org.mockito.kotlin.never()).update(any())
        }

        @Test
        fun `update rejects invalid fields before the service is reached`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            val bodies =
                listOf(
                    "{\"branch_name\":\"   \"}",
                    "{\"branch_name\":\"A\"}",
                    "{\"branch_name\":\"${"x".repeat(101)}\"}",
                    "{\"timezone\":\"\"}",
                )
            bodies.forEach { body ->
                patch(branchId, tenantId, body).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
            }
            // The code and type are not updatable, and unknown fields are refused outright.
            listOf(
                "{\"branch_code\":\"NEW-CODE\"}",
                "{\"branch_type\":\"OPERATIONAL\"}",
                "{\"parent_branch_id\":\"not-a-uuid\"}",
            ).forEach { body ->
                patch(branchId, tenantId, body).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_json") }
                }
            }

            verify(branchProvisioningService, org.mockito.kotlin.never()).update(any())
        }

        @Test
        fun `update accepts a name with a line break as create does and rejects a blank one`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubUpdateResponse(tenantId, branchId, "ACTIVE")

            patch(branchId, tenantId, "{\"branch_name\":\"North\\nWing\"}")
                .andExpect { status { isOk() } }
            patch(branchId, tenantId, "{\"timezone\":\"Africa/Nairobi\\n\"}")
                .andExpect { status { isOk() } }
            patch(branchId, tenantId, "{\"branch_name\":\" \\n \\t \"}").andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("validation_failed") }
            }
        }

        @Test
        fun `update is forbidden without branch update`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()

            patch(branchId, tenantId, "{\"branch_name\":\"Riverside\"}", setOf("branch.view"))
                .andExpect { status { isForbidden() } }

            verify(branchProvisioningService, org.mockito.kotlin.never()).update(any())
        }

        @Test
        fun `update is forbidden to a maker who holds only branch create`() {
            // branch.create no longer reaches PATCH (#203): a maker-only role must not be able to
            // edit a live branch with no checker.
            val tenantId = uuidV7()
            val branchId = uuidV7()

            patch(branchId, tenantId, "{\"branch_name\":\"Riverside\"}", setOf("branch.create"))
                .andExpect { status { isForbidden() } }

            verify(branchProvisioningService, org.mockito.kotlin.never()).update(any())
        }

        @Test
        fun `update surfaces the service refusals as problems`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            val body = "{\"branch_name\":\"Riverside\"}"

            refuseUpdateWith(ConflictException())
            patch(branchId, tenantId, body).andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("conflict") }
            }
            refuseUpdateWith(ResourceNotFoundException())
            patch(branchId, tenantId, body).andExpect { status { isNotFound() } }
            refuseUpdateWith(ForbiddenOperationException())
            patch(branchId, tenantId, body).andExpect { status { isForbidden() } }
            refuseUpdateWith(InvalidOperationException())
            patch(branchId, tenantId, body).andExpect { status { isUnprocessableContent() } }
        }

        private fun refuseUpdateWith(refusal: ApplicationException) {
            whenever(branchProvisioningService.update(any())).doAnswer { throw refusal }
        }

        @Test
        fun `update administers a branch other than the selected one`() {
            val tenantId = uuidV7()
            val targetBranchId = uuidV7()
            val selectedBranchId = uuidV7()
            stubUpdateResponse(tenantId, targetBranchId, "ACTIVE")

            patch(
                targetBranchId,
                tenantId,
                "{\"branch_name\":\"Riverside\"}",
                selectedBranchId = selectedBranchId,
            ).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(targetBranchId.toString()) }
            }

            assertEquals(targetBranchId, captureUpdate().branchId)
            verify(foundationQueryService, org.mockito.kotlin.never())
                .getBranch(any(), any(), any())
        }

        @Test
        fun `return passes the target branch and reason and answers the ungated detail`() {
            val tenantId = uuidV7()
            val targetBranchId = uuidV7()
            val selectedBranchId = uuidV7()
            stubUpdateResponse(tenantId, targetBranchId, "DRAFT")

            returnBranch(targetBranchId, tenantId, selectedBranchId = selectedBranchId).andExpect {
                status { isOk() }
                jsonPath("$.id") { value(targetBranchId.toString()) }
                jsonPath("$.status") { value("DRAFT") }
            }

            val captor = org.mockito.kotlin.argumentCaptor<ReturnBranchCommand>()
            verify(branchProvisioningService).returnForChanges(captor.capture())
            assertEquals(tenantId, captor.firstValue.organisationId)
            assertEquals(targetBranchId, captor.firstValue.branchId)
            assertEquals("Valid reason", captor.firstValue.reason.value)
            assertEquals(ActingScope.TENANT, captor.firstValue.scope)
            // The permission depends on who the actor is, so the service decides it; the
            // controller must not pin a single one, and the response must not need branch.view.
            verify(permissionGuard, org.mockito.kotlin.never())
                .requireBranchPermission(any(), any(), any(), any())
            verify(foundationQueryService, org.mockito.kotlin.never())
                .getBranch(any(), any(), any())
        }

        @Test
        fun `return is open to either the maker or the checker authority and to nobody else`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubUpdateResponse(tenantId, branchId, "DRAFT")

            listOf("branch.create", "branch.approve").forEach { authority ->
                returnBranch(branchId, tenantId, permissions = setOf(authority))
                    .andExpect { status { isOk() } }
            }
            returnBranch(branchId, tenantId, permissions = setOf("branch.view"))
                .andExpect { status { isForbidden() } }

            verify(branchProvisioningService, org.mockito.kotlin.times(2)).returnForChanges(any())
        }

        @Test
        fun `the deprecated branch activate authority no longer approves or returns a branch`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "ACTIVE")

            mockMvc
                .post("${ApiPaths.BRANCHES}/$branchId/activate") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                    with(authentication(tenantToken(setOf("branch.activate"), tenantId, branchId)))
                }.andExpect { status { isForbidden() } }
            returnBranch(branchId, tenantId, permissions = setOf("branch.activate"))
                .andExpect { status { isForbidden() } }

            verify(branchProvisioningService, org.mockito.kotlin.never()).activate(any())
            verify(branchProvisioningService, org.mockito.kotlin.never()).returnForChanges(any())
        }

        @Test
        fun `return requires a body with a reason of three to 500 characters`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()

            // A missing body, an absent reason or a null reason cannot be deserialised.
            listOf(null, "{}", "{\"reason\":null}").forEach { body ->
                returnBranch(branchId, tenantId, body = body).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("invalid_json") }
                }
            }
            // A present but blank or out-of-range reason fails Bean Validation.
            listOf("", "  ", "ab", "x".repeat(501)).forEach { reason ->
                returnBranch(
                    branchId,
                    tenantId,
                    body = apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason)),
                ).andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }
            }

            verify(branchProvisioningService, org.mockito.kotlin.never()).returnForChanges(any())
        }

        @Test
        fun `return accepts reasons of exactly three and 500 characters`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubUpdateResponse(tenantId, branchId, "DRAFT")

            listOf("abc", "x".repeat(500)).forEach { reason ->
                returnBranch(
                    branchId,
                    tenantId,
                    body = apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason)),
                ).andExpect { status { isOk() } }
            }
        }

        @Test
        fun `return surfaces the service refusals as problems`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()

            listOf(
                ForbiddenOperationException() to 403,
                ResourceNotFoundException() to 404,
                ConflictException() to 409,
            ).forEach { (refusal, expected) ->
                whenever(branchProvisioningService.returnForChanges(any())).doAnswer {
                    throw refusal
                }
                returnBranch(branchId, tenantId).andExpect { status { isEqualTo(expected) } }
            }
        }

        @Test
        fun `optional reason routes reject a reason over 500 characters before the service`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()

            optionalReasonRoutes.forEach { route ->
                postOptionalReason(route, tenantId, branchId, reasonBody("x".repeat(501)))
                    .andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }

            verify(branchProvisioningService, org.mockito.kotlin.never()).submitForApproval(any())
            verify(branchProvisioningService, org.mockito.kotlin.never()).activate(any())
            verify(branchProvisioningService, org.mockito.kotlin.never()).reactivate(any())
        }

        @Test
        fun `optional reason routes validate the body before the permission is evaluated`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()

            // Like every other @Valid body, a malformed one is a 400 even for a caller who would
            // have been refused with 403: validation precedes the method-security check.
            optionalReasonRoutes.forEach { route ->
                postOptionalReason(
                    route,
                    tenantId,
                    branchId,
                    reasonBody("x".repeat(501)),
                    permissions = setOf("branch.view"),
                ).andExpect { status { isBadRequest() } }
            }
        }

        @Test
        fun `optional reason routes accept a reason of exactly 500 characters`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "ACTIVE")

            optionalReasonRoutes.forEach { route ->
                postOptionalReason(route, tenantId, branchId, reasonBody("x".repeat(500)))
                    .andExpect { status { isOk() } }
            }

            verify(branchProvisioningService).submitForApproval(any())
            verify(branchProvisioningService).activate(any())
            verify(branchProvisioningService).reactivate(any())
        }

        @Test
        fun `optional reason routes accept an absent body and an empty object`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "ACTIVE")

            optionalReasonRoutes.forEach { route ->
                listOf(null, "{}", "{\"reason\":null}", reasonBody("   ")).forEach { body ->
                    postOptionalReason(route, tenantId, branchId, body)
                        .andExpect { status { isOk() } }
                }
            }

            verify(branchProvisioningService, org.mockito.kotlin.times(4)).submitForApproval(any())
            verify(branchProvisioningService, org.mockito.kotlin.times(4)).activate(any())
            verify(branchProvisioningService, org.mockito.kotlin.times(4)).reactivate(any())
        }

        @Test
        fun `optional reason routes hand the service a trimmed reason or none for a blank one`() {
            val tenantId = uuidV7()
            val branchId = uuidV7()
            stubBranchDetail(tenantId, branchId, "ACTIVE")

            postOptionalReason(
                optionalReasonRoutes[0],
                tenantId,
                branchId,
                reasonBody("  Ready \n"),
            ).andExpect { status { isOk() } }
            postOptionalReason(optionalReasonRoutes[1], tenantId, branchId, reasonBody("   "))
                .andExpect { status { isOk() } }
            postOptionalReason(optionalReasonRoutes[2], tenantId, branchId, null)
                .andExpect { status { isOk() } }

            val submit = org.mockito.kotlin.argumentCaptor<SubmitBranchForApprovalCommand>()
            verify(branchProvisioningService).submitForApproval(submit.capture())
            assertEquals("Ready", submit.firstValue.reason?.value)
            val activate = org.mockito.kotlin.argumentCaptor<ActivateBranchCommand>()
            verify(branchProvisioningService).activate(activate.capture())
            assertNull(activate.firstValue.reason)
            val reactivate = org.mockito.kotlin.argumentCaptor<ReactivateBranchCommand>()
            verify(branchProvisioningService).reactivate(reactivate.capture())
            assertNull(reactivate.firstValue.reason)
        }

        private val optionalReasonRoutes =
            listOf(
                "submit" to "branch.create",
                "activate" to "branch.approve",
                "reactivate" to "branch.reactivate",
            )

        private fun reasonBody(reason: String) =
            apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason))

        private fun postOptionalReason(
            route: Pair<String, String>,
            tenantId: UUID,
            branchId: UUID,
            body: String?,
            permissions: Set<String> = setOf(route.second),
        ) = mockMvc.post("${ApiPaths.BRANCHES}/$branchId/${route.first}") {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            with(authentication(tenantToken(permissions, tenantId, branchId)))
        }

        private fun returnBranch(
            branchId: UUID,
            tenantId: UUID,
            permissions: Set<String> = setOf("branch.approve"),
            selectedBranchId: UUID? = branchId,
            body: String? = REASON_BODY,
        ) = mockMvc.post("${ApiPaths.BRANCHES}/$branchId/return") {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            with(authentication(tenantToken(permissions, tenantId, selectedBranchId)))
        }

        private fun patch(
            branchId: UUID,
            tenantId: UUID,
            body: String?,
            permissions: Set<String> = setOf("branch.update"),
            selectedBranchId: UUID? = branchId,
        ) = mockMvc.patch("${ApiPaths.BRANCHES}/$branchId") {
            header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, uuidV7().toString())
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
            with(authentication(tenantToken(permissions, tenantId, selectedBranchId)))
        }

        private fun captureUpdate(): UpdateBranchCommand {
            val captor = org.mockito.kotlin.argumentCaptor<UpdateBranchCommand>()
            verify(branchProvisioningService).update(captor.capture())
            return captor.firstValue
        }

        private fun assertAdministersOtherBranch(
            action: String,
            permission: String,
            body: String,
            verifyCommand: (UUID) -> Unit,
        ) {
            val tenantId = uuidV7()
            val targetBranchId = uuidV7()
            val selectedBranchId = uuidV7()
            stubBranchDetail(tenantId, targetBranchId, "ACTIVE")

            mockMvc
                .post("${ApiPaths.BRANCHES}/$targetBranchId/$action") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                    with(authentication(tenantToken(setOf(permission), tenantId, selectedBranchId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(targetBranchId.toString()) }
                }

            verifyCommand(targetBranchId)
            // The permission must be evaluated against the target branch, not the selected one.
            verify(permissionGuard)
                .requireBranchPermission(any(), eq(tenantId), eq(targetBranchId), eq(permission))
        }

        private fun stubBranchDetail(
            tenantId: UUID,
            branchId: UUID,
            lifecycleStatus: String,
        ) {
            whenever(
                foundationQueryService.getBranch(eq(tenantId), eq(branchId), any()),
            ).thenReturn(branchDetail(tenantId, branchId, lifecycleStatus))
        }

        /**
         * The update response comes from the permission-free read: the service has already
         * authorised the target branch, and a branch-scoped maker holds no tenant-wide
         * branch.view. The gated read is deliberately left unstubbed so a regression fails.
         */
        private fun stubUpdateResponse(
            tenantId: UUID,
            branchId: UUID,
            lifecycleStatus: String,
        ) {
            whenever(
                foundationQueryService.getBranchAfterAuthorizedMutation(
                    eq(tenantId),
                    eq(branchId),
                ),
            ).thenReturn(branchDetail(tenantId, branchId, lifecycleStatus))
        }

        private fun branchDetail(
            tenantId: UUID,
            branchId: UUID,
            lifecycleStatus: String,
        ) = BranchDetail(
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
        )

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

                path.endsWith("/suspend") ||
                    path.endsWith("/close") ||
                    path.endsWith("/return") -> {
                    "{\"reason\":\"Valid reason\"}"
                }

                path.endsWith("/submit") ||
                    path.endsWith("/activate") ||
                    path.endsWith("/reactivate") -> {
                    "{}"
                }

                else -> {
                    "{\"branch_name\":\"Headquarters\"}"
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
