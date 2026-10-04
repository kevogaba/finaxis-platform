package com.finaxis.platform.common.context

import java.util.UUID

/**
 * Reserved platform-wide organisation used for global roles and tenant-less audit fallback.
 * Framework-neutral on purpose, so domain code (the organisation transition guard) can name it
 * without depending on persistence configuration.
 */
object PlatformOrganisation {
    /** Stable id of the reserved `PLATFORM` organisation seeded by Flyway. */
    val ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
}
