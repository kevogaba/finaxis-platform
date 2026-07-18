package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.authorization.ResourceRef
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.port.outbound.AppPrincipalLookup
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.PrincipalMembership
import com.finaxis.platform.iam.application.port.outbound.PrincipalUser
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
import com.finaxis.platform.lifecycle.UserFirstLoginActivation
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.security.access.AccessDeniedException as SpringAccessDeniedException

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
        val resource = ResourceRef("shipment", uuidV7(), principal.organisationId)
        val authorizationService = authorizationService()

        authorizationService.requirePermission(principal, "logistics.shipment.approve")
        authorizationService.require(principal, "logistics.shipment.approve", resource)
    }

    @Test
    fun `resource authorization rejects same organisation when permission is missing`() {
        val principal = principal()
        val resource = ResourceRef("shipment", uuidV7(), principal.organisationId)

        assertEquals(
            false,
            authorizationService().can(principal, "logistics.shipment.approve", resource),
        )
    }

    private fun authorizationService(): AuthorizationService =
        AuthorizationService(
            mock(MembershipSelectionLookup::class.java),
            mock(RequestPermissionCache::class.java),
        )

    @Test
    fun `principal loader rejects missing and mismatched identity context`() {
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val activation = mock(UserFirstLoginActivation::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver, activation)
        val context =
            ActiveOrganisationContext(uuidV7(), uuidV7(), uuidV7())

        assertNull(loader.load("missing", context))

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(uuidV7()))
        assertNull(loader.load("subject", context))
    }

    @Test
    fun `principal loader returns principal for matching membership`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val activation = mock(UserFirstLoginActivation::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver, activation)

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, organisationId))
        `when`(principalLookup.organisationStatus(organisationId)).thenReturn(
            OrganisationStatus.ACTIVE,
        )
        `when`(resolver.effectivePermissions(membershipId)).thenReturn(setOf("iam.user.invite"))

        val loaded =
            loader.load(
                "subject",
                ActiveOrganisationContext(userId, organisationId, membershipId),
            )

        assertEquals(setOf("iam.user.invite"), loaded?.permissions)
        assertEquals(organisationId, loaded?.organisationId)
        verify(activation).activateOnFirstLogin(userId, organisationId)
    }

    @Test
    fun `principal loader rejects mismatched membership`() {
        val userId = uuidV7()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val activation = mock(UserFirstLoginActivation::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver, activation)
        val membershipId = uuidV7()

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, uuidV7()))

        assertNull(
            loader.load(
                "subject",
                ActiveOrganisationContext(userId, uuidV7(), membershipId),
            ),
        )
    }

    @Test
    fun `principal loader rejects suspended users`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val activation = mock(UserFirstLoginActivation::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver, activation)

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId, UserStatus.SUSPENDED))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, organisationId))
        `when`(principalLookup.organisationStatus(organisationId)).thenReturn(
            OrganisationStatus.ACTIVE,
        )

        assertNull(
            loader.load(
                "subject",
                ActiveOrganisationContext(userId, organisationId, membershipId),
            ),
        )
        verify(activation, never()).activateOnFirstLogin(userId, organisationId)
    }

    @Test
    fun `principal loader rejects suspended and deprovisioned organisations`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val activation = mock(UserFirstLoginActivation::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver, activation)

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, organisationId))

        listOf(OrganisationStatus.SUSPENDED, OrganisationStatus.DEPROVISIONED).forEach { status ->
            `when`(principalLookup.organisationStatus(organisationId)).thenReturn(status)

            assertNull(
                loader.load(
                    "subject",
                    ActiveOrganisationContext(userId, organisationId, membershipId),
                ),
            )
        }

        verify(activation, never()).activateOnFirstLogin(userId, organisationId)
    }

    @Test
    fun `principal loader rejects branch context without active branch assignment`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val activation = mock(UserFirstLoginActivation::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver, activation)

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, organisationId))
        `when`(principalLookup.organisationStatus(organisationId)).thenReturn(
            OrganisationStatus.ACTIVE,
        )
        `when`(
            principalLookup.hasActiveAssignedBranch(membershipId, organisationId, branchId),
        ).thenReturn(false)

        assertNull(
            loader.load(
                "subject",
                ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            ),
        )
        verify(activation, never()).activateOnFirstLogin(userId, organisationId)
    }

    @Test
    fun `principal loader activates invited user before building principal`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val membershipId = uuidV7()
        val branchId = uuidV7()
        val principalLookup = mock(AppPrincipalLookup::class.java)
        val resolver = mock(EffectivePermissionResolver::class.java)
        val activation = mock(UserFirstLoginActivation::class.java)
        val loader = AppPrincipalLoader(principalLookup, resolver, activation)

        `when`(
            principalLookup.findPrincipalUserByKeycloakSubject("subject"),
        ).thenReturn(principalUser(userId, UserStatus.INVITED))
        `when`(
            principalLookup.findPrincipalMembershipById(membershipId),
        ).thenReturn(principalMembership(membershipId, userId, organisationId))
        `when`(principalLookup.organisationStatus(organisationId)).thenReturn(
            OrganisationStatus.ACTIVE,
        )
        `when`(
            principalLookup.hasActiveAssignedBranch(membershipId, organisationId, branchId),
        ).thenReturn(true)
        `when`(
            resolver.effectivePermissions(membershipId, branchId),
        ).thenReturn(setOf("iam.profile.read"))

        val loaded =
            loader.load(
                "subject",
                ActiveOrganisationContext(userId, organisationId, membershipId, branchId),
            )

        assertEquals(branchId, loaded?.branchId)
        assertEquals(setOf("iam.profile.read"), loaded?.permissions)
        verify(activation).activateOnFirstLogin(userId, organisationId)
    }

    @Test
    fun `active organisation filter upgrades jwt authentication`() {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val loader = mock(AppPrincipalLoader::class.java)
        val filter = ActiveOrganisationContextFilter(contextResolver, loader, problemWriter())
        val context =
            ActiveOrganisationContext(uuidV7(), uuidV7(), uuidV7())
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
                problemWriter(),
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
        assertTrue(requireNotNull(response.contentType).startsWith("application/problem+json"))
        assertEquals(
            "invalid_active_tenant_context",
            response.contentAsString.substringAfter("\"code\":\"").substringBefore('"'),
        )
        assertEquals(
            response.getHeader("X-Request-Id"),
            request.getAttribute(ApiProblemFactory.REQUEST_ID_ATTRIBUTE),
        )
    }

    @Test
    fun `authentication entry point returns shared correlated problem`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        request.addHeader("X-Request-Id", "security-request")
        val response = MockHttpServletResponse()

        ApiAuthenticationEntryPoint(problemWriter()).commence(
            request,
            response,
            InsufficientAuthenticationException("unsafe internal detail"),
        )

        assertSecurityProblem(response, 401, "authentication_required")
    }

    @Test
    fun `access denied handler returns shared correlated problem`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        request.addHeader("X-Request-Id", "security-request")
        val response = MockHttpServletResponse()

        ApiAccessDeniedHandler(problemWriter()).handle(
            request,
            response,
            SpringAccessDeniedException("unsafe internal detail"),
        )

        assertSecurityProblem(response, 403, "access_denied")
    }

    @Test
    fun `active organisation filter rejects context that cannot load principal`() {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val loader = mock(AppPrincipalLoader::class.java)
        val filter = ActiveOrganisationContextFilter(contextResolver, loader, problemWriter())
        val context =
            ActiveOrganisationContext(uuidV7(), uuidV7(), uuidV7())
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
            ActiveOrganisationContext(uuidV7(), uuidV7(), uuidV7())
        val filter =
            ActiveOrganisationContextFilter(
                contextResolver,
                mock(AppPrincipalLoader::class.java),
                problemWriter(),
            )
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
                problemWriter(),
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

    private fun problemWriter(): ApiProblemWriter =
        ApiProblemWriter(ApiProblemFactory(), ApiJsonCodec())

    private fun assertSecurityProblem(
        response: MockHttpServletResponse,
        status: Int,
        code: String,
    ) {
        assertEquals(status, response.status)
        assertTrue(requireNotNull(response.contentType).startsWith("application/problem+json"))
        assertEquals("security-request", response.getHeader("X-Request-Id"))
        assertTrue(response.contentAsString.contains("\"code\":\"$code\""))
        assertTrue(response.contentAsString.contains("\"request_id\":\"security-request\""))
        assertTrue(!response.contentAsString.contains("unsafe internal detail"))
    }

    private fun principal(permissions: Set<String> = emptySet()): AppPrincipal =
        AppPrincipal(
            userId = uuidV7(),
            keycloakSubject = "subject",
            organisationId = uuidV7(),
            membershipId = uuidV7(),
            email = "user@example.com",
            fullName = "Example User",
            permissions = permissions,
        )

    private fun principalUser(
        userId: UUID,
        status: UserStatus = UserStatus.ACTIVE,
    ): PrincipalUser =
        PrincipalUser(
            userId,
            "subject",
            "user@example.com",
            "Example User",
            status,
        )

    private fun principalMembership(
        membershipId: UUID,
        userId: UUID,
        organisationId: UUID,
        status: MembershipStatus = MembershipStatus.ACTIVE,
    ): PrincipalMembership =
        PrincipalMembership(
            membershipId,
            userId,
            organisationId,
            status,
        )
}
