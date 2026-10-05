package com.finaxis.platform.common.audit

import java.util.Collections
import java.util.IdentityHashMap

private const val DEFAULT_MAX_FRAMES = 25
private const val DEFAULT_MAX_CAUSES = 8
private const val NATIVE_METHOD_LINE = -2

/**
 * Class name of the innermost cause of this throwable, or its own class name when it has none.
 * Content-free: a class name never carries request values, SQL or an email address, unlike
 * [Throwable.message]. The walk is cycle-safe and bounded by the length of the cause chain.
 */
fun Throwable.rootCauseClassName(): String {
    val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
    var current: Throwable = this
    while (seen.add(current)) {
        current = current.cause ?: break
    }
    return current.javaClass.name
}

/**
 * Renders this throwable's stack for an operator log **without any exception message**: for the
 * throwable and then each cause, the class name followed by `at Class.method(File:line)` frames,
 * bounded to [maxFrames] frames per throwable and [maxCauses] causes (a chain cut at the bound ends
 * with `... further causes omitted`). A stack built with
 * [Throwable.printStackTrace] or passed to a logger as its throwable argument also prints
 * [Throwable.message] (and every cause's, and every suppressed exception's), which can carry
 * request values, SQL parameters, identity-provider output or an email address; this never
 * reads [Throwable.message], [Throwable.localizedMessage] or [Throwable.suppressed], and the
 * walk is cycle-safe.
 */
fun Throwable.toMessageFreeStackTrace(
    maxFrames: Int = DEFAULT_MAX_FRAMES,
    maxCauses: Int = DEFAULT_MAX_CAUSES,
): String {
    val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
    val out = StringBuilder()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < maxCauses && seen.add(current)) {
        out.append(if (depth == 0) "" else "\nCaused by: ").append(current.javaClass.name)
        val frames = current.stackTrace
        frames.take(maxFrames).forEach { out.append("\n\tat ").append(it.render()) }
        if (frames.size > maxFrames) {
            out.append("\n\t... ").append(frames.size - maxFrames).append(" more")
        }
        current = current.cause
        depth++
    }
    if (current != null && current !in seen) {
        out.append("\n... further causes omitted")
    }
    return out.toString()
}

private fun StackTraceElement.render(): String {
    val location =
        when {
            lineNumber == NATIVE_METHOD_LINE -> "Native Method"
            fileName == null -> "Unknown Source"
            lineNumber >= 0 -> "$fileName:$lineNumber"
            else -> fileName
        }
    return "$className.$methodName($location)"
}

/**
 * [rootCauseClassName] for a log line: a throwable whose `getCause` throws yields `<unavailable>`
 * rather than an exception, so rendering a failure can never mask or replace it.
 */
fun Throwable.rootCauseClassNameOrUnavailable(): String =
    runCatching { rootCauseClassName() }.getOrElse { UNAVAILABLE }

/**
 * [toMessageFreeStackTrace] for a log line: a throwable whose `getStackTrace` or `getCause`
 * throws yields its failure's class name instead of an exception, so rendering a failure can
 * never mask or replace it.
 */
fun Throwable.toMessageFreeStackTraceOrUnavailable(): String =
    runCatching { toMessageFreeStackTrace() }
        .getOrElse { "<stack unavailable: ${it.javaClass.name}>" }

private const val UNAVAILABLE = "<unavailable>"
