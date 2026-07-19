package com.finaxis.platform.iam.domain

/**
 * Lifecycle state for a global application user.
 */
enum class UserStatus {
    DRAFT,
    PENDING_APPROVAL,
    PROVISIONING_IDP,
    INVITED,
    ACTIVE,
    SUSPENDED,
    LOCKED,
    DEACTIVATING,
    DEACTIVATED,
    ARCHIVED,
}

/** Whether this user lifecycle state may establish an authenticated application context. */
fun UserStatus.allowsLogin(): Boolean = this == UserStatus.ACTIVE || this == UserStatus.INVITED

/**
 * Lifecycle state for an organisation tenant.
 */
enum class OrganisationStatus {
    DRAFT,
    PENDING_APPROVAL,
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    DEPROVISIONING,
    DEPROVISIONED,
    REJECTED,
    ARCHIVED,
}

/**
 * Lifecycle state for a user's organisation membership.
 */
enum class MembershipStatus {
    PENDING_APPROVAL,
    ACTIVE,
    SUSPENDED,
    REVOKED,
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
    ARCHIVED,
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
