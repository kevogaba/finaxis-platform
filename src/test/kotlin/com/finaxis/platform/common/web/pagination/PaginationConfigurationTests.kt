package com.finaxis.platform.common.web.pagination

import com.finaxis.platform.common.web.api.InvalidPageRequestException
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaginationConfigurationTests {
    private val properties = PaginationProperties()
    private val interceptor =
        PaginationConfiguration().paginationParameterValidationInterceptor(
            properties,
        )

    @Test
    fun `uses public defaults of 25 with a hard maximum of 100`() {
        assertEquals(25, properties.defaultPageSize)
        assertEquals(100, properties.maxPageSize)
    }

    @Test
    fun `rejects malformed and out of range page parameters before MVC can clamp them`() {
        val invalidParameters =
            listOf("page" to "-1", "size" to "0", "size" to "101", "size" to "not-a-number")

        invalidParameters.forEach { (name, value) ->
            assertFailsWith<InvalidPageRequestException> {
                interceptor.preHandle(
                    MockHttpServletRequest(
                        "GET",
                        "/api/v1/widgets",
                    ).apply { addParameter(name, value) },
                    MockHttpServletResponse(),
                    Any(),
                )
            }
        }
    }

    @Test
    fun `accepts valid pagination parameters`() {
        assertTrue(
            interceptor.preHandle(
                MockHttpServletRequest("GET", "/api/v1/widgets").apply {
                    addParameter("page", "0")
                    addParameter("size", "100")
                },
                MockHttpServletResponse(),
                Any(),
            ),
        )
    }
}
