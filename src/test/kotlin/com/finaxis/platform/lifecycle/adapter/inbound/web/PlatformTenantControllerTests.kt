package com.finaxis.platform.lifecycle.adapter.inbound.web

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
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.AmendTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.CreateTenantDraftRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.DeprovisionTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.InitialAdminDto
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.ReactivateTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.RejectTenantRequest
import com.finaxis.platform.lifecycle.adapter.inbound.web.dto.SuspendTenantRequest
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.OrganisationDraftResult
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.application.query.TenantDetail
import com.finaxis.platform.lifecycle.application.query.TenantSummary
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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

@WebMvcTest(controllers = [PlatformTenantController::class], useDefaultFilters = false)
@AutoConfigureMockMvc
@Import(
    WebJsonConfiguration::class,
    ApiJsonCodec::class,
    ApiProblemFactory::class,
    ApiExceptionHandler::class,
    PlatformTenantControllerTests.TestSecurityConfiguration::class,
    PlatformTenantController::class,
)
class PlatformTenantControllerTests
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
        private lateinit var organisationProvisioningService: OrganisationProvisioningService

        @MockitoBean
        private lateinit var foundationQueryService: FoundationQueryService

        @MockitoBean
        private lateinit var adminBootstrapStore: InitialAdministratorBootstrapStore

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `createDraft succeeds with reserved platform context`() {
            val orgId = uuidV7()
            whenever(organisationProvisioningService.createDraft(any()))
                .thenReturn(OrganisationDraftResult(orgId, OrganisationLifecycleState.DRAFT))

            val request =
                CreateTenantDraftRequest(
                    tenantCode = "acme-test",
                    displayName = "Acme Test",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    admin =
                        InitialAdminDto(
                            email = "admin@acme.test",
                            username = "admin",
                            displayName = "Initial Admin",
                            phoneE164 = "+254700000000",
                        ),
                )

            val idempotencyKey = UUID.randomUUID().toString()

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context.TenantContext(
                            PlatformOrganisation.ID,
                        ),
                ),
            ) {
                mockMvc
                    .post(ApiPaths.PLATFORM_TENANTS) {
                        header(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(request)
                        with(authentication(platformToken(setOf("tenant.create"))))
                    }.andExpect {
                        status { isCreated() }
                        header { string("Location", "${ApiPaths.PLATFORM_TENANTS}/$orgId") }
                        jsonPath("$.organisation_id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("DRAFT") }
                    }
            }
        }

        @Test
        fun `createDraft rejects non-platform tenant context`() {
            val request =
                CreateTenantDraftRequest(
                    tenantCode = "acme-test",
                    displayName = "Acme Test",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    admin =
                        InitialAdminDto(
                            email = "admin@acme.test",
                            username = "admin",
                            displayName = "Initial Admin",
                            phoneE164 = "+254700000000",
                        ),
                )

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context
                            .TenantContext(uuidV7()),
                ),
            ) {
                mockMvc
                    .post(ApiPaths.PLATFORM_TENANTS) {
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(request)
                        with(authentication(tenantToken(setOf("tenant.create"))))
                    }.andExpect {
                        status { isForbidden() }
                        jsonPath(
                            "$.code",
                        ) {
                            value(
                                "Reserved platform organisation context is " +
                                    "required for this route.",
                            )
                        }
                    }
            }
        }

        @Test
        fun `getTenant returns tenant detail response`() {
            val orgId = uuidV7()
            val detail =
                TenantDetail(
                    id = orgId,
                    tenantCode = "acme-test",
                    displayName = "Acme Test",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    status = "ACTIVE",
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                )
            whenever(foundationQueryService.getTenant(eq(orgId), any())).thenReturn(detail)

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$orgId") {
                    with(authentication(platformToken(setOf("tenant.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.id") { value(orgId.toString()) }
                    jsonPath("$.tenant_code") { value("acme-test") }
                    jsonPath("$.status") { value("ACTIVE") }
                }
        }

        @Test
        fun `searchTenants returns a bounded page`() {
            val tenantId = uuidV7()
            whenever(foundationQueryService.searchTenants(any(), any())).thenReturn(
                apiPageOf(
                    listOf(
                        TenantSummary(
                            tenantId,
                            "acme-test",
                            "Acme Test",
                            "KE",
                            "ACTIVE",
                            Instant.parse("2026-07-18T10:00:00Z"),
                        ),
                    ),
                    number = 2,
                    size = 20,
                    totalItems = 41,
                ),
            )

            mockMvc
                .get(ApiPaths.PLATFORM_TENANTS) {
                    param("page", "2")
                    param("size", "20")
                    param("q", "acme")
                    with(authentication(platformToken(setOf("tenant.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].id") { value(tenantId.toString()) }
                    jsonPath("$.page.number") { value(2) }
                    jsonPath("$.page.size") { value(20) }
                }
        }

        @Test
        fun `amendDraft returns updated tenant detail`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, "DRAFT")
            val request =
                AmendTenantDraftRequest(
                    tenantCode = "acme-test",
                    displayName = "Acme Test Updated",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    admin =
                        InitialAdminDto(
                            email = "admin@acme.test",
                            username = "admin",
                            displayName = "Initial Admin",
                            phoneE164 = "+254700000000",
                        ),
                )

            withPlatformContext {
                mockMvc
                    .patch("${ApiPaths.PLATFORM_TENANTS}/$orgId") {
                        contentType = MediaType.APPLICATION_JSON
                        content = apiJsonCodec.mapper.writeValueAsString(request)
                        with(authentication(platformToken(setOf("tenant.update_draft"))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("DRAFT") }
                    }
            }

            verify(organisationProvisioningService).amendDraft(any())
        }

        @Test
        fun `submit returns updated tenant detail`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, "PENDING_APPROVAL")

            withPlatformContext {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/submit") {
                        with(
                            authentication(
                                platformToken(setOf("tenant.submit_for_approval")),
                            ),
                        )
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("PENDING_APPROVAL") }
                    }
            }

            verify(organisationProvisioningService).submitForApproval(any())
        }

        @Test
        fun `approve returns accepted status`() {
            val orgId = uuidV7()
            val detail =
                TenantDetail(
                    id = orgId,
                    tenantCode = "acme-test",
                    displayName = "Acme Test",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    status = "PROVISIONING",
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                )
            whenever(foundationQueryService.getTenant(eq(orgId), any())).thenReturn(detail)

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context.TenantContext(
                            PlatformOrganisation.ID,
                        ),
                ),
            ) {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/approve") {
                        with(authentication(platformToken(setOf("tenant.approve"))))
                    }.andExpect {
                        status { isAccepted() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("PROVISIONING") }
                    }
            }
        }

        @Test
        fun `suspend returns updated tenant detail`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, "SUSPENDED")

            withPlatformContext {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/suspend") {
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                SuspendTenantRequest(reason = "Regulatory review"),
                            )
                        with(authentication(platformToken(setOf("tenant.suspend"))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("SUSPENDED") }
                    }
            }

            verify(organisationProvisioningService).suspend(any())
        }

        @Test
        fun `reactivate returns updated tenant detail`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, "ACTIVE")

            withPlatformContext {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/reactivate") {
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                ReactivateTenantRequest(reason = "Review completed"),
                            )
                        with(authentication(platformToken(setOf("tenant.reactivate"))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("ACTIVE") }
                    }
            }

            verify(organisationProvisioningService).reactivate(any())
        }

        @Test
        fun `deprovision returns updated tenant detail`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, "DEPROVISIONED")

            withPlatformContext {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/deprovision") {
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                DeprovisionTenantRequest(reason = "Tenant offboarding"),
                            )
                        with(authentication(platformToken(setOf("tenant.deprovision"))))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("DEPROVISIONED") }
                    }
            }

            verify(organisationProvisioningService).deprovision(any())
        }

        @Test
        fun `retryBootstrap returns accepted status`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, "ACTIVE")

            withPlatformContext {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/bootstrap/retry") {
                        with(authentication(platformToken(setOf("tenant.bootstrap_retry"))))
                    }.andExpect {
                        status { isAccepted() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("ACTIVE") }
                    }
            }

            verify(organisationProvisioningService).retryBootstrap(any())
        }

        @Test
        fun `reject requires reason validation`() {
            val orgId = uuidV7()

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context.TenantContext(
                            PlatformOrganisation.ID,
                        ),
                ),
            ) {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/reject") {
                        contentType = MediaType.APPLICATION_JSON
                        content =
                            apiJsonCodec.mapper.writeValueAsString(
                                RejectTenantRequest(reason = "ab"),
                            )
                        with(authentication(platformToken(setOf("tenant.reject"))))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }
        }

        @Test
        fun `createDraft rejects a missing initial administrator phone`() {
            val request =
                """
                {"tenant_code":"acme-test","display_name":"Acme Test","country_code":"KE",
                 "base_currency_code":"KES","timezone":"Africa/Nairobi","admin":
                 {"email":"admin@acme.test","username":"admin","display_name":"Initial Admin"}}
                """.trimIndent()

            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context.TenantContext(
                            PlatformOrganisation.ID,
                        ),
                ),
            ) {
                mockMvc
                    .post(ApiPaths.PLATFORM_TENANTS) {
                        contentType = MediaType.APPLICATION_JSON
                        content = request
                        with(authentication(platformToken(setOf("tenant.create"))))
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("validation_failed") }
                    }
            }
        }

        @Test
        fun `tenant mutations generate or validate idempotency keys before authentication`() {
            val tenantId = uuidV7()
            val tenantRoute = "${ApiPaths.PLATFORM_TENANTS}/$tenantId"
            val routes =
                listOf(
                    HttpMethod.POST to ApiPaths.PLATFORM_TENANTS,
                    HttpMethod.PATCH to tenantRoute,
                    HttpMethod.POST to "$tenantRoute/submit",
                    HttpMethod.POST to "$tenantRoute/approve",
                    HttpMethod.POST to "$tenantRoute/reject",
                    HttpMethod.POST to "$tenantRoute/suspend",
                    HttpMethod.POST to "$tenantRoute/reactivate",
                    HttpMethod.POST to "$tenantRoute/deprovision",
                    HttpMethod.POST to "$tenantRoute/bootstrap/retry",
                )

            routes.forEach { (method, path) ->
                val payload = tenantMutationPayload(path)
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
                            .with(authentication(platformToken(emptySet()))),
                    ).andExpect(status().isBadRequest)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
                mockMvc
                    .perform(
                        request(method, path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .with(authentication(platformToken(emptySet()))),
                    ).andExpect(status().isForbidden)
                    .andExpect(header().exists(IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER))
            }
        }

        private fun stubTenantDetail(
            orgId: UUID,
            lifecycleStatus: String,
        ) {
            whenever(foundationQueryService.getTenant(eq(orgId), any())).thenReturn(
                TenantDetail(
                    id = orgId,
                    tenantCode = "acme-test",
                    displayName = "Acme Test",
                    countryCode = "KE",
                    baseCurrencyCode = "KES",
                    timezone = "Africa/Nairobi",
                    status = lifecycleStatus,
                    createdAt = Instant.parse("2026-07-18T10:00:00Z"),
                    updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
                ),
            )
        }

        private fun withPlatformContext(block: () -> Unit) {
            com.finaxis.platform.common.context.RequestContexts.with(
                com.finaxis.platform.common.context.RequestContext(
                    tenant =
                        com.finaxis.platform.common.context.TenantContext(
                            PlatformOrganisation.ID,
                        ),
                ),
                block,
            )
        }

        private fun tenantMutationPayload(path: String): String =
            when {
                path == ApiPaths.PLATFORM_TENANTS ||
                    path.removePrefix("${ApiPaths.PLATFORM_TENANTS}/").count { it == '/' } == 0 -> {
                    apiJsonCodec.mapper.writeValueAsString(
                        CreateTenantDraftRequest(
                            tenantCode = "acme-test",
                            displayName = "Acme Test",
                            countryCode = "KE",
                            baseCurrencyCode = "KES",
                            timezone = "Africa/Nairobi",
                            admin =
                                InitialAdminDto(
                                    "admin@acme.test",
                                    "admin",
                                    "Initial Admin",
                                    "+254700000000",
                                ),
                        ),
                    )
                }

                path.endsWith("/reject") ||
                    path.endsWith("/suspend") ||
                    path.endsWith("/deprovision") -> {
                    "{\"reason\":\"Valid reason\"}"
                }

                path.endsWith("/reactivate") -> {
                    "{}"
                }

                else -> {
                    ""
                }
            }

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

        private fun tenantToken(permissions: Set<String>): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = uuidV7(),
                    keycloakSubject = "tenant-user",
                    organisationId = uuidV7(),
                    membershipId = uuidV7(),
                    email = "admin@tenant.test",
                    fullName = "Tenant Admin",
                    permissions = permissions,
                ),
            )
    }
