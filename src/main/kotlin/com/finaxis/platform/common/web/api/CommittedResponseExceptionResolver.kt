package com.finaxis.platform.common.web.api

import com.finaxis.platform.common.audit.rootCauseClassNameOrUnavailable
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView

/**
 * A failure after the response has started must never be answered with a problem document: the
 * status and headers are gone, so the document would be appended to whatever body was already
 * sent, and a streamed CSV would end looking complete (ADR 0039, point 12). This resolver runs
 * ahead of every other, and for a committed response it writes nothing and **always aborts**:
 *
 * - the failure is logged once, by exception class names only (never a message or the throwable,
 *   which can carry SQL or an email address), and rethrown as a [ResponseAbortedException], so the
 *   container abandons the transfer and the client sees an incomplete response. The rethrown
 *   exception carries no cause and no message of the original, so the container's own error log is
 *   message-free too;
 * - what the failure *is* only picks the log level: a genuine **client disconnect** (see
 *   [isClientAbort]) is logged at `DEBUG`, anything else at `WARN`. It never changes the outcome,
 *   so a mis-classified failure (a lost database connection looks a little like a client that left)
 *   costs a log level and can never end a partial export as a normal, complete-looking response;
 * - the one quiet exception is the container's own error-page dispatch (`ERROR`, `INCLUDE`,
 *   `FORWARD`: Spring Boot's `/error` is included after the first failure and finds the response
 *   committed). The first dispatch has already aborted the transfer, so this one is handled
 *   without a second log line.
 *
 * It only acts when the response is already committed, so every response that has not started is
 * answered by [ApiExceptionHandler] exactly as before. It never writes, so when the async timeout
 * dispatches while a stream thread is still writing, the two cannot interleave bytes.
 */
@Component
class CommittedResponseExceptionResolver :
    HandlerExceptionResolver,
    Ordered {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        if (!response.isCommitted) return null
        if (request.dispatcherType in SECONDARY_DISPATCHES) {
            logger.debug(
                "Follow-up dispatch over a committed response for {} ({}): exceptionClass={}",
                request.requestURI,
                request.dispatcherType,
                ex.javaClass.name,
            )
            return ModelAndView()
        }
        val message =
            "Failure after the response started, aborting the transfer for {} ({}): " +
                "exceptionClass={} rootCauseClass={}"
        val arguments =
            arrayOf(
                request.requestURI,
                request.dispatcherType,
                ex.javaClass.name,
                ex.rootCauseClassNameOrUnavailable(),
            )
        if (ex.isClientAbort()) {
            logger.debug(
                message,
                *arguments,
            )
        } else {
            logger.warn(message, *arguments)
        }
        throw ResponseAbortedException(ex.javaClass.name)
    }

    /**
     * True for the exceptions a disconnected client causes, by class name so no servlet-container
     * class is needed at compile time, as Spring's `DisconnectedClientHelper` does: a container
     * or Spring async class (`ClientAbortException`, `EofException`,
     * `AsyncRequestNotUsableException`), or an `IOException` (a bare `EOFException` included)
     * whose message says broken pipe or connection reset. The walk follows the causes only
     * through `IOException`s: a non-I/O wrapper such as a `DataAccessException` or a
     * `SQLException` ends it, so a lost database connection (`PSQLException` over
     * `EOFException`, or `SocketException("Connection reset")`) is not a client abort. The message
     * is read only for this test, never logged.
     */
    private fun Throwable.isClientAbort(): Boolean =
        generateSequence<Throwable>(this) { current ->
            current.cause?.takeIf { current is java.io.IOException }
        }.take(MAX_CAUSES).any { cause ->
            cause.javaClass.simpleName in CLIENT_ABORT_CLASSES ||
                (
                    cause is java.io.IOException &&
                        (cause is java.io.EOFException || cause.mentionsDisconnect())
                )
        }

    private fun Throwable.mentionsDisconnect(): Boolean =
        message?.lowercase()?.let { text -> CLIENT_ABORT_TEXTS.any { text.contains(it) } } == true

    private companion object {
        const val MAX_CAUSES = 8

        /**
         * The container's own error-page dispatches that follow a failure already handled by the
         * first dispatch.
         */
        val SECONDARY_DISPATCHES =
            setOf(DispatcherType.ERROR, DispatcherType.INCLUDE, DispatcherType.FORWARD)
        val CLIENT_ABORT_CLASSES =
            setOf("ClientAbortException", "EofException", "AsyncRequestNotUsableException")
        val CLIENT_ABORT_TEXTS = listOf("broken pipe", "connection reset")
    }
}

/**
 * Thrown for a failure after the response started, in place of the original exception, so the
 * container aborts the transfer. It names the original exception's class only.
 */
class ResponseAbortedException(
    failureClass: String,
) : RuntimeException("The response had already started; transfer aborted after $failureClass.")
