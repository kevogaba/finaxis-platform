package com.finaxis.platform.common.web.versioning

/**
 * Canonical public API path constants.
 */
object ApiPaths {
    /** Baseline public API namespace. */
    const val API_V1 = "/api/v1"

    /** Authentication and active-context API namespace. */
    const val AUTH = "$API_V1/auth"

    /** Reserved platform administration namespace. */
    const val PLATFORM = "$API_V1/platform"

    /** Platform tenant administration endpoint namespace. */
    const val PLATFORM_TENANTS = "$PLATFORM/tenants"

    /** Active tenant endpoint namespace. */
    const val TENANT = "$API_V1/tenant"

    /** Branch management endpoint namespace. */
    const val BRANCHES = "$API_V1/branches"
}
