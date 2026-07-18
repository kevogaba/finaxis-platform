package com.finaxis.platform.lifecycle

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TenantAdminOrganisationFixtureTests {
    @Test
    fun `withRequestContext restores a previously bound request context`() {
        val previous = ServletRequestAttributes(MockHttpServletRequest())
        RequestContextHolder.setRequestAttributes(previous)

        try {
            withRequestContext {
                assertNotSame(previous, RequestContextHolder.getRequestAttributes())
            }

            assertSame(previous, RequestContextHolder.getRequestAttributes())
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
    }
}
