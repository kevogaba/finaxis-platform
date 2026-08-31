package com.finaxis.platform.accounting.application.posting

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Proof that a posting was recorded, returned to the calling product module inside the same
 * transaction. Carries identifiers only, never persistence types, so a caller cannot reach back
 * into accounting state through it.
 */
data class PostingReceipt(
    val postingRequestId: UUID,
    val journalEntryId: UUID,
    val journalReference: String,
    val businessDate: LocalDate,
    val postedAt: Instant,
    val lineCount: Int,
)
