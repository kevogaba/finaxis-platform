package com.finaxis.platform.lifecycle.adapter.inbound.web

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CallerContextResolverTests {
    @AfterTest
    fun reset() {
        RequestContexts.clear()
        SecurityContextHolder.clearContext()
    }

    private fun <T> inOrganisation(
        organisationId: java.util.UUID,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(organisationId),
                actor = ActorContext(uuidV7(), "subject", "user", "user@example.com"),
            ),
            block,
        )

    @Test
    fun `platform route without any context gets the platform context code`() {
        val failure =
            assertThrows<ForbiddenOperationException> {
                CallerContextResolver.getPlatformCaller()
            }

        assertEquals("platform_context_required", failure.code)
        assertEquals(
            "Reserved platform organisation context is required for this route.",
            failure.safeDetail,
        )
    }

    @Test
    fun `platform route from a tenant context gets the platform context code`() {
        val failure =
            assertThrows<ForbiddenOperationException> {
                inOrganisation(uuidV7()) { CallerContextResolver.getPlatformCaller() }
            }

        assertEquals("platform_context_required", failure.code)
    }

    @Test
    fun `tenant route without any context gets the tenant context code`() {
        val failure =
            assertThrows<ForbiddenOperationException> {
                CallerContextResolver.getTenantCaller()
            }

        assertEquals("tenant_context_required", failure.code)
        assertEquals("Active tenant context is required for this route.", failure.safeDetail)
    }

    @Test
    fun `tenant route from the platform organisation gets the tenant context code`() {
        val failure =
            assertThrows<ForbiddenOperationException> {
                inOrganisation(PlatformOrganisation.ID) { CallerContextResolver.getTenantCaller() }
            }

        assertEquals("tenant_context_required", failure.code)
        assertEquals(
            "This route is restricted to non-platform tenant context.",
            failure.safeDetail,
        )
    }
}
