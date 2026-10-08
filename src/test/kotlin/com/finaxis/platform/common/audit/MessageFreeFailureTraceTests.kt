package com.finaxis.platform.common.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for the message-free failure rendering used in operator logs. */
class MessageFreeFailureTraceTests {
    private val email = "jane.doe@acme.test"
    private val sql = "insert into user_account (email) values ('jane.doe@acme.test')"

    private fun nested(): Throwable =
        IllegalStateException(
            "outer $email",
            IllegalArgumentException("middle $sql", UnsupportedOperationException(sql)),
        ).also { it.addSuppressed(RuntimeException("suppressed $email")) }

    @Test
    fun `the root cause class is the innermost cause or the throwable itself`() {
        assertEquals(
            UnsupportedOperationException::class.java.name,
            nested().rootCauseClassName(),
        )
        assertEquals(
            IllegalStateException::class.java.name,
            IllegalStateException(email).rootCauseClassName(),
        )
    }

    @Test
    fun `the rendering names every class and frame but holds no message`() {
        val trace = nested().toMessageFreeStackTrace()

        assertTrue(trace.startsWith(IllegalStateException::class.java.name))
        assertTrue(trace.contains("Caused by: ${IllegalArgumentException::class.java.name}"))
        assertTrue(trace.contains("Caused by: ${UnsupportedOperationException::class.java.name}"))
        assertTrue(trace.contains("\tat ${javaClass.name}.nested(MessageFreeFailureTraceTests.kt:"))
        assertFalse(trace.contains(email))
        assertFalse(trace.contains("insert into"))
        assertFalse(trace.contains("suppressed"))
        assertFalse(trace.contains("outer"))
    }

    @Test
    fun `frames and causes are bounded`() {
        val trace = nested().toMessageFreeStackTrace(maxFrames = 1, maxCauses = 2)

        assertTrue(trace.contains("more"))
        assertFalse(trace.contains(UnsupportedOperationException::class.java.name))
        assertEquals(2, trace.lines().count { it.startsWith("\tat ") })
        assertTrue(trace.endsWith("\n... further causes omitted"))
    }

    @Test
    fun `a chain that fits the bound carries no truncation marker`() {
        val trace = nested().toMessageFreeStackTrace(maxFrames = 1, maxCauses = 3)

        assertFalse(trace.contains("further causes omitted"))
        assertTrue(trace.contains(UnsupportedOperationException::class.java.name))
        assertFalse(
            nested().toMessageFreeStackTrace(maxCauses = 3).contains("further causes omitted"),
        )
    }

    @Test
    fun `a cause cycle terminates`() {
        val a = RuntimeException("a $email")
        val b = RuntimeException("b $email", a)
        a.initCause(b)

        val trace = a.toMessageFreeStackTrace()

        assertEquals(1, Regex("Caused by: ").findAll(trace).count())
        assertFalse(trace.contains(email))
        assertEquals(RuntimeException::class.java.name, a.rootCauseClassName())
    }
}
