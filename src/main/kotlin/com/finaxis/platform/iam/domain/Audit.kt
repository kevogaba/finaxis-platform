package com.finaxis.platform.iam.domain

import java.time.Instant
import java.util.UUID

/**
 * Actor reference used by audit-aware application services.
 */
data class AuditActor(
    val type: AuditActorType,
    val userId: UUID? = null,
    val membershipId: UUID? = null,
    val name: String? = null,
)

/**
 * Reusable audit metadata for records that track user and membership attribution.
 */
data class AuditMetadata(
    val createdAt: Instant,
    val createdByUserId: UUID? = null,
    val createdByMembershipId: UUID? = null,
    val updatedAt: Instant,
    val updatedByUserId: UUID? = null,
    val updatedByMembershipId: UUID? = null,
)
