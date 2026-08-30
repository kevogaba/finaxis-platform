package com.finaxis.platform.common.jobs

/**
 * Runs a background-job step at most once across JobRunr retries, keyed by [step]'s name.
 */
fun interface JobStepGuard {
    /** Executes [action] unless a prior attempt of this job already completed [step]. */
    fun runOnce(
        step: String,
        action: () -> Unit,
    )
}
