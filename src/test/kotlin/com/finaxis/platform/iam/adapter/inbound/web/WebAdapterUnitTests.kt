package com.finaxis.platform.iam.adapter.inbound.web

import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.finaxis.platform.iam.adapter.inbound.security.SessionActiveOrganisationContextResolver
import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextProperties
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.port.outbound.MembershipSelection
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.port.outbound.ProfileBranch
import com.finaxis.platform.iam.application.port.outbound.ProfileMembership
import com.finaxis.platform.iam.application.port.outbound.ProfileOrganisation
import com.finaxis.platform.iam.application.port.outbound.ProfileRole
import com.finaxis.platform.iam.application.port.outbound.UserProfileLookup
import com.finaxis.platform.iam.application.profile.UserProfileService
import com.finaxis.platform.iam.application.selection.AuthSelectionService
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.core.MethodParameter
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpMethod
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.validation.ObjectError
import org.springframework.validation.method.ParameterValidationResult
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.lang.reflect.Method
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val MEMBERSHIP_ID: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555")
private val HEAD_OFFICE_BRANCH_ID: UUID =
    UUID.fromString("33333333-3333-3333-3333-333333333333")

class WebAdapterUnitTests {
    @Test
    fun `auth controller returns selected organisation response`() {
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val service =
            AuthSelectionService(
                lookup = StaticMembershipLookup(organisationId, membershipId),
                contextService =
                    ActiveOrganisationContextService(
                        ActiveOrganisationContextProperties("test-secret-with-enough-length"),
                        Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), ZoneOffset.UTC),
                    ),
            )
        val controller = AuthController(service)

        val response =
            controller.selectOrganisation(
                jwtAuthentication(),
                SelectOrganisationRequest(organisationId),
                MockHttpSession(),
            )

        assertEquals(organisationId, response.organisationId)
        assertEquals(membershipId, response.membershipId)
        assertTrue(response.contextToken.isNotBlank())
        assertEquals("X-Active-Organisation-Context", response.contextHeader)
        assertEquals(false, response.requiresBranchSelection)
    }

    @Test
    fun `auth controller stores selected branch in browser session`() {
        val organisationId = UUID.randomUUID()
        val membershipId = UUID.randomUUID()
        val branchId = UUID.randomUUID()
        val lookup =
            StaticMembershipLookup(organisationId, membershipId, branchIds = listOf(branchId))
        val service =
            AuthSelectionService(
                lookup = lookup,
                contextService =
                    ActiveOrganisationContextService(
                        ActiveOrganisationContextProperties("test-secret-with-enough-length"),
                        Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), ZoneOffset.UTC),
                    ),
            )
        val session = MockHttpSession()
        session.setAttribute(
            SessionActiveOrganisationContextResolver.ATTRIBUTE,
            ActiveOrganisationContext(lookup.userId, organisationId, membershipId),
        )
        val controller = AuthController(service)

        val response =
            controller.selectBranch(
                jwtAuthentication(),
                SelectBranchRequest(branchId),
                session,
            )

        assertEquals(branchId, response.branchId)
        assertEquals(
            ActiveOrganisationContext(lookup.userId, organisationId, membershipId, branchId),
            session.getAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE),
        )
    }

    @Test
    fun `api exception handler returns forbidden response`() {
        val request = MockHttpServletRequest("POST", "/shipments/1/approve")
        val response = ApiExceptionHandler().forbidden(AccessDeniedException("denied"), request)

        assertEquals(403, response.statusCode.value())
        assertEquals("denied", response.body?.message)
        assertEquals("/shipments/1/approve", response.body?.path)
        assertEquals("forbidden", response.body?.code)
        assertEquals(
            "denied",
            response.body
                ?.errors
                ?.single()
                ?.message,
        )
    }

    @Test
    fun `api exception handler returns spring security forbidden response`() {
        val response =
            ApiExceptionHandler().springSecurityForbidden(
                org.springframework.security.access
                    .AccessDeniedException("missing authority"),
                MockHttpServletRequest("GET", "/protected"),
            )

        assertEquals(403, response.statusCode.value())
        assertEquals("forbidden", response.body?.code)
        assertEquals(
            "authorization",
            response.body
                ?.errors
                ?.single()
                ?.attribute,
        )
    }

    @Test
    fun `api exception handler returns validation response`() {
        val target = SelectOrganisationRequest(null)
        val binding = BeanPropertyBindingResult(target, "request")
        binding.addError(FieldError("request", "organisationId", "must not be null"))
        val exception =
            MethodArgumentNotValidException(MethodParameter(dummyBodyMethod(), 0), binding)
        val response =
            ApiExceptionHandler().validation(
                exception,
                MockHttpServletRequest("POST", "/auth/select-organisation"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("Validation failed", response.body?.message)
        assertEquals(
            mapOf("organisationId" to listOf("must not be null")),
            response.body?.fieldErrors,
        )
    }

    @Test
    fun `api exception handler groups object validation errors`() {
        val binding = BeanPropertyBindingResult(SelectOrganisationRequest(null), "request")
        binding.addError(ObjectError("request", null, null, null))
        val exception =
            MethodArgumentNotValidException(MethodParameter(dummyBodyMethod(), 0), binding)
        val response =
            ApiExceptionHandler().validation(
                exception,
                MockHttpServletRequest("POST", "/auth/select-organisation"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals(mapOf("request" to listOf("Invalid value")), response.body?.fieldErrors)
    }

    @Test
    fun `api exception handler maps method validation response`() {
        val exception = mock(HandlerMethodValidationException::class.java)
        val result =
            ParameterValidationResult(
                MethodParameter(dummyIdMethod(), 0),
                "bad",
                listOf(ObjectError("id", "must be a UUID")),
                null,
                null,
                null,
                { resolvable, _ -> resolvable },
            )
        `when`(exception.parameterValidationResults).thenReturn(listOf(result))
        `when`(exception.message).thenReturn("method validation failed")

        val response =
            ApiExceptionHandler().methodValidation(
                exception,
                MockHttpServletRequest("GET", "/bad"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals(mapOf("id" to listOf("must be a UUID")), response.body?.fieldErrors)
    }

    @Test
    fun `api exception handler returns type mismatch response`() {
        val exception =
            MethodArgumentTypeMismatchException(
                "abc",
                UUID::class.java,
                "id",
                MethodParameter(dummyIdMethod(), 0),
                null,
            )
        val response =
            ApiExceptionHandler().typeMismatch(
                exception,
                MockHttpServletRequest("GET", "/organisations/abc"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("invalid_parameter", response.body?.code)
        assertEquals(
            "id",
            response.body
                ?.errors
                ?.single()
                ?.attribute,
        )
    }

    @Test
    fun `api exception handler returns type mismatch response for unknown type`() {
        val exception =
            MethodArgumentTypeMismatchException(
                "abc",
                null,
                "id",
                MethodParameter(dummyIdMethod(), 0),
                null,
            )
        val response =
            ApiExceptionHandler().typeMismatch(
                exception,
                MockHttpServletRequest("GET", "/organisations/abc"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals(
            "Invalid value 'abc' for parameter 'id'. Expected type: unknown",
            response.body?.message,
        )
    }

    @Test
    fun `api exception handler maps missing parameter response`() {
        val response =
            ApiExceptionHandler().missingParameter(
                MissingServletRequestParameterException("organisationId", "UUID"),
                MockHttpServletRequest("GET", "/auth/select-organisation"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("missing_parameter", response.body?.code)
        assertEquals(
            "organisationId",
            response.body
                ?.errors
                ?.single()
                ?.attribute,
        )
    }

    @Test
    fun `api exception handler maps invalid json response`() {
        val response =
            ApiExceptionHandler().invalidJson(
                HttpMessageNotReadableException("bad json", mock(HttpInputMessage::class.java)),
                MockHttpServletRequest("POST", "/auth/select-organisation"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("invalid_json", response.body?.code)
        assertEquals(
            "request_body",
            response.body
                ?.errors
                ?.single()
                ?.attribute,
        )
    }

    @Test
    fun `api exception handler maps missing resource response`() {
        val response =
            ApiExceptionHandler().noResource(
                NoResourceFoundException(HttpMethod.GET, "/missing", "No resource found"),
                MockHttpServletRequest("GET", "/missing"),
            )

        assertEquals(404, response.statusCode.value())
        assertEquals("resource_not_found", response.body?.code)
    }

    @Test
    fun `api exception handler maps method not allowed response`() {
        val response =
            ApiExceptionHandler().methodNotAllowed(
                HttpRequestMethodNotSupportedException("PATCH", listOf("GET", "POST")),
                MockHttpServletRequest("PATCH", "/auth/select-organisation"),
            )

        assertEquals(405, response.statusCode.value())
        assertEquals("method_not_allowed", response.body?.code)
        assertEquals(
            "http_method",
            response.body
                ?.errors
                ?.single()
                ?.attribute,
        )
    }

    @Test
    fun `api exception handler maps method not allowed without supported methods`() {
        val response =
            ApiExceptionHandler().methodNotAllowed(
                HttpRequestMethodNotSupportedException("PATCH"),
                MockHttpServletRequest("PATCH", "/auth/select-organisation"),
            )

        assertEquals(405, response.statusCode.value())
        assertEquals("method_not_allowed", response.body?.code)
    }

    @Test
    fun `api exception handler maps response status exceptions`() {
        val response =
            ApiExceptionHandler().responseStatus(
                ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Missing"),
                MockHttpServletRequest("GET", "/missing"),
            )

        assertEquals(404, response.statusCode.value())
        assertEquals("response_status_error", response.body?.code)
        assertEquals("Missing", response.body?.message)
    }

    @Test
    fun `api exception handler maps response status without reason`() {
        val response =
            ApiExceptionHandler().responseStatus(
                ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST),
                MockHttpServletRequest("GET", "/bad"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("Request failed", response.body?.message)
    }

    @Test
    fun `api exception handler maps illegal argument response`() {
        val response =
            ApiExceptionHandler().illegalArgument(
                IllegalArgumentException("invalid organisationId"),
                MockHttpServletRequest("GET", "/bad"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("invalid_argument", response.body?.code)
        assertEquals(
            "request",
            response.body
                ?.errors
                ?.single()
                ?.attribute,
        )
    }

    @Test
    fun `api exception handler maps illegal argument without message`() {
        val response =
            ApiExceptionHandler().illegalArgument(
                IllegalArgumentException(),
                MockHttpServletRequest("GET", "/bad"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("Invalid argument provided", response.body?.message)
        assertEquals(
            "Invalid argument",
            response.body
                ?.errors
                ?.single()
                ?.message,
        )
    }

    @Test
    fun `api exception handler includes trace id when available`() {
        val tracer = mock(Tracer::class.java)
        val span = mock(Span::class.java)
        val context = mock(TraceContext::class.java)
        `when`(tracer.currentSpan()).thenReturn(span)
        `when`(span.context()).thenReturn(context)
        `when`(context.traceId()).thenReturn("trace-123")

        val response =
            ApiExceptionHandler(
                tracer,
            ).unexpected(RuntimeException("boom"), MockHttpServletRequest("GET", "/x"))

        assertEquals("trace-123", response.body?.traceId)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `api exception handler maps constraint violations`() {
        val violation = mock(ConstraintViolation::class.java) as ConstraintViolation<Any>
        val path = mock(jakarta.validation.Path::class.java)
        `when`(path.toString()).thenReturn("request.name")
        `when`(violation.propertyPath).thenReturn(path)
        `when`(violation.message).thenReturn("must not be blank")
        val response =
            ApiExceptionHandler().constraintViolation(
                ConstraintViolationException(setOf(violation)),
                MockHttpServletRequest("GET", "/bad"),
            )

        assertEquals(400, response.statusCode.value())
        assertEquals("validation_failed", response.body?.code)
        assertEquals(listOf("must not be blank"), response.body?.fieldErrors?.get("name"))
    }

    @Test
    fun `api exception handler hides unexpected exception details`() {
        val response =
            ApiExceptionHandler().unexpected(
                RuntimeException("database password leaked"),
                MockHttpServletRequest("GET", "/x"),
            )

        assertEquals(500, response.statusCode.value())
        assertEquals("internal_error", response.body?.code)
        assertEquals("An unexpected error occurred", response.body?.message)
    }

    @Test
    fun `user profile controller returns active tenant profile`() {
        val principal =
            principal(
                permissions = setOf("iam.profile.read", "iam.user.invite"),
                branchId = HEAD_OFFICE_BRANCH_ID,
            )
        val controller =
            UserProfileController(
                UserProfileService(StaticUserProfileLookup(principal)),
            )

        val response = controller.me(principal)

        assertEquals(principal.userId, response.userId)
        assertEquals("FINAXIS-LOCAL", response.organisation.code)
        assertEquals(HEAD_OFFICE_BRANCH_ID, response.selectedBranch?.id)
        assertEquals(listOf("iam.profile.read", "iam.user.invite"), response.permissions)
        assertEquals(listOf("local-admin"), response.roles.map { it.code })
    }

    private fun jwt(): Jwt =
        Jwt
            .withTokenValue("token")
            .header("alg", "none")
            .subject("subject")
            .build()

    private fun jwtAuthentication(): JwtAuthenticationToken = JwtAuthenticationToken(jwt())

    private fun principal(
        permissions: Set<String>,
        branchId: UUID?,
    ): AppPrincipal =
        AppPrincipal(
            userId = UUID.randomUUID(),
            keycloakSubject = "subject",
            organisationId = ORGANISATION_ID,
            membershipId = MEMBERSHIP_ID,
            branchId = branchId,
            email = "admin@finaxis.local",
            fullName = "Local Admin",
            permissions = permissions,
        )

    private fun dummyBodyMethod(): Method =
        WebAdapterUnitTests::class.java.getDeclaredMethod(
            "dummyEndpoint",
            SelectOrganisationRequest::class.java,
        )

    private fun dummyIdMethod(): Method =
        WebAdapterUnitTests::class.java.getDeclaredMethod("dummyIdEndpoint", UUID::class.java)

    @Suppress("unused")
    fun dummyEndpoint(request: SelectOrganisationRequest) = request

    @Suppress("unused")
    fun dummyIdEndpoint(id: UUID) = id
}

private class StaticMembershipLookup(
    private val organisationId: UUID,
    private val membershipId: UUID,
    private val branchIds: List<UUID> = emptyList(),
) : MembershipSelectionLookup {
    val userId = UUID.randomUUID()

    override fun findUserIdByKeycloakSubject(keycloakSubject: String): UUID = userId

    override fun findMembership(
        userId: UUID,
        organisationId: UUID,
    ): MembershipSelection? {
        if (this.userId != userId || this.organisationId != organisationId) {
            return null
        }
        return MembershipSelection(membershipId, userId, organisationId, MembershipStatus.ACTIVE)
    }

    override fun findAssignedBranchIds(membershipId: UUID): List<UUID> = branchIds

    override fun hasAssignedBranch(
        membershipId: UUID,
        branchId: UUID,
    ): Boolean = branchId in branchIds
}

private class StaticUserProfileLookup(
    private val principal: AppPrincipal,
) : UserProfileLookup {
    override fun organisation(organisationId: UUID): ProfileOrganisation? =
        ProfileOrganisation(
            id = organisationId,
            code = "FINAXIS-LOCAL",
            name = "Finaxis Local Organisation",
            status = OrganisationStatus.ACTIVE,
        ).takeIf { organisationId == principal.organisationId }

    override fun membership(membershipId: UUID): ProfileMembership? =
        ProfileMembership(
            id = membershipId,
            status = MembershipStatus.ACTIVE,
        ).takeIf { membershipId == principal.membershipId }

    override fun assignedBranches(membershipId: UUID): List<ProfileBranch> =
        listOf(
            ProfileBranch(
                id = HEAD_OFFICE_BRANCH_ID,
                code = "HQ",
                name = "Head Office",
                status = "ACTIVE",
            ),
        ).takeIf { membershipId == principal.membershipId }.orEmpty()

    override fun assignedRoles(membershipId: UUID): List<ProfileRole> =
        listOf(
            ProfileRole(
                id = UUID.randomUUID(),
                code = "local-admin",
                name = "Local Administrator",
                status = RoleStatus.ACTIVE,
            ),
        ).takeIf { membershipId == principal.membershipId }.orEmpty()
}
