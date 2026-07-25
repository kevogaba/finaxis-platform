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

    /** Tenant membership management endpoint namespace. */
    const val MEMBERSHIPS = "$TENANT/memberships"

    /** Tenant branch-assignment management endpoint namespace. */
    const val BRANCH_ASSIGNMENTS = "$TENANT/branch-assignments"

    /** Tenant role management endpoint namespace. */
    const val ROLES = "$TENANT/roles"

    /** Tenant role-assignment management endpoint namespace. */
    const val ROLE_ASSIGNMENTS = "$TENANT/role-assignments"

    /** Tenant permission catalogue endpoint namespace. */
    const val PERMISSIONS = "$TENANT/permissions"

    /** Tenant user management endpoint namespace. */
    const val TENANT_USERS = "$TENANT/users"

    /** Platform-wide user lifecycle endpoint namespace. */
    const val PLATFORM_USERS = "$PLATFORM/users"

    /** Tenant setup-level settings endpoint namespace. */
    const val TENANT_SETTINGS = "$TENANT/settings"

    /** Tenant controlled business date endpoint namespace (singleton resource). */
    const val BUSINESS_DATE = "$TENANT/business-date"

    /** Tenant audit event administration endpoint namespace. */
    const val AUDIT_EVENTS = "$TENANT/audit-events"
}
