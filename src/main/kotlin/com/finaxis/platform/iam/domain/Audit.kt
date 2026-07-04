package com.finaxis.platform.iam.domain

import java.time.Instant
import java.util.UUID

data class AuditActor(
    val type: AuditActorType,
    val userId: UUID? = null,
    val membershipId: UUID? = null,
    val name: String? = null,
)

data class AuditMetadata(
    val createdAt: Instant,
    val createdByUserId: UUID? = null,
    val createdByMembershipId: UUID? = null,
    val updatedAt: Instant,
    val updatedByUserId: UUID? = null,
    val updatedByMembershipId: UUID? = null,
)
