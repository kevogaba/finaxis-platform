package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Summary projection of a tenant organisation. [activeBranchCount] counts its `ACTIVE` branches
 * and [activeUserCount] its `ACTIVE` memberships; each is null for a caller who may not list the
 * rows it counts. [bootstrapStatus] is null for a tenant with no bootstrap record.
 */
data class TenantSummary(
    val id: UUID,
    val tenantCode: String,
    val displayName: String,
    val countryCode: String,
    val status: String,
    val createdAt: Instant,
    val baseCurrencyCode: String,
    val timezone: String,
    val bootstrapStatus: String?,
    val activeBranchCount: Int?,
    val activeUserCount: Int?,
    val requestedBy: ActorRef? = null,
    val submittedBy: ActorRef? = null,
)

/** Detailed projection of a tenant organisation. */
data class TenantDetail(
    val id: UUID,
    val tenantCode: String,
    val displayName: String,
    val countryCode: String,
    val baseCurrencyCode: String,
    val timezone: String,
    val status: String,
    /** The reason recorded by the tenant's last transition, such as a return for changes. */
    val statusReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** The initial-administrator bootstrap status, or null when the tenant has no record. */
    val bootstrapStatus: String? = null,
    /** The closed-set failure code of the last bootstrap attempt, or null when none failed. */
    val bootstrapFailureCode: InitialAdministratorBootstrapFailureCode? = null,
    /** The tenant's legal name, or null when none is recorded. */
    val legalName: String? = null,
    /** The tenant's registration number, or null when none is recorded. */
    val registrationNumber: String? = null,
    val requestedBy: ActorRef? = null,
    val submittedBy: ActorRef? = null,
    /**
     * The initial administrator as drafted, for a platform caller who may amend the draft, and
     * only while the tenant is a draft; null otherwise. Personal data, so never shown to a tenant.
     */
    val administratorDraft: AdministratorDraftView? = null,
)

/** The initial-administrator details a tenant draft holds, in full (#271). */
data class AdministratorDraftView(
    val email: String,
    val username: String,
    val displayName: String,
    val phoneE164: String?,
    val sendApplicationInvite: Boolean,
)

/**
 * Summary projection of an operating branch. [activeUserCount] counts the distinct users with an
 * `ACTIVE` assignment to it, and is null for a caller who may not list its assignments.
 */
data class BranchSummary(
    val id: UUID,
    val organisationId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchType: String,
    val status: String,
    val createdAt: Instant,
    val parentBranchId: UUID?,
    val timezone: String,
    val openedOn: LocalDate?,
    val closedOn: LocalDate?,
    val activeUserCount: Int?,
    val createdBy: ActorRef? = null,
    val submittedBy: ActorRef? = null,
)

/** Detailed projection of an operating branch. */
data class BranchDetail(
    val id: UUID,
    val organisationId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchType: String,
    val parentBranchId: UUID?,
    val status: String,
    val timezone: String,
    val addressJson: String,
    val openedOn: LocalDate?,
    val closedOn: LocalDate?,
    val statusReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: ActorRef? = null,
    val submittedBy: ActorRef? = null,
)

/** Summary projection of a business date history event. */
data class BusinessDateHistorySummary(
    val id: UUID,
    val organisationId: UUID,
    val eventType: String,
    val toStatus: String,
    val toBusinessDate: LocalDate,
    val occurredAt: Instant,
)

/** Detailed projection of a business date history event. */
data class BusinessDateHistoryDetail(
    val id: UUID,
    val organisationId: UUID,
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    val fromBusinessDate: LocalDate?,
    val toBusinessDate: LocalDate,
    val actorId: UUID?,
    val reason: String?,
    val occurredAt: Instant,
    val createdAt: Instant,
    val createdBy: UUID?,
)
