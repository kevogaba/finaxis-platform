package com.finaxis.platform.common.web.api

/** RFC 9457 problem response with Finaxis-safe extensions. */
data class ApiProblem(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String,
    val code: String,
    val requestId: String,
    val violations: List<ApiViolation>? = null,
)

/** A safe, field-specific validation failure. */
data class ApiViolation(
    val field: String,
    val code: String,
    val message: String,
)
