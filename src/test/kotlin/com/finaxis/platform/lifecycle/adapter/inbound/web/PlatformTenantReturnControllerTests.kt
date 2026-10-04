package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiExceptionHandler
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.WebJsonConfiguration
import com.finaxis.platform.common.web.versioning.ApiPaths
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReturnOrganisationForChangesCommand
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import com.finaxis.platform.lifecycle.application.query.TenantDetail
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

/**
 * The tenant return route (ADR 0029, 3c) and the `status_reason` of the tenant detail, against a
 * stubbed service: the coarse authority, the binding of the required body, the permission-free
 * read-back and the mapping of the service's refusals.
 */
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
class PlatformTenantReturnControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
    ) {
        @MockitoBean
        private lateinit var organisationProvisioningService: OrganisationProvisioningService

        @MockitoBean
        private lateinit var foundationQueryService: FoundationQueryService

        @MockitoBean
        private lateinit var adminBootstrapStore: InitialAdministratorBootstrapStore

        @MockitoBean
        private lateinit var permissionGuard: PermissionGuard

        @Test
        fun `getTenant exposes the status reason of a returned tenant`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, "Registration number has a typo.")

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$orgId") {
                    with(authentication(platformToken(setOf("tenant.view"))))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.status") { value("DRAFT") }
                    jsonPath("$.status_reason") { value("Registration number has a typo.") }
                }
        }

        @Test
        fun `a tenant with no status reason carries an explicit null`() {
            val orgId = uuidV7()
            stubTenantDetail(orgId, null)

            mockMvc
                .get("${ApiPaths.PLATFORM_TENANTS}/$orgId") {
                    with(authentication(platformToken(setOf("tenant.view"))))
                }.andExpect {
                    status { isOk() }
                    // Present and null, never omitted: `doesNotExist` would also pass for null.
                    content { string(containsString("\"status_reason\":null")) }
                }
        }

        @Test
        fun `return sends a pending tenant back to draft and reads it back without tenant view`() {
            val orgId = uuidV7()
            val actor = uuidV7()
            whenever(foundationQueryService.getTenantAfterAuthorizedMutation(eq(orgId)))
                .thenReturn(tenantDetail(orgId, "DRAFT", "Registration number has a typo."))

            withPlatformContext {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/return") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"reason":"Registration number has a typo."}"""
                        // tenant.reject alone: the response must not need tenant.view.
                        with(authentication(platformToken(setOf("tenant.reject"), actor)))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.id") { value(orgId.toString()) }
                        jsonPath("$.status") { value("DRAFT") }
                        jsonPath("$.status_reason") { value("Registration number has a typo.") }
                    }
            }

            verify(organisationProvisioningService).returnForChanges(
                ReturnOrganisationForChangesCommand(
                    organisationId = orgId,
                    reason = "Registration number has a typo.",
                    actorId = actor,
                ),
            )
            verify(foundationQueryService, never()).getTenant(any(), any())
        }

        @Test
        fun `return needs the tenant reject authority`() {
            val orgId = uuidV7()

            withPlatformContext {
                mockMvc
                    .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/return") {
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"reason":"Registration number has a typo."}"""
                        with(authentication(platformToken(setOf("tenant.approve"))))
                    }.andExpect { status { isForbidden() } }
            }

            verify(organisationProvisioningService, never()).returnForChanges(any())
        }

        @Test
        fun `return requires a body with a reason of 3 to 500 characters`() {
            val orgId = uuidV7()
            val path = "${ApiPaths.PLATFORM_TENANTS}/$orgId/return"

            withPlatformContext {
                // A missing body, an absent reason and a null reason fail to deserialize.
                listOf(null, "{}", """{"reason":null}""").forEach { body ->
                    mockMvc
                        .post(path) {
                            body?.let {
                                contentType = MediaType.APPLICATION_JSON
                                content = it
                            }
                            with(authentication(platformToken(setOf("tenant.reject"))))
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("invalid_json") }
                        }
                }
                // A present reason that is blank or out of range is a validation failure.
                listOf("", "   ", "ab", "x".repeat(501)).forEach { reason ->
                    mockMvc
                        .post(path) {
                            contentType = MediaType.APPLICATION_JSON
                            content =
                                apiJsonCodec.mapper.writeValueAsString(mapOf("reason" to reason))
                            with(authentication(platformToken(setOf("tenant.reject"))))
                        }.andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("validation_failed") }
                        }
                }
            }

            verify(organisationProvisioningService, never()).returnForChanges(any())
        }

        @Test
        fun `return maps an application refusal to its status`() {
            val orgId = uuidV7()
            listOf(
                ForbiddenOperationException() to 403,
                ResourceNotFoundException() to 404,
                ConflictException() to 409,
            ).forEach { (refusal, expected) ->
                doThrow(refusal).whenever(organisationProvisioningService).returnForChanges(any())
                withPlatformContext {
                    mockMvc
                        .post("${ApiPaths.PLATFORM_TENANTS}/$orgId/return") {
                            contentType = MediaType.APPLICATION_JSON
                            content = """{"reason":"Registration number has a typo."}"""
                            with(authentication(platformToken(setOf("tenant.reject"))))
                        }.andExpect { status { isEqualTo(expected) } }
                }
            }
        }

        private fun stubTenantDetail(
            orgId: UUID,
            statusReason: String?,
        ) {
            whenever(foundationQueryService.getTenant(eq(orgId), any()))
                .thenReturn(tenantDetail(orgId, "DRAFT", statusReason))
        }

        private fun tenantDetail(
            orgId: UUID,
            status: String,
            statusReason: String?,
        ) = TenantDetail(
            id = orgId,
            tenantCode = "acme-test",
            displayName = "Acme Test",
            countryCode = "KE",
            baseCurrencyCode = "KES",
            timezone = "Africa/Nairobi",
            status = status,
            statusReason = statusReason,
            createdAt = Instant.parse("2026-07-18T10:00:00Z"),
            updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
        )

        private fun withPlatformContext(block: () -> Unit) {
            RequestContexts.with(
                RequestContext(tenant = TenantContext(PlatformOrganisation.ID)),
                block,
            )
        }

        private fun platformToken(
            permissions: Set<String>,
            userId: UUID = uuidV7(),
        ): AppPrincipalAuthenticationToken =
            AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = userId,
                    keycloakSubject = "platform-user",
                    organisationId = PlatformOrganisation.ID,
                    membershipId = uuidV7(),
                    email = "admin@platform.test",
                    fullName = "Platform Admin",
                    permissions = permissions,
                ),
            )
    }
