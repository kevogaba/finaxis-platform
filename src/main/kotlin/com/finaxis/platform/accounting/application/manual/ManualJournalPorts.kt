package com.finaxis.platform.accounting.application.manual

import com.finaxis.platform.accounting.domain.ManualJournal
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.ManualJournalTransition
import java.time.LocalDate
import java.util.UUID

/** A manual journal before the database has given it an identity; always created as `DRAFT`. */
data class NewManualJournal(
    val organisationId: UUID,
    val branchId: UUID?,
    val title: String,
    val externalReference: String?,
    val narrative: String,
    val transactionDate: LocalDate?,
    val valueDate: LocalDate?,
    val postingDate: LocalDate?,
    val actorId: UUID,
)

/**
 * The editable content of a draft: header fields and the whole line set.
 *
 * [externalReference] has no default. A document number that a caller can silently omit is a
 * document number that is missing from half the drafts for no recorded reason, which is the
 * situation issue #92 exists to end; a caller with nothing to cite passes `null` and says so.
 */
data class ManualJournalDraftContent(
    val title: String,
    val externalReference: String?,
    val narrative: String,
    val branchId: UUID?,
    val transactionDate: LocalDate?,
    val valueDate: LocalDate?,
    val postingDate: LocalDate?,
    val lines: List<ManualJournalLine>,
)

/**
 * Persistence port over `manual_journal`, `manual_journal_line` and their transition log's read.
 *
 * Every method is organisation-scoped by parameter. Line writes are only legal while the header is
 * `DRAFT`; the service enforces that under the header's row lock, which [lockForStateChange] takes.
 */
interface ManualJournalStore {
    /** Finds one manual journal within a tenant, or null. */
    fun find(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal?

    /** Takes an exclusive row lock on the header and returns it as read under that lock. */
    fun lockForStateChange(
        organisationId: UUID,
        journalId: UUID,
    ): ManualJournal?

    /** Inserts a draft header and returns it with its generated id. */
    fun create(journal: NewManualJournal): ManualJournal

    /** Replaces a draft's header fields; false when the row version moved or it is not a draft. */
    fun updateDraft(
        organisationId: UUID,
        journalId: UUID,
        content: ManualJournalDraftContent,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean

    /**
     * Moves the status, and on `POSTED` records the journal the approval produced, as a
     * compare-and-set on [from].
     */
    fun updateStatus(
        organisationId: UUID,
        journalId: UUID,
        from: ManualJournalStatus,
        to: ManualJournalStatus,
        reason: String?,
        journalEntryId: UUID?,
        actorId: UUID,
    ): Boolean

    /** The lines of one manual journal in line order. */
    fun findLines(
        organisationId: UUID,
        journalId: UUID,
    ): List<ManualJournalLine>

    /** Replaces every line of a draft in one delete-and-insert pair. */
    fun replaceLines(
        organisationId: UUID,
        journalId: UUID,
        lines: List<ManualJournalLine>,
        actorId: UUID,
    )
}

/** Who performed a given transition on a manual journal last, from its transition log. */
interface ManualJournalMakerResolver {
    /** The actor of the most recent [transition] on the journal, or null when it never happened. */
    fun lastActorFor(
        organisationId: UUID,
        journalId: UUID,
        transition: ManualJournalTransition,
    ): UUID?
}
