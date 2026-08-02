package com.finaxis.platform.common.audit

/**
 * Bounded, content-free identifier for an exception, safe to persist as an audit `reason`.
 * [SensitiveDataRedactor] only covers metadata/before/after maps, and audit read APIs return
 * `reason` to callers verbatim, so a caught exception's raw message - which can carry request
 * values, entity identifiers, or SQL detail - must never be persisted there directly.
 */
fun Throwable.toAuditFailureReason(): String = javaClass.simpleName
