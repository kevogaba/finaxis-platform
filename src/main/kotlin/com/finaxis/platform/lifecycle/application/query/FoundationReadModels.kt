package com.finaxis.platform.lifecycle.application.query

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Summary projection of a tenant organisation. */
data class TenantSummary(
    val id: UUID,
    val tenantCode: String,
    val displayName: String,
    val countryCode: String,
    val status: String,
    val createdAt: Instant,
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
    /** The failure code of the last bootstrap attempt, or null when none failed. */
    val bootstrapFailureCode: String? = null,
)

/** Summary projection of an operating branch. */
data class BranchSummary(
    val id: UUID,
    val organisationId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchType: String,
    val status: String,
    val createdAt: Instant,
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
