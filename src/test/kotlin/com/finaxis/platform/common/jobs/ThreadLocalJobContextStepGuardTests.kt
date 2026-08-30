package com.finaxis.platform.common.jobs

import org.jobrunr.jobs.JobTestBuilder.aJob
import org.jobrunr.server.runner.MockThreadLocalJobContext
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThreadLocalJobContextStepGuardTests {
    private val guard = ThreadLocalJobContextStepGuard()

    @Test
    fun `runs the action once for a fresh job`() {
        var calls = 0

        MockThreadLocalJobContext().use {
            MockThreadLocalJobContext.setUpJobContextForJob(aJob().withEnqueuedState().build())
            guard.runOnce("test-step") { calls++ }
        }

        assertEquals(1, calls)
    }

    @Test
    fun `skips the action on a retry once the step already completed`() {
        var calls = 0
        val job = aJob().withEnqueuedState().build()

        MockThreadLocalJobContext().use {
            MockThreadLocalJobContext.setUpJobContextForJob(job)
            guard.runOnce("test-step") { calls++ }
        }
        MockThreadLocalJobContext().use {
            MockThreadLocalJobContext.setUpJobContextForJob(job)
            guard.runOnce("test-step") { calls++ }
        }

        assertEquals(1, calls)
    }

    @Test
    fun `propagates the original exception type, not JobRunr's wrapper`() {
        class BoomException(
            message: String,
        ) : RuntimeException(message)

        val exception =
            MockThreadLocalJobContext().use {
                MockThreadLocalJobContext.setUpJobContextForJob(aJob().withEnqueuedState().build())
                assertFailsWith<BoomException> {
                    guard.runOnce("boom-step") { throw BoomException("delivery failed") }
                }
            }

        assertTrue(exception.message == "delivery failed")
    }

    @Test
    fun `propagates a non-RuntimeException cause too`() {
        val exception =
            MockThreadLocalJobContext().use {
                MockThreadLocalJobContext.setUpJobContextForJob(aJob().withEnqueuedState().build())
                assertFailsWith<IOException> {
                    guard.runOnce("io-step") { throw IOException("disk full") }
                }
            }

        assertTrue(exception.message == "disk full")
    }
}
