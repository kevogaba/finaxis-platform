package com.finaxis.platform.common.jobs

import org.jobrunr.jobs.exceptions.StepExecutionException
import org.jobrunr.server.runner.ThreadLocalJobContext
import org.springframework.stereotype.Component

/**
 * Delegates to JobRunr's own [org.jobrunr.jobs.context.JobContext.runStepOnce], which persists
 * step-completion in the job's own metadata so a step is skipped on retry once it has succeeded.
 * `runStepOnce` always wraps any thrown exception in [StepExecutionException]; this class unwraps
 * it so callers see the original exception type from [action].
 */
@Component
class ThreadLocalJobContextStepGuard : JobStepGuard {
    override fun runOnce(
        step: String,
        action: () -> Unit,
    ) {
        try {
            ThreadLocalJobContext.getJobContext().runStepOnce(step) { action() }
        } catch (ex: StepExecutionException) {
            throw ex.cause ?: ex
        }
    }
}
