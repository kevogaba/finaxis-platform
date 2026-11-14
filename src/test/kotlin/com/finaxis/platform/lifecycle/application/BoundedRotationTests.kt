package com.finaxis.platform.lifecycle.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The windows a bounded scheduled job takes its candidates in (#277, ADR 0041). */
class BoundedRotationTests {
    @Test
    fun `every candidate is read when they fit in one run`() {
        listOf(0, 1, 500).forEach { total ->
            assertEquals(0, BoundedRotation.windowOffset(total, 500, slot = 7))
        }
    }

    @Test
    fun `successive slots take successive windows and wrap round`() {
        val offsets = (0L..3L).map { BoundedRotation.windowOffset(1200, 500, it) }

        assertEquals(listOf(0, 500, 1000, 0), offsets)
    }

    @Test
    fun `the windows of one cycle cover every candidate exactly once`() {
        val total = 7
        val limit = 3
        val read =
            (0L until 3L).flatMap { slot ->
                val offset = BoundedRotation.windowOffset(total, limit, slot)
                (offset until minOf(offset + limit, total)).toList()
            }

        assertEquals((0 until total).toList(), read.sorted())
    }

    @Test
    fun `a negative slot still lands on a window`() {
        assertEquals(500, BoundedRotation.windowOffset(1200, 500, slot = -2))
        assertFailsWith<IllegalArgumentException> { BoundedRotation.windowOffset(10, 0, 0) }
    }
}
