package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.fasterxml.jackson.annotation.JsonFormat
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
