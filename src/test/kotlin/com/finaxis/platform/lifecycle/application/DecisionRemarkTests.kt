package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.InvalidRequestException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DecisionRemarkTests {
    @Test
    fun `an optional remark is trimmed`() {
        assertEquals("Audit complete", DecisionRemark.optional("  Audit complete \n")?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t\n  "])
    fun `an absent or blank optional remark normalises to null`(raw: String) {
        assertNull(DecisionRemark.optional(null))
        assertNull(DecisionRemark.optional(raw))
    }

    @Test
    fun `an optional remark accepts a single character and exactly 500`() {
        assertEquals("x", DecisionRemark.optional("x")?.value)
        assertEquals(500, DecisionRemark.optional("x".repeat(500))?.value?.length)
    }

    @Test
    fun `an optional remark over 500 characters is a validation failure`() {
        val failure =
            assertFailsWith<InvalidRequestException> { DecisionRemark.optional("x".repeat(501)) }

        assertEquals("validation_failed", failure.code)
    }

    @Test
    fun `the 500 character bound applies after trimming`() {
        assertEquals(500, DecisionRemark.optional("  " + "x".repeat(500) + "  ")?.value?.length)
        assertFailsWith<InvalidRequestException> {
            DecisionRemark.optional(" " + "x".repeat(501) + " ")
        }
    }

    @Test
    fun `the failure message never echoes the rejected text`() {
        val failure =
            assertFailsWith<InvalidRequestException> {
                DecisionRemark.optional("s3cret-" + "x".repeat(600))
            }

        assertEquals(false, failure.safeDetail.contains("s3cret"))
    }

    @Test
    fun `remarks with the same text are equal`() {
        assertEquals(DecisionRemark.optional("abc"), DecisionRemark.optional(" abc "))
        assertEquals("abc", DecisionRemark.optional("abc").toString())
    }
}
