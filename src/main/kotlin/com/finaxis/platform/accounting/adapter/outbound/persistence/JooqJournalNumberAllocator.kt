package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.ledger.JournalNumberAllocator
import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Gapless journal numbering over the tenant's `JOURNAL` row in `reference_sequence`.
 *
 * One `UPDATE … RETURNING`: the row lock it takes is held until the caller's transaction ends,
 * so two postings into one tenant serialise here and a rolled-back posting gives its number back
 * by never having committed the increment. Postings into different tenants never contend, because
 * the row is organisation-scoped.
 *
 * The threshold at which this becomes the bottleneck is measured, not argued - posting p99 above
 * 20 ms **and** lock wait on this row above 10% of it - per the accounting foundation.
 */
@Component
class JooqJournalNumberAllocator(
    private val dsl: DSLContext,
    private val clock: Clock,
) : JournalNumberAllocator {
    override fun nextEntryNumber(organisationId: UUID): Long? {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Allocating a journal number takes a row lock that must be held until commit."
        }
        return dsl
            .update(REFERENCE_SEQUENCE)
            .set(REFERENCE_SEQUENCE.NEXT_VALUE, REFERENCE_SEQUENCE.NEXT_VALUE.plus(1))
            .set(REFERENCE_SEQUENCE.UPDATED_AT, OffsetDateTime.now(clock))
            .set(REFERENCE_SEQUENCE.ROW_VERSION, REFERENCE_SEQUENCE.ROW_VERSION.plus(1))
            .where(REFERENCE_SEQUENCE.ORGANISATION_ID.eq(organisationId))
            .and(REFERENCE_SEQUENCE.SEQUENCE_CODE.eq(JOURNAL_SEQUENCE_CODE))
            // The value the row held before this increment: the number this journal takes.
            .returning(REFERENCE_SEQUENCE.NEXT_VALUE)
            .fetchOne()
            ?.nextValue
            ?.let { it - 1 }
    }

    private companion object {
        /** The code `OrganisationBootstrapDefaults.SEQUENCE_CODES` seeds and `V7` backfills. */
        const val JOURNAL_SEQUENCE_CODE = "JOURNAL"
    }
}
