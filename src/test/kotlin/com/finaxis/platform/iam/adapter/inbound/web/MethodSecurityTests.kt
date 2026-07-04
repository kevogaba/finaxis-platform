package com.finaxis.platform.iam.adapter.inbound.web

import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import java.util.UUID
import kotlin.test.Test
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import com.finaxis.platform.iam.adapter.inbound.security.ActiveOrganisationContextResolver
import com.finaxis.platform.iam.adapter.inbound.security.AppPrincipalLoader

@ExtendWith(SpringExtension::class)
@WebMvcTest(MethodSecurityTests.ProtectedController::class)
@Import(MethodSecurityTests.MethodSecurityOnlyConfiguration::class, MethodSecurityTests.ProtectedController::class)
class MethodSecurityTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var contextResolver: ActiveOrganisationContextResolver

    @MockitoBean
    private lateinit var principalLoader: AppPrincipalLoader

    @Test
    fun `controller protected by PreAuthorize rejects missing permission`() {
        mockMvc.get("/test/protected") {
            with(authentication(tokenWithPermissions(emptySet())))
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `role derived permission exposed as authority passes hasAuthority`() {
        mockMvc.get("/test/protected") {
            with(authentication(tokenWithPermissions(setOf("logistics.shipment.approve"))))
        }.andExpect {
            status { isOk() }
            content { string("approved") }
        }
    }

    private fun tokenWithPermissions(permissions: Set<String>): AppPrincipalAuthenticationToken {
        val principal = AppPrincipal(
            userId = UUID.randomUUID(),
            keycloakSubject = "subject",
            organisationId = UUID.randomUUID(),
            membershipId = UUID.randomUUID(),
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
        fun protectedEndpoint(): String {
            return "approved"
        }
    }
}
