package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextProperties
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.mock.web.MockHttpServletRequest

class ActiveOrganisationContextResolverTests {

    private val contextService = ActiveOrganisationContextService(
        ActiveOrganisationContextProperties(secret = "test-secret-with-enough-length"),
        Clock.fixed(Instant.parse("2026-07-04T08:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `valid header context is resolved before session context`() {
        val headerContext = context()
        val sessionContext = context()
        val request = MockHttpServletRequest().apply {
            addHeader(ActiveOrganisationContextService.HEADER, contextService.issue(headerContext))
            getSession(true)!!.setAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE, sessionContext)
        }
        val resolver = CompositeActiveOrganisationContextResolver(
            HeaderActiveOrganisationContextResolver(contextService),
            SessionActiveOrganisationContextResolver(),
        )

        val resolution = resolver.resolve(request)

        assertEquals(headerContext, resolution.context)
        assertNull(resolution.failureMessage)
    }

    @Test
    fun `invalid header fails closed and does not fall back to session`() {
        val request = MockHttpServletRequest().apply {
            addHeader(ActiveOrganisationContextService.HEADER, "invalid")
            getSession(true)!!.setAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE, context())
        }
        val resolver = CompositeActiveOrganisationContextResolver(
            HeaderActiveOrganisationContextResolver(contextService),
            SessionActiveOrganisationContextResolver(),
        )

        val resolution = resolver.resolve(request)

        assertNull(resolution.context)
        assertEquals("Invalid active organisation context header", resolution.failureMessage)
    }

    @Test
    fun `session context is used when header is absent`() {
        val sessionContext = context()
        val request = MockHttpServletRequest().apply {
            getSession(true)!!.setAttribute(SessionActiveOrganisationContextResolver.ATTRIBUTE, sessionContext)
        }
        val resolver = CompositeActiveOrganisationContextResolver(
            HeaderActiveOrganisationContextResolver(contextService),
            SessionActiveOrganisationContextResolver(),
        )

        val resolution = resolver.resolve(request)

        assertEquals(sessionContext, resolution.context)
        assertNull(resolution.failureMessage)
    }

    @Test
    fun `no active context is resolved when header and session are absent`() {
        val resolver = CompositeActiveOrganisationContextResolver(
            HeaderActiveOrganisationContextResolver(contextService),
            SessionActiveOrganisationContextResolver(),
        )

        val resolution = resolver.resolve(MockHttpServletRequest())

        assertNull(resolution.context)
        assertNull(resolution.failureMessage)
    }

    private fun context(): ActiveOrganisationContext {
        return ActiveOrganisationContext(
            userId = UUID.randomUUID(),
            organisationId = UUID.randomUUID(),
            membershipId = UUID.randomUUID(),
        )
    }
}
