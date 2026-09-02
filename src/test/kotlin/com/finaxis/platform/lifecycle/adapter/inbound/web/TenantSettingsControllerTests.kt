package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
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
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateOrUpdateTenantSettingRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.DeactivateTenantSettingRequest
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.DeactivateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.TenantSettingPage
import com.finaxis.platform.lifecycle.application.TenantSettingView
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
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
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(controllers = [TenantSettingsController::class], useDefaultFilters = false)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    TenantSettingsControllerTests.TestSecurityConfiguration::class,
    TenantSettingsController::class,
)
class TenantSettingsControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
    ) {
        /** Minimal authenticated-only security and idempotency setup for the MVC slice. */
        @TestConfiguration
        @EnableMethodSecurity
        class TestSecurityConfiguration {
            /** Configures the authenticated-only test filter chain. */
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
        private lateinit var tenantSettingsService: TenantSettingsService

        @Test
        fun `list returns a bounded page with redacted values unchanged`() {
            val tenantId = uuidV7()
            whenever(tenantSettingsService.list(any())).thenReturn(
                TenantSettingPage(
                    items =
                        listOf(
                            TenantSettingView(
                                key = "audit_retention_days",
                                value = "***REDACTED***",
                                valueType = "INT",
                                sensitive = true,
                                platformAdminOnly = true,
                            ),
                        ),
                    totalItems = 26,
                ),
            )

            mockMvc
                .get(ApiPaths.TENANT_SETTINGS) {
                    param("page", "1")
                    param("size", "25")
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].key") { value("audit_retention_days") }
                    jsonPath("$.items[0].value") { value("***REDACTED***") }
                    jsonPath("$.items[0].value_type") { value("INT") }
                    jsonPath("$.items[0].platform_admin_only") { value(true) }
                    jsonPath("$.page.number") { value(1) }
                    jsonPath("$.page.size") { value(25) }
                    jsonPath("$.page.total_items") { value(26) }
                    jsonPath("$.page.has_next") { value(false) }
                    jsonPath("$.page.has_previous") { value(true) }
                }
        }

        @Test
        fun `list rejects invalid page bounds`() {
            val tenantId = uuidV7()

            mockMvc
                .get(ApiPaths.TENANT_SETTINGS) {
                    param("size", "0")
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            mockMvc
                .get(ApiPaths.TENANT_SETTINGS) {
                    param("size", "101")
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            verifyNoInteractions(tenantSettingsService)
        }

        @Test
        fun `list requires authentication`() {
            mockMvc
                .get(ApiPaths.TENANT_SETTINGS)
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `get returns a tenant setting`() {
            val tenantId = uuidV7()
            whenever(tenantSettingsService.get(any())).thenReturn(settingView())

            mockMvc
                .get("${ApiPaths.TENANT_SETTINGS}/default_timezone") {
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.key") { value("default_timezone") }
                    jsonPath("$.value") { value("Africa/Nairobi") }
                    jsonPath("$.value_type") { value("TIMEZONE") }
                    jsonPath("$.sensitive") { value(false) }
                    jsonPath("$.platform_admin_only") { value(false) }
                }
        }

        @Test
        fun `get surfaces an unknown key as an invalid operation`() {
            val tenantId = uuidV7()
            whenever(tenantSettingsService.get(any())).thenThrow(
                InvalidOperationException(safeDetail = "Unknown tenant setting key: unknown"),
            )

            mockMvc
                .get("${ApiPaths.TENANT_SETTINGS}/unknown") {
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isUnprocessableContent() }
                    jsonPath("$.code") { value("invalid_operation") }
                }
        }

        @Test
        fun `create or update returns the updated setting`() {
            val tenantId = uuidV7()
            val actorId = uuidV7()
            whenever(tenantSettingsService.createOrUpdate(any())).thenReturn(settingView())
            val request =
                CreateOrUpdateTenantSettingRequest(
                    value = "Africa/Nairobi",
                    reason = "Initial tenant setup",
                )

            mockMvc
                .put("${ApiPaths.TENANT_SETTINGS}/default_timezone") {
                    contentType = MediaType.APPLICATION_JSON
                    content = apiJsonCodec.mapper.writeValueAsString(request)
                    with(authentication(tenantToken(tenantId, actorId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.key") { value("default_timezone") }
                    jsonPath("$.value") { value("Africa/Nairobi") }
                }

            val command = argumentCaptor<CreateOrUpdateTenantSettingCommand>()
            verify(tenantSettingsService).createOrUpdate(command.capture())
            kotlin.test.assertEquals(tenantId, command.firstValue.organisationId)
            kotlin.test.assertEquals(actorId, command.firstValue.actorId)
            kotlin.test.assertEquals("default_timezone", command.firstValue.key)
            kotlin.test.assertEquals("Africa/Nairobi", command.firstValue.value)
            kotlin.test.assertEquals("Initial tenant setup", command.firstValue.reason)
        }

        @Test
        fun `create or update rejects a blank value`() {
            val tenantId = uuidV7()

            mockMvc
                .put("${ApiPaths.TENANT_SETTINGS}/default_timezone") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            CreateOrUpdateTenantSettingRequest(value = ""),
                        )
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("validation_failed") }
                }

            verifyNoInteractions(tenantSettingsService)
        }

        @Test
        fun `create or update returns conflict when the organisation is not active`() {
            val tenantId = uuidV7()
            whenever(tenantSettingsService.createOrUpdate(any())).thenThrow(ConflictException())

            mockMvc
                .put("${ApiPaths.TENANT_SETTINGS}/default_timezone") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            CreateOrUpdateTenantSettingRequest(value = "Africa/Nairobi"),
                        )
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isConflict() }
                    jsonPath("$.code") { value("conflict") }
                }
        }

        @Test
        fun `create or update returns forbidden for a denied platform setting`() {
            val tenantId = uuidV7()
            doThrow(AccessDeniedException("Missing permission: tenant_setting.manage_platform"))
                .whenever(tenantSettingsService)
                .createOrUpdate(any())

            mockMvc
                .put("${ApiPaths.TENANT_SETTINGS}/audit_retention_days") {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        apiJsonCodec.mapper.writeValueAsString(
                            CreateOrUpdateTenantSettingRequest(value = "365"),
                        )
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isForbidden() }
                    jsonPath("$.code") { value("forbidden") }
                }
        }

        @Test
        fun `deactivate returns no content`() {
            val tenantId = uuidV7()
            val actorId = uuidV7()
            val request = DeactivateTenantSettingRequest(reason = "No longer needed")

            mockMvc
                .delete("${ApiPaths.TENANT_SETTINGS}/default_timezone") {
                    contentType = MediaType.APPLICATION_JSON
                    content = apiJsonCodec.mapper.writeValueAsString(request)
                    with(authentication(tenantToken(tenantId, actorId)))
                }.andExpect {
                    status { isNoContent() }
                }

            val command = argumentCaptor<DeactivateTenantSettingCommand>()
            verify(tenantSettingsService).deactivate(command.capture())
            kotlin.test.assertEquals(tenantId, command.firstValue.organisationId)
            kotlin.test.assertEquals(actorId, command.firstValue.actorId)
            kotlin.test.assertEquals("default_timezone", command.firstValue.key)
            kotlin.test.assertEquals("No longer needed", command.firstValue.reason)
        }

        @Test
        fun `deactivate requires authentication`() {
            mockMvc
                .delete("${ApiPaths.TENANT_SETTINGS}/default_timezone")
                .andExpect { status { isUnauthorized() } }
        }

        @Test
        fun `mutation routes reject malformed idempotency keys and return a key header`() {
            val tenantId = uuidV7()
            val settingPath = "${ApiPaths.TENANT_SETTINGS}/default_timezone"

            listOf(HttpMethod.PUT, HttpMethod.DELETE).forEach { method ->
                mockMvc
                    .perform(
                        request(method, settingPath)
                            .header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, "not-a-uuid")
                            .with(authentication(tenantToken(tenantId))),
                    ).andExpect(status().isBadRequest)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
            }
        }

        private fun settingView() =
            TenantSettingView(
                key = "default_timezone",
                value = "Africa/Nairobi",
                valueType = "TIMEZONE",
                sensitive = false,
                platformAdminOnly = false,
            )

        private fun tenantToken(
            tenantId: UUID,
            actorId: UUID = uuidV7(),
        ) = AppPrincipalAuthenticationToken(
            AppPrincipal(
                userId = actorId,
                keycloakSubject = "tenant-user",
                organisationId = tenantId,
                membershipId = uuidV7(),
                branchId = null,
                email = "admin@tenant.test",
                fullName = "Tenant Admin",
                permissions = emptySet(),
            ),
        )
    }
