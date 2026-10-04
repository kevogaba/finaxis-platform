package com.finaxis.platform.common.web.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Verifies the shared page and sort guards raise the 400-mapped exception, never a bare one. */
class PageRequestValidationTests {
    private val allowed = setOf("roleCode", "createdAt")

    @Test
    fun `a page inside the bounds is accepted`() {
        requireValidPage(0, 1)
        requireValidPage(7, 100)
    }

    @Test
    fun `a negative page is rejected without a violation`() {
        assertNull(rejected { requireValidPage(-1, 25) }.parameter)
    }

    @Test
    fun `a size outside the bounds is rejected without a violation`() {
        assertNull(rejected { requireValidPage(0, 0) }.parameter)
        assertNull(rejected { requireValidPage(0, 101) }.parameter)
        assertNull(rejected { requireValidPage(0, Int.MIN_VALUE) }.parameter)
    }

    @Test
    fun `absent and allowed sort values are accepted in any letter case`() {
        requireValidSort(null, null, allowed)
        requireValidSort("roleCode", null, allowed)
        requireValidSort(null, "desc", allowed)
        requireValidSort("createdAt", "Asc", allowed)
    }

    @Test
    fun `an unknown sort field names sort_by and does not echo the value`() {
        val failure = rejected { requireValidSort("bogus", null, allowed) }

        assertEquals("sort_by", failure.parameter)
        assertFalse(failure.message!!.contains("bogus"))
        assertEquals("Sort field must be one of: createdAt, roleCode.", failure.message)
    }

    @Test
    fun `sort field matching is case sensitive because the allowed names are camelCase`() {
        assertEquals("sort_by", rejected { requireValidSort("rolecode", null, allowed) }.parameter)
    }

    @Test
    fun `an unknown sort direction names sort_dir`() {
        assertEquals("sort_dir", rejected { requireValidSort(null, "UP", allowed) }.parameter)
        assertEquals("sort_dir", rejected { requireValidSort(null, "", allowed) }.parameter)
    }

    private fun rejected(block: () -> Unit): InvalidPageRequestException =
        assertFailsWith<InvalidPageRequestException>(block = block)
}
