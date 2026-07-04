package com.finaxis.platform.iam.domain

/**
 * Lifecycle state for a global application user.
 */
enum class UserStatus {
    ACTIVE,
    SUSPENDED,
    DISABLED,
}

/**
 * Lifecycle state for an organisation tenant.
 */
enum class OrganisationStatus {
    ACTIVE,
    SUSPENDED,
    ARCHIVED,
}

/**
 * Lifecycle state for a user's organisation membership.
 */
enum class MembershipStatus {
    ACTIVE,
    SUSPENDED,
    LEFT,
}

/**
 * Lifecycle state for permission catalogue entries.
 */
enum class PermissionStatus {
    ACTIVE,
    DEPRECATED,
    DISABLED,
}

/**
 * Lifecycle state for role permission bundles.
 */
enum class RoleStatus {
    ACTIVE,
    DISABLED,
}

/**
 * Direct membership permission effect.
 */
enum class PermissionEffect {
    ALLOW,
    DENY,
}

/**
 * Actor categories supported by the audit model.
 */
enum class AuditActorType {
    USER,
    SYSTEM,
    SERVICE_ACCOUNT,
    INTEGRATION,
    MIGRATION,
    SCHEDULER,
}
