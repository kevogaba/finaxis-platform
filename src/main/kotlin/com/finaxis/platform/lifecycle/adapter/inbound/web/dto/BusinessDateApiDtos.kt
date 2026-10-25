package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Request payload for advancing the active organisation business date. */
data class AdvanceBusinessDateRequest(
    @field:NotNull
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val newBusinessDate: LocalDate,
    val reason: String? = null,
)

/** Request payload for a close-of-business status transition. */
data class BusinessDateStatusTransitionRequest(
    val reason: String? = null,
)

/** Current controlled business date for an organisation. */
data class BusinessDateResponse(
    val organisationId: UUID,
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val currentBusinessDate: LocalDate,
    val status: String,
)

/** Result returned after a successful business-date advance. */
data class BusinessDateAdvanceResponse(
    val organisationId: UUID,
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val previousBusinessDate: LocalDate,
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val newBusinessDate: LocalDate,
)

/** One append-only business-date or close-of-business history entry. */
data class BusinessDateHistoryEntryResponse(
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val fromBusinessDate: LocalDate?,
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val toBusinessDate: LocalDate,
    val actorId: UUID?,
    val reason: String?,
    val occurredAt: Instant,
)

/** One close-of-business readiness check; `count` is omitted by checks that count nothing. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CobReadinessCheckResponse(
    @field:Schema(example = "manual_journals_pending_approval")
    val code: String,
    @field:Schema(allowableValues = ["BLOCKING", "ADVISORY"])
    val severity: String,
    @field:Schema(allowableValues = ["PASS", "FAIL", "WARN"])
    val status: String,
    val detail: String,
    val count: Long? = null,
)

/**
 * Point-in-time close-of-business readiness: advice, not a gate. `ready` is false only when a
 * `BLOCKING` check failed, and `cob/start` does not consult it.
 */
data class CobReadinessResponse(
    @field:JsonFormat(pattern = "dd-MM-yyyy")
    @field:Schema(example = "18-07-2026", type = "string")
    val businessDate: LocalDate,
    val status: String,
    val ready: Boolean,
    val checks: List<CobReadinessCheckResponse>,
)
