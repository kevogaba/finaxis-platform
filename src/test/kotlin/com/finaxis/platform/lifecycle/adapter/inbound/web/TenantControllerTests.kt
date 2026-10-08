package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.application.query.TenantDetail
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.test.web.servlet.request
    .SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.util.UUID

/**
 * `GET /api/v1/tenant` against a stubbed query service: the tenant reads the `status_reason` of
 * its own last transition (ADR 0029, 3c), always present on the wire and `null` when there is
 * none.
 */
@WebMvcTest(controllers = [TenantController::class], useDefaultFilters = false)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    TenantControllerTests.TestSecurityConfiguration::class,
    TenantController::class,
)
class TenantControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
    ) {
        /** Minimal authenticated-only security setup for the MVC slice. */
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
                        it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                    }.build()
        }

        @MockitoBean
        private lateinit var foundationQueryService: FoundationQueryService

        @Test
        fun `a returned tenant reads the reason of its last transition`() {
            val tenantId = uuidV7()
            stubTenant(tenantId, "DRAFT", "Registration number has a typo.")

            mockMvc
                .get(ApiPaths.TENANT) {
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("DRAFT") }
                    jsonPath("$.status_reason") { value("Registration number has a typo.") }
                }
        }

        @Test
        fun `a tenant with no reason carries an explicit null, not an omitted field`() {
            val tenantId = uuidV7()
            stubTenant(tenantId, "ACTIVE", null)

            mockMvc
                .get(ApiPaths.TENANT) {
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isOk() }
                    // `doesNotExist` would also pass for null, so assert the key on the wire.
                    content { string(containsString("\"status_reason\":null")) }
                }
        }

        @Test
        fun `the bootstrap status comes from the gated tenant detail`() {
            val tenantId = uuidV7()
            stubTenant(
                tenantId,
                "PENDING_APPROVAL",
                null,
                "FAILED",
                InitialAdministratorBootstrapFailureCode.IDENTITY_PROVIDER_FAILED,
            )

            mockMvc
                .get(ApiPaths.TENANT) {
                    with(authentication(tenantToken(tenantId)))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.bootstrap_status") { value("FAILED") }
                    jsonPath("$.bootstrap_failure_code") { value("IDENTITY_PROVIDER_FAILED") }
                }
        }

        private fun stubTenant(
            tenantId: UUID,
            status: String,
            statusReason: String?,
            bootstrapStatus: String? = null,
            bootstrapFailureCode: InitialAdministratorBootstrapFailureCode? = null,
        ) {
            whenever(foundationQueryService.getTenant(eq(tenantId), any()))
                .thenReturn(
                    TenantDetail(
                        id = tenantId,
                        tenantCode = "acme-test",
                        displayName = "Acme Test",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        status = status,
                        statusReason = statusReason,
                        bootstrapStatus = bootstrapStatus,
                        bootstrapFailureCode = bootstrapFailureCode,
                        createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                        updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                    ),
                )
        }

        private fun tenantToken(tenantId: UUID) =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = uuidV7(),
                    keycloakSubject = "tenant-user",
                    organisationId = tenantId,
                    membershipId = uuidV7(),
                    email = "admin@tenant.test",
                    fullName = "Tenant Admin",
                    permissions = setOf("tenant.view"),
                ),
            )
    }
