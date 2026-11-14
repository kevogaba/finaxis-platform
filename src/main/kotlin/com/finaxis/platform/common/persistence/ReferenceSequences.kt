package com.finaxis.platform.common.persistence

import com.finaxis.platform.jooq.tables.references.REFERENCE_SEQUENCE
import org.jooq.DSLContext
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Takes values from an organisation's gapless counters in `reference_sequence` (V1), one row per
 * organisation and code. The accounting journal number has its own allocator inside accounting;
 * this one serves the counters the other modules read.
 */
object ReferenceSequences {
    /** The member-number counter (#287, ADR 0040): seeded at approval, backfilled by V7. */
    const val MEMBER: String = "MEMBER"

    /**
     * Takes the next value of [organisationId]'s [sequenceCode] counter and returns it.
     *
     * The increment row-locks the counter until the surrounding transaction ends, so a rollback
     * hands the value back and the numbers stay gapless, and concurrent callers in one
     * organisation queue on that one row. A counter that does not exist yet is created holding 1,
     * so an organisation whose setup predates the counter is numbered rather than refused.
     */
    fun next(
        dsl: DSLContext,
        organisationId: UUID,
        sequenceCode: String,
        now: OffsetDateTime,
    ): Long {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Taking a reference number locks its counter, which must be held until commit."
        }
        val after =
            dsl
                .insertInto(REFERENCE_SEQUENCE)
                .set(REFERENCE_SEQUENCE.ORGANISATION_ID, organisationId)
                .set(REFERENCE_SEQUENCE.SEQUENCE_CODE, sequenceCode)
                .set(REFERENCE_SEQUENCE.NEXT_VALUE, FIRST_VALUE + 1)
                .set(REFERENCE_SEQUENCE.CREATED_AT, now)
                .set(REFERENCE_SEQUENCE.UPDATED_AT, now)
                .onConflict(REFERENCE_SEQUENCE.ORGANISATION_ID, REFERENCE_SEQUENCE.SEQUENCE_CODE)
                .doUpdate()
                .set(REFERENCE_SEQUENCE.NEXT_VALUE, REFERENCE_SEQUENCE.NEXT_VALUE.plus(1))
                .set(REFERENCE_SEQUENCE.UPDATED_AT, now)
                .set(REFERENCE_SEQUENCE.ROW_VERSION, REFERENCE_SEQUENCE.ROW_VERSION.plus(1))
                .returning(REFERENCE_SEQUENCE.NEXT_VALUE)
                .fetchOne()
                ?.nextValue
        // The value the row held before this increment is the one taken.
        return checkNotNull(after) { "The $sequenceCode counter returned no value." } - 1
    }

    private const val FIRST_VALUE = 1L
}
