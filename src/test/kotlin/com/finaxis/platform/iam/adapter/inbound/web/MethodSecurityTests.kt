package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.adapter.inbound.security.ActiveOrganisationContextResolver
import com.finaxis.platform.iam.adapter.inbound.security.AppPrincipalLoader
import com.finaxis.platform.iam.adapter.inbound.security.MethodSecurityAuthorizer
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import kotlin.test.Test

@ExtendWith(SpringExtension::class)
@WebMvcTest(MethodSecurityTests.ProtectedController::class)
@Import(
    MethodSecurityTests.MethodSecurityOnlyConfiguration::class,
    MethodSecurityAuthorizer::class,
    MethodSecurityTests.ProtectedController::class,
)
class MethodSecurityTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var contextResolver: ActiveOrganisationContextResolver

    @MockitoBean
    private lateinit var principalLoader: AppPrincipalLoader

    @Test
    fun `controller protected by PreAuthorize rejects missing permission`() {
        mockMvc
            .get("/test/protected") {
                with(authentication(tokenWithPermissions(emptySet())))
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `role derived permission exposed as authority passes hasAuthority`() {
        mockMvc
            .get("/test/protected") {
                with(authentication(tokenWithPermissions(setOf("logistics.shipment.approve"))))
            }.andExpect {
                status { isOk() }
                content { string("approved") }
            }
    }

    @Test
    fun `authz bean allows matching organisation permission code`() {
        val organisationId = uuidV7()

        mockMvc
            .get("/test/authz/$organisationId") {
                with(
                    authentication(
                        tokenWithPermissions(
                            permissions = setOf("branch.create"),
                            organisationId = organisationId,
                        ),
                    ),
                )
            }.andExpect {
                status { isOk() }
                content { string("branch-created:$organisationId") }
            }
    }

    @Test
    fun `authz bean denies missing permission code`() {
        val organisationId = uuidV7()

        mockMvc
            .get("/test/authz/$organisationId") {
                with(authentication(tokenWithPermissions(emptySet(), organisationId)))
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `authz bean denies different organisation context`() {
        val requestedOrganisationId = uuidV7()

        mockMvc
            .get("/test/authz/$requestedOrganisationId") {
                with(
                    authentication(
                        tokenWithPermissions(
                            permissions = setOf("branch.create"),
                            organisationId = uuidV7(),
                        ),
                    ),
                )
            }.andExpect {
                status { isForbidden() }
            }
    }

    private fun tokenWithPermissions(
        permissions: Set<String>,
        organisationId: UUID = uuidV7(),
        branchId: UUID? = null,
    ): AppPrincipalAuthenticationToken {
        val principal =
            AppPrincipal(
                userId = uuidV7(),
                keycloakSubject = "subject",
                organisationId = organisationId,
                membershipId = uuidV7(),
                branchId = branchId,
                email = "user@example.com",
                fullName = "Example User",
                permissions = permissions,
            )
        return AppPrincipalAuthenticationToken(principal)
    }

    @EnableMethodSecurity
    class MethodSecurityOnlyConfiguration

    @RestController
    class ProtectedController {
        @PreAuthorize("hasAuthority('logistics.shipment.approve')")
        @GetMapping("/test/protected")
        fun protectedEndpoint(): String = APPROVED_RESPONSE

        @PreAuthorize("@authz.hasPermission(#organisationId, 'branch.create')")
        @GetMapping("/test/authz/{organisationId}")
        fun authzProtectedEndpoint(
            @PathVariable organisationId: UUID,
        ): String = "$BRANCH_CREATED_RESPONSE:$organisationId"

        private companion object {
            const val APPROVED_RESPONSE = "approved"
            const val BRANCH_CREATED_RESPONSE = "branch-created"
        }
    }
}
