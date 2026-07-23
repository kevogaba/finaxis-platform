package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonFormat
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Request payload for creating a branch draft. */
data class CreateBranchRequest(
    @field:NotBlank
    @field:Pattern(
        regexp = "^[A-Z0-9_-]{2,20}$",
        message =
            "Branch code must be 2-20 uppercase alphanumeric " +
                "characters, underscores, or hyphens.",
    )
    @field:Schema(example = "HEAD-OFFICE")
    val branchCode: String,
    @field:NotBlank
    @field:Size(min = 2, max = 100)
    @field:Schema(example = "Head Office Branch")
    val branchName: String,
    @field:NotBlank
    @field:Schema(example = "HEAD_OFFICE")
    val branchType: String,
    @field:Schema(example = "00000000-0000-0000-0000-000000000000")
    val parentBranchId: UUID? = null,
    @field:NotBlank
    @field:Schema(example = "Africa/Nairobi")
    val timezone: String,
    val address: Map<String, String> = emptyMap(),
)

/** Request payload for submitting a branch draft for approval. */
data class SubmitBranchRequest(
    @field:Size(max = 500)
    @field:Schema(example = "Ready for operational approval.")
    val reason: String? = null,
)

/** Request payload for activating an approved branch. */
data class ActivateBranchRequest(
    @field:Size(max = 500)
    @field:Schema(example = "Operational setup complete.")
    val reason: String? = null,
)

/** Request payload for suspending an active branch. */
data class SuspendBranchRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    @field:Schema(example = "Temporary closure for audit.")
    val reason: String,
)

/** Request payload for reactivating a suspended branch. */
data class ReactivateBranchRequest(
    @field:Size(max = 500)
    @field:Schema(example = "Audit completed.")
    val reason: String? = null,
)

/** Request payload for closing a branch. */
data class CloseBranchRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    @field:Schema(example = "Branch operations consolidated.")
    val reason: String,
)

/** Result returned when a branch draft is created. */
data class BranchDraftResultResponse(
    val branchId: UUID,
    val status: String,
)

/** Detailed response for a branch. */
data class BranchDetailResponse(
    val id: UUID,
    val organisationId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchType: String,
    val parentBranchId: UUID?,
    val status: String,
    val timezone: String,
    val address: Map<String, String>,
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val openedOn: LocalDate?,
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val closedOn: LocalDate?,
    val statusReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary response for a branch in list queries. */
data class BranchSummaryResponse(
    val id: UUID,
    val organisationId: UUID,
    val branchCode: String,
    val branchName: String,
    val branchType: String,
    val status: String,
    val createdAt: Instant,
)
