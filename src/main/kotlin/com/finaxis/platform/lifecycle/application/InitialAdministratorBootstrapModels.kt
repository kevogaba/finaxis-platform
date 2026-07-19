package com.finaxis.platform.lifecycle.application

import java.time.Instant
import java.util.UUID

/**
 * Draft details for the mandatory initial administrator user to provision.
 */
data class InitialAdministratorDraft(
    val email: String,
    val username: String,
    val displayName: String,
    val phoneE164: String?,
    val sendApplicationInvite: Boolean = false,
)

/**
 * Status of the asynchronous bootstrap process for an organisation's initial administrator.
 */
enum class InitialAdministratorBootstrapStatus {
    DRAFT,
    PENDING_ACTIVATION,
    QUEUED,
    PROVISIONING_IDENTITY,
    COMPLETED,
    FAILED,
}

/**
 * Persistence model matching a row in `organisation_initial_administrator_bootstrap`.
 */
data class InitialAdministratorBootstrapRecord(
    val organisationId: UUID,
    val adminEmail: String,
    val adminUsername: String,
    val adminDisplayName: String,
    val adminPhoneE164: String?,
    val sendApplicationInvite: Boolean,
    val status: InitialAdministratorBootstrapStatus,
    val attempts: Int,
    val requestedBy: UUID,
    val submittedBy: UUID?,
    val approvedBy: UUID?,
    val userId: UUID?,
    val membershipId: UUID?,
    val headOfficeId: UUID?,
    val roleId: UUID?,
    val lastFailureCode: String?,
    val createdAt: Instant,
    val submittedAt: Instant?,
    val approvedAt: Instant?,
    val updatedAt: Instant,
    val rowVersion: Long,
)
