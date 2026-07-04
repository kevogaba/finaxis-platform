package com.finaxis.platform.iam.domain

enum class UserStatus {
    ACTIVE,
    SUSPENDED,
    DISABLED,
}

enum class OrganisationStatus {
    ACTIVE,
    SUSPENDED,
    ARCHIVED,
}

enum class MembershipStatus {
    ACTIVE,
    SUSPENDED,
    LEFT,
}

enum class PermissionStatus {
    ACTIVE,
    DEPRECATED,
    DISABLED,
}

enum class RoleStatus {
    ACTIVE,
    DISABLED,
}

enum class PermissionEffect {
    ALLOW,
    DENY,
}

enum class AuditActorType {
    USER,
    SYSTEM,
    SERVICE_ACCOUNT,
    INTEGRATION,
    MIGRATION,
    SCHEDULER,
}
