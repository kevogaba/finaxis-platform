package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ConflictException
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
import com.finaxis.platform.lifecycle.application.LifecycleErrorCodes
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.query.FoundationQueryService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
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
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post

/**
 * The 409 problem body every tenant action answers for the reserved platform organisation
 * (issue #205), against a stubbed service that raises the refusal: the status, the stable code and
 * the explicit client-facing message, and that a refused action is not read back for the response.
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
class PlatformTenantProtectionControllerTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
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
        fun `every tenant action on the platform organisation answers the protection 409`() {
            stubEveryServiceMethodToRefuse()

            ACTIONS.forEach { (action, permission) ->
                withPlatformContext {
                    mockMvc
                        .post("${ApiPaths.PLATFORM_TENANTS}/${PlatformOrganisation.ID}/$action") {
                            contentType = MediaType.APPLICATION_JSON
                            content = BODIES.getValue(action)
                            with(authentication(platformToken(setOf(permission))))
                        }.andExpect {
                            status { isConflict() }
                            jsonPath("$.status") { value(409) }
                            jsonPath("$.code") {
                                value("lifecycle.platform_organisation_protected")
                            }
                            jsonPath("$.detail") { value(PROTECTION_MESSAGE) }
                        }
                }
            }

            // A refused action must not go on to read the organisation back for the response.
            verify(foundationQueryService, never()).getTenant(any(), any())
        }

        @Test
        fun `amending the platform organisation answers the protection 409`() {
            stubEveryServiceMethodToRefuse()

            withPlatformContext {
                mockMvc
                    .patch("${ApiPaths.PLATFORM_TENANTS}/${PlatformOrganisation.ID}") {
                        contentType = MediaType.APPLICATION_JSON
                        content = AMEND_BODY
                        with(authentication(platformToken(setOf("tenant.update_draft"))))
                    }.andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("lifecycle.platform_organisation_protected") }
                        jsonPath("$.detail") { value(PROTECTION_MESSAGE) }
                    }
            }

            verify(foundationQueryService, never()).getTenant(any(), any())
        }

        private fun stubEveryServiceMethodToRefuse() {
            val refusal = {
                ConflictException(
                    LifecycleErrorCodes.PLATFORM_ORGANISATION_PROTECTED,
                    LifecycleErrorCodes.PLATFORM_ORGANISATION_PROTECTED_DETAIL,
                )
            }
            val service = organisationProvisioningService
            doThrow(refusal()).whenever(service).suspend(any())
            doThrow(refusal()).whenever(service).deprovision(any())
            doThrow(refusal()).whenever(service).reactivate(any())
            doThrow(refusal()).whenever(service).approveProvisioning(any())
            doThrow(refusal()).whenever(service).rejectProvisioning(any())
            doThrow(refusal()).whenever(service).returnForChanges(any())
            doThrow(refusal()).whenever(service).submitForApproval(any())
            doThrow(refusal()).whenever(service).retryBootstrap(any())
            doThrow(refusal()).whenever(service).amendDraft(any())
        }

        private fun withPlatformContext(block: () -> Unit) {
            RequestContexts.with(
                RequestContext(tenant = TenantContext(PlatformOrganisation.ID)),
                block,
            )
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

        private companion object {
            const val PROTECTION_MESSAGE =
                "The platform organisation cannot be suspended, deprovisioned or otherwise " +
                    "changed through the tenant lifecycle."
            const val REASON_BODY = """{"reason":"Valid reason"}"""
            const val AMEND_BODY =
                """{"tenant_code":"renamed","display_name":"Renamed","country_code":"KE",
                    "base_currency_code":"KES","timezone":"Africa/Nairobi","admin":
                    {"email":"a@example.test","username":"adminuser","display_name":"Admin User",
                     "phone_e164":"+254700000000"}}"""

            val ACTIONS: List<Pair<String, String>> =
                listOf(
                    "suspend" to "tenant.suspend",
                    "deprovision" to "tenant.deprovision",
                    "reactivate" to "tenant.reactivate",
                    "approve" to "tenant.approve",
                    "reject" to "tenant.reject",
                    "return" to "tenant.reject",
                    "submit" to "tenant.submit_for_approval",
                    "bootstrap/retry" to "tenant.bootstrap_retry",
                )

            val BODIES: Map<String, String> =
                mapOf(
                    "suspend" to REASON_BODY,
                    "deprovision" to REASON_BODY,
                    "reactivate" to "{}",
                    "approve" to "",
                    "reject" to REASON_BODY,
                    "return" to REASON_BODY,
                    "submit" to "",
                    "bootstrap/retry" to "",
                )
        }
    }
