package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.authorization.ResourceRef
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.port.outbound.AppPrincipalLookup
import com.finaxis.platform.iam.application.port.outbound.PrincipalMembership
import com.finaxis.platform.iam.application.port.outbound.PrincipalUser
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SecurityAdapterTests {
    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `current user returns application principal`() {
        val principal = principal()
        SecurityContextHolder.getContext().authentication =
            AppPrincipalAuthenticationToken(principal)

        assertEquals(principal, CurrentUser().principal())
    }

    @Test
    fun `current user rejects missing application principal`() {
        assertThrows<AccessDeniedException> {
            CurrentUser().principal()
        }
    }

    @Test
    fun `authentication token exposes principal and blank credentials`() {
        val principal = principal()
        val token = AppPrincipalAuthenticationToken(principal)

        assertEquals(principal, token.principal)
        assertEquals("", token.credentials)
    }

    @Test
    fun `authorization require succeeds when permission and organisation match`() {
        val principal = principal(permissions = setOf("logistics.shipment.approve"))
        val resource = ResourceRef("shipment", UUID.randomUUID(), principal.organisationId)

        AuthorizationService().requirePermission(principal, "logistics.shipment.approve")
        AuthorizationService().require(principal, "logistics.shipment.approve", resource)
    }

    @Test
    fun `resource authorization rejects same organisation when permission is missing`() {
        val principal = principal()
        val resource = ResourceRef("shipment", UUID.randomUUID(), principal.organisationId)

        assertEquals(
            false,
            AuthorizationService().can(principal, "logistics.shipment.approve", resource),
        )
    }

    @Test
    fun `principal loader rejects missing and mismatched identity context`() {
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver)
        val context =
            ActiveOrganisationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

        assertNull(loader.load("missing", context))

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(UUID.randomUUID()))
        assertNull(loader.load("subject", context))
    }

    @Test
    fun `principal loader returns principal for matching membership`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver)

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, organisationId))
        `when`(resolver.effectivePermissions(membershipId)).thenReturn(setOf("iam.user.invite"))

        val loaded =
            loader.load(
                "subject",
                ActiveOrganisationContext(userId, organisationId, membershipId),
            )

        assertEquals(setOf("iam.user.invite"), loaded?.permissions)
        assertEquals(organisationId, loaded?.organisationId)
    }

    @Test
    fun `principal loader rejects mismatched membership`() {
        val userId = UUID.randomUUID()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver)
        val membershipId = UUID.randomUUID()

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, UUID.randomUUID()))

        assertNull(
            loader.load(
                "subject",
                ActiveOrganisationContext(userId, UUID.randomUUID(), membershipId),
            ),
        )
    }

    @Test
    fun `active organisation filter upgrades jwt authentication`() {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val loader = mock(AppPrincipalLoader::class.java)
        val filter = ActiveOrganisationContextFilter(contextResolver, loader)
        val context =
            ActiveOrganisationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val principal = principal()
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        SecurityContextHolder.getContext().authentication = jwtAuthentication("subject")
        `when`(
            contextResolver.resolve(request),
        ).thenReturn(ActiveOrganisationContextResolution(context))
        `when`(loader.load("subject", context)).thenReturn(principal)

        filter.doFilter(request, response, MockFilterChain())

        assertIs<AppPrincipalAuthenticationToken>(SecurityContextHolder.getContext().authentication)
        assertEquals(200, response.status)
    }

    @Test
    fun `active organisation filter rejects invalid context`() {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val request = MockHttpServletRequest()
        val filter =
            ActiveOrganisationContextFilter(
                contextResolver,
                mock(AppPrincipalLoader::class.java),
            )
        val response = MockHttpServletResponse()
        SecurityContextHolder.getContext().authentication = jwtAuthentication("subject")
        `when`(contextResolver.resolve(request)).thenReturn(
            ActiveOrganisationContextResolution(
                failureMessage = "Invalid active organisation context header",
            ),
        )

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
    }

    @Test
    fun `active organisation filter rejects context that cannot load principal`() {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val loader = mock(AppPrincipalLoader::class.java)
        val filter = ActiveOrganisationContextFilter(contextResolver, loader)
        val context =
            ActiveOrganisationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        SecurityContextHolder.getContext().authentication = jwtAuthentication("subject")
        `when`(
            contextResolver.resolve(request),
        ).thenReturn(ActiveOrganisationContextResolution(context))
        `when`(loader.load("subject", context)).thenReturn(null)

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
    }

    @Test
    fun `active organisation filter rejects jwt authentication without subject`() {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val authentication = mock(JwtAuthenticationToken::class.java)
        val jwt = mock(Jwt::class.java)
        val context =
            ActiveOrganisationContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        val filter =
            ActiveOrganisationContextFilter(contextResolver, mock(AppPrincipalLoader::class.java))
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        `when`(authentication.token).thenReturn(jwt)
        `when`(jwt.subject).thenReturn(null)
        `when`(
            contextResolver.resolve(request),
        ).thenReturn(ActiveOrganisationContextResolution(context))
        SecurityContextHolder.getContext().authentication = authentication

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(403, response.status)
    }

    @Test
    fun `active organisation filter continues when no context is present`() {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val request = MockHttpServletRequest()
        val filter =
            ActiveOrganisationContextFilter(
                contextResolver,
                mock(AppPrincipalLoader::class.java),
            )
        val response = MockHttpServletResponse()
        SecurityContextHolder.getContext().authentication = jwtAuthentication("subject")
        `when`(contextResolver.resolve(request)).thenReturn(ActiveOrganisationContextResolution())

        filter.doFilter(request, response, MockFilterChain())

        assertEquals(200, response.status)
    }

    private fun jwtAuthentication(subject: String): JwtAuthenticationToken =
        JwtAuthenticationToken(
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .build(),
        )

    private fun principal(permissions: Set<String> = emptySet()): AppPrincipal =
        AppPrincipal(
            userId = UUID.randomUUID(),
            keycloakSubject = "subject",
            organisationId = UUID.randomUUID(),
            membershipId = UUID.randomUUID(),
            email = "user@example.com",
            fullName = "Example User",
            permissions = permissions,
        )

    private fun principalUser(userId: UUID): PrincipalUser =
        PrincipalUser(
            userId,
            "subject",
            "user@example.com",
            "Example User",
        )

    private fun principalMembership(
        membershipId: UUID,
        userId: UUID,
        organisationId: UUID,
    ): PrincipalMembership =
        PrincipalMembership(
            membershipId,
            userId,
            organisationId,
        )
}
