package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.idempotency.IdempotencyProperties
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AdvanceBusinessDateRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.BusinessDateStatusTransitionRequest
import com.finaxis.platform.lifecycle.application.AdvanceBusinessDateCommand
import com.finaxis.platform.lifecycle.application.BusinessDateAdvanceResult
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryPage
import com.finaxis.platform.lifecycle.application.BusinessDateHistoryRecord
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.BusinessDateView
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val MODULITH_RUNTIME_AUTO_CONFIGURATION =
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration"
private const val EXCLUDE_MODULITH_RUNTIME =
    "spring.autoconfigure.exclude=$MODULITH_RUNTIME_AUTO_CONFIGURATION"

@WebMvcTest(
    controllers = [BusinessDateController::class],
    properties = [EXCLUDE_MODULITH_RUNTIME],
    useDefaultFilters = false,
)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    BusinessDateControllerTests.TestSecurityConfiguration::class,
    BusinessDateController::class,
)
class BusinessDateControllerTests
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
            ): FilterRegistrationBean<IdempotencyKeyFilter> =
                FilterRegistrationBean(
                    IdempotencyKeyFilter(
                        IdempotencyProperties(),
                        ApiProblemWriter(problemFactory, jsonCodec),
                    ),
                )
        }

        @MockitoBean
        private lateinit var businessDateService: BusinessDateService

        @Test
        fun `current business date returns the tenant scoped view`() {
            val organisationId = uuidV7()
            whenever(businessDateService.get(any())).thenReturn(currentView(organisationId))

            mockMvc
                .get(ApiPaths.BUSINESS_DATE) {
                    with(
                        authentication(tenantToken(setOf("business_date.view"), organisationId)),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.organisation_id") { value(organisationId.toString()) }
                    jsonPath("$.current_business_date") { value("18-07-2026") }
                    jsonPath("$.status") { value("OPEN") }
                }
        }

        @Test
        fun `current business date requires authentication and view permission`() {
            val organisationId = uuidV7()
            mockMvc.get(ApiPaths.BUSINESS_DATE).andExpect { status { isUnauthorized() } }
            mockMvc
                .get(ApiPaths.BUSINESS_DATE) {
                    with(authentication(tenantToken(emptySet(), organisationId)))
                }.andExpect { status { isForbidden() } }
        }

        @Test
        fun `current business date surfaces a safe not found response`() {
            val organisationId = uuidV7()
            whenever(businessDateService.get(any())).thenThrow(ResourceNotFoundException())

            mockMvc
                .get(ApiPaths.BUSINESS_DATE) {
                    with(authentication(tenantToken(setOf("business_date.view"), organisationId)))
                }.andExpect {
                    status { isNotFound() }
                    jsonPath("$.code") { value("resource_not_found") }
                }
        }

        @Test
        fun `history returns a bounded newest first page`() {
            val organisationId = uuidV7()
            whenever(businessDateService.listHistory(any())).thenReturn(
                BusinessDateHistoryPage(
                    listOf(
                        BusinessDateHistoryRecord(
                            "ADVANCED",
                            "OPEN",
                            "OPEN",
                            LocalDate.of(2026, 7, 17),
                            LocalDate.of(2026, 7, 18),
                            uuidV7(),
                            "Daily advance",
                            NOW,
                        ),
                    ),
                    totalItems = 11,
                ),
            )

            mockMvc
                .get("${ApiPaths.BUSINESS_DATE}/history") {
                    param("page", "1")
                    param("size", "10")
                    with(authentication(tenantToken(setOf("business_date.view"), organisationId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].event_type") { value("ADVANCED") }
                    jsonPath("$.items[0].from_business_date") { value("17-07-2026") }
                    jsonPath("$.items[0].to_business_date") { value("18-07-2026") }
                    jsonPath("$.page.number") { value(1) }
                    jsonPath("$.page.size") { value(10) }
                    jsonPath("$.page.total_items") { value(11) }
                }
        }

        @Test
        fun `history validates page bounds`() {
            val organisationId = uuidV7()
            listOf("0", "101").forEach { size ->
                mockMvc
                    .get("${ApiPaths.BUSINESS_DATE}/history") {
                        param("size", size)
                        with(
                            authentication(
                                tenantToken(setOf("business_date.view"), organisationId),
                            ),
                        )
                    }.andExpect { status { isBadRequest() } }
            }
        }

        @Test
        fun `advance returns its transition result`() {
            val organisationId = uuidV7()
            whenever(businessDateService.advance(any())).thenReturn(
                BusinessDateAdvanceResult(
                    organisationId,
                    LocalDate.of(2026, 7, 18),
                    LocalDate.of(2026, 7, 19),
                ),
            )

            mockMvc
                .post("${ApiPaths.BUSINESS_DATE}/advance") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            AdvanceBusinessDateRequest(LocalDate.of(2026, 7, 19), "Daily advance"),
                        )
                    with(
                        authentication(
                            tenantToken(setOf("business_date.advance"), organisationId),
                        ),
                    )
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.previous_business_date") { value("18-07-2026") }
                    jsonPath("$.new_business_date") { value("19-07-2026") }
                }

            val commandCaptor = argumentCaptor<AdvanceBusinessDateCommand>()
            verify(businessDateService).advance(commandCaptor.capture())
            kotlin.test.assertEquals(organisationId, commandCaptor.firstValue.organisationId)
            kotlin.test.assertEquals(
                LocalDate.of(2026, 7, 19),
                commandCaptor.firstValue.newBusinessDate,
            )
            kotlin.test.assertEquals("Daily advance", commandCaptor.firstValue.reason)
        }

        @Test
        fun `advance validates missing and blank business dates`() {
            val organisationId = uuidV7()
            listOf("{}", "{\"new_business_date\":\"\"}").forEach { payload ->
                mockMvc
                    .post("${ApiPaths.BUSINESS_DATE}/advance") {
                        contentType = MediaType.APPLICATION_JSON
                        content = payload
                        with(
                            authentication(
                                tenantToken(setOf("business_date.advance"), organisationId),
                            ),
                        )
                    }.andExpect { status { isBadRequest() } }
            }
        }

        @Test
        fun `advance conflict requires authentication and permission`() {
            val organisationId = uuidV7()
            whenever(businessDateService.advance(any())).thenThrow(ConflictException())
            val payload =
                apiJsonCodec.mapper.writeValueAsString(
                    AdvanceBusinessDateRequest(LocalDate.of(2026, 7, 19)),
                )

            mockMvc
                .post("${ApiPaths.BUSINESS_DATE}/advance") {
                    contentType = MediaType.APPLICATION_JSON
                    content = payload
                }.andExpect { status { isUnauthorized() } }
            mockMvc
                .post("${ApiPaths.BUSINESS_DATE}/advance") {
                    contentType = MediaType.APPLICATION_JSON
                    content = payload
                    with(authentication(tenantToken(emptySet(), organisationId)))
                }.andExpect { status { isForbidden() } }
            mockMvc
                .post("${ApiPaths.BUSINESS_DATE}/advance") {
                    contentType = MediaType.APPLICATION_JSON
                    content = payload
                    with(
                        authentication(
                            tenantToken(setOf("business_date.advance"), organisationId),
                        ),
                    )
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("conflict") }
                }
        }

        @Test
        fun `close of business mutations return their status views`() {
            val organisationId = uuidV7()
            whenever(businessDateService.startCob(any())).thenReturn(
                BusinessDateView(organisationId, LocalDate.of(2026, 7, 18), "CLOSING"),
            )
            whenever(businessDateService.completeCob(any())).thenReturn(
                BusinessDateView(organisationId, LocalDate.of(2026, 7, 18), "CLOSED"),
            )
            whenever(businessDateService.reopen(any())).thenReturn(currentView(organisationId))

            mutationRoutes().forEach { (_, path, permission) ->
                mockMvc
                    .post(path) {
                        contentType = MediaType.APPLICATION_JSON
                        content = statusTransitionPayload()
                        with(authentication(tenantToken(setOf(permission), organisationId)))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.organisation_id") { value(organisationId.toString()) }
                    }
            }
        }

        @Test
        fun `close of business mutations surface state conflicts`() {
            val organisationId = uuidV7()
            whenever(businessDateService.startCob(any())).thenThrow(ConflictException())
            whenever(businessDateService.completeCob(any())).thenThrow(ConflictException())
            whenever(businessDateService.reopen(any())).thenThrow(ConflictException())

            mutationRoutes().forEach { (_, path, permission) ->
                mockMvc
                    .post(path) {
                        contentType = MediaType.APPLICATION_JSON
                        content = statusTransitionPayload()
                        with(authentication(tenantToken(setOf(permission), organisationId)))
                    }.andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("conflict") }
                    }
            }
        }

        @Test
        fun `close of business mutations require authentication and their permissions`() {
            val organisationId = uuidV7()
            mutationRoutes().forEach { (_, path, _) ->
                mockMvc
                    .post(path) {
                        contentType = MediaType.APPLICATION_JSON
                        content = statusTransitionPayload()
                    }.andExpect { status { isUnauthorized() } }
            }
            mutationRoutes().forEach { (_, path, _) ->
                mockMvc
                    .post(path) {
                        contentType = MediaType.APPLICATION_JSON
                        content = statusTransitionPayload()
                        with(authentication(tenantToken(emptySet(), organisationId)))
                    }.andExpect { status { isForbidden() } }
            }
        }

        @Test
        fun `all business date mutations reject malformed idempotency keys`() {
            val organisationId = uuidV7()
            allMutationRoutes().forEach { (_, path, permission) ->
                mockMvc
                    .perform(
                        request(HttpMethod.POST, path)
                            .header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, "not-a-uuid")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mutationPayload(path))
                            .with(authentication(tenantToken(setOf(permission), organisationId))),
                    ).andExpect(status().isBadRequest)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
            }
        }

        private fun mutationRoutes() =
            listOf(
                Triple("start", "${ApiPaths.BUSINESS_DATE}/cob/start", "cob.start"),
                Triple("complete", "${ApiPaths.BUSINESS_DATE}/cob/complete", "cob.complete"),
                Triple("reopen", "${ApiPaths.BUSINESS_DATE}/reopen", "business_date.reopen"),
            )

        private fun allMutationRoutes() =
            listOf(
                Triple("advance", "${ApiPaths.BUSINESS_DATE}/advance", "business_date.advance"),
            ) + mutationRoutes()

        private fun mutationPayload(path: String): String =
            if (path.endsWith("/advance")) {
                apiJsonCodec.mapper.writeValueAsString(
                    AdvanceBusinessDateRequest(LocalDate.of(2026, 7, 19)),
                )
            } else {
                statusTransitionPayload()
            }

        private fun statusTransitionPayload(): String =
            apiJsonCodec.mapper.writeValueAsString(
                BusinessDateStatusTransitionRequest("Daily close"),
            )

        private fun currentView(organisationId: UUID) =
            BusinessDateView(organisationId, LocalDate.of(2026, 7, 18), "OPEN")

        private fun tenantToken(
            permissions: Set<String>,
            organisationId: UUID = uuidV7(),
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = uuidV7(),
                keycloakSubject = "tenant-user",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = null,
                email = "admin@tenant.test",
                fullName = "Tenant Admin",
                permissions = permissions,
            ),
        )

        private companion object {
            val NOW: Instant = Instant.parse("2026-07-25T10:00:00Z")
        }
    }
