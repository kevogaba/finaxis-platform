package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.InvalidRequestException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReasonTests {
    @Test
    fun `a required reason is trimmed and accepts three to 500 characters`() {
        assertEquals("abc", Reason.required("  abc  ").value)
        assertEquals(500, Reason.required("x".repeat(500)).value.length)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "ab", "  ab  ", "\t\n"])
    fun `a required reason that is blank or shorter than three is rejected`(raw: String) {
        val failure = assertFailsWith<InvalidRequestException> { Reason.required(raw) }

        assertEquals("validation_failed", failure.code)
    }

    @Test
    fun `a required reason that is absent or over 500 characters is rejected`() {
        assertFailsWith<InvalidRequestException> { Reason.required(null) }
        assertFailsWith<InvalidRequestException> { Reason.required("x".repeat(501)) }
    }

    @Test
    fun `the 500 character bound applies after trimming`() {
        assertEquals(500, Reason.required("  " + "x".repeat(500) + "  ").value.length)
        assertFailsWith<InvalidRequestException> {
            Reason.required(" " + "x".repeat(501) + " ")
        }
    }

    @Test
    fun `the failure message never echoes the rejected text`() {
        val failure =
            assertFailsWith<InvalidRequestException> {
                Reason.required(
                    "s3cret-" + "x".repeat(600),
                )
            }

        assertEquals(false, failure.safeDetail.contains("s3cret"))
    }

    @Test
    fun `reasons with the same text are equal`() {
        assertEquals(Reason.required("abc"), Reason.required(" abc "))
        assertEquals("abc", Reason.required("abc").toString())
    }

    @Test
    fun `the bounds are the documented ones`() {
        assertEquals(3, Reason.MIN_LENGTH)
        assertEquals(500, Reason.MAX_LENGTH)
        assertEquals(Reason.MAX_LENGTH, DecisionRemark.MAX_LENGTH)
    }
}
