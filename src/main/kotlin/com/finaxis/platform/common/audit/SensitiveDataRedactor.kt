package com.finaxis.platform.common.audit

/**
 * Explicit escape hatch that forces a metadata value to be redacted regardless of its key name.
 */
data class Redacted(
    val value: Any?,
)

/**
 * Masks sensitive values before audit metadata, before-state, or after-state summaries are
 * persisted or logged. Redaction replaces values rather than dropping keys, so the audit trail
 * still shows that a sensitive field changed.
 */
object SensitiveDataRedactor {
    private const val MASK = "***REDACTED***"

    private val SENSITIVE_KEY_FRAGMENTS =
        setOf(
            "token",
            "secret",
            "authorization",
            "password",
            "bearer",
            "cookie",
            "apikey",
            "nationalid",
            "taxid",
            "bankaccount",
            "phone",
            "email",
        )

    /** Returns [metadata] with sensitive values masked, recursing into nested maps/collections. */
    fun redact(metadata: Map<String, Any?>): Map<String, Any?> =
        metadata.mapValues { (key, value) -> redactValue(key, value) }

    private fun redactValue(
        key: String,
        value: Any?,
    ): Any? =
        when {
            value is Redacted -> MASK
            isSensitiveKey(key) -> MASK
            value is Map<*, *> -> redactNested(value)
            value is Collection<*> -> redactCollection(value)
            else -> value
        }

    @Suppress("UNCHECKED_CAST")
    private fun redactNested(value: Map<*, *>): Map<String, Any?> =
        redact(value as Map<String, Any?>)

    private fun redactCollection(value: Collection<*>): List<Any?> = value.map(::redactElement)

    private fun redactElement(element: Any?): Any? =
        when {
            element is Redacted -> MASK
            element is Map<*, *> -> redactNested(element)
            element is Collection<*> -> redactCollection(element)
            else -> element
        }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = normalize(key)
        return SENSITIVE_KEY_FRAGMENTS.any { fragment -> normalized.contains(normalize(fragment)) }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace("_", "").replace("-", "")
}
