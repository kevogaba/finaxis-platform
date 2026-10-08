package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.rootCauseClassNameOrUnavailable
import com.finaxis.platform.common.audit.toMessageFreeStackTraceOrUnavailable
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.IdentityHashMap

/**
 * What a background-job handler throws to JobRunr in place of the failure it has already recorded.
 *
 * JobRunr logs the exception it is given with its message and cause chain, stores its message,
 * its cause's message and its full stack text in `jobrunr_jobs`, and the production Sentry
 * appender ships that log line. The original exception's message can carry an email address, SQL
 * parameters or identity-provider output, so the handler records or logs the failure first (the
 * closed [InitialAdministratorBootstrapFailureCode], the audit row, the dispatch row, and a
 * message-free log line naming the exception class, the root-cause class and the frames) and
 * then throws this instead: [safeDetail] is the closed code or an exception class name, never a
 * message, and there is **no cause**, except a message-free [InterruptedException] when the
 * original chain held one, because JobRunr recognises a stopped server or a deleted job by an
 * `InterruptedException` in the cause chain. It is a plain [RuntimeException], which JobRunr
 * retries with its default count and backoff exactly as it retried the original; a handler that
 * must fail permanently throws a `JobRunrException(…, doNotRetry = true)` of its own, also built
 * without the original as its cause.
 */
class SanitisedJobFailureException(
    safeDetail: String,
    interrupted: Boolean = false,
) : RuntimeException(safeDetail, if (interrupted) InterruptedException() else null) {
    /** Factories for the sanitised failure. */
    companion object {
        private val log = LoggerFactory.getLogger(SanitisedJobFailureException::class.java)

        /**
         * The sanitised failure whose message is the closed code chosen from [failure]'s type, for
         * a handler whose failure has already been recorded (and logged) by the recorder.
         */
        fun forFailure(failure: Throwable): SanitisedJobFailureException {
            val code = InitialAdministratorBootstrapFailureCode.from(failure)
            return SanitisedJobFailureException(code.name, failure.holdsInterruption())
        }

        /**
         * Logs [failure] at `WARN` without its message, then returns the sanitised failure: for a
         * handler path on which no recorder line is written, so an operator still finds the
         * exception class, the root-cause class and the frames. [context] must name ids only.
         * [detail] is the closed code unless the handler gives the exception class name.
         */
        fun logged(
            failure: Throwable,
            context: String,
            detail: String? = null,
        ): SanitisedJobFailureException {
            logFailure(failure, context)
            val code = InitialAdministratorBootstrapFailureCode.from(failure).name
            return SanitisedJobFailureException(detail ?: code, failure.holdsInterruption())
        }

        /** Logs [failure] at `WARN` without its message; the throwable is never passed on. */
        fun logFailure(
            failure: Throwable,
            context: String,
        ) {
            log.warn(
                "{} failed: exceptionClass={} rootCauseClass={}\n{}",
                context,
                failure.javaClass.name,
                failure.rootCauseClassNameOrUnavailable(),
                failure.toMessageFreeStackTraceOrUnavailable(),
            )
        }

        private fun Throwable.holdsInterruption(): Boolean =
            runCatching {
                val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
                generateSequence(this) { it.cause }
                    .takeWhile { seen.add(it) }
                    .any { it is InterruptedException }
            }.getOrDefault(false)
    }
}
