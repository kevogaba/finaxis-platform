package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.MoneyPolicy
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * A stable digest of what a posting asked for, so a retry can be told from a conflicting reuse of
 * the same source reference without storing the request.
 *
 * Computed from the caller's inputs - the source triple, the event, the entry type, the branch,
 * correction and reversal lineage, the resolved dates, the functional currency,
 * [LedgerPostingRequest.productClass] and [LedgerPostingRequest.financialFacts] - never from the
 * resolved legs. That is deliberate, not an oversight: claiming the source reference has to happen
 * before a rule-resolved posting's legs can safely be asked for (see [PostingEngine]), so nothing
 * the digest covers may depend on resolving them. [LedgerPostingRequest.productClass] and
 * [LedgerPostingRequest.financialFacts] are what still give a rule-resolved posting selector- and
 * amount-level discrimination without that resolution: both are the caller's own asserted inputs,
 * known before any rule ever runs, for [com.finaxis.platform.accounting.application.posting.PostingService]
 * callers. Reversal and manual journals leave both empty/null and need neither: both derive their
 * source reference from an already-immutable record (the journal being reversed, the approved
 * manual journal's own id), so the same reference can never legitimately name two different
 * amounts or route through two different rules. Narratives are excluded: a retry that rewords its
 * description is not a different financial fact. Each fact's amount is fed through
 * [com.finaxis.platform.accounting.domain.MoneyPolicy.requireSettled] before hashing, so two
 * requests that name the same amount at a different scale - `500.0` against `500.00` - collapse to
 * the same fingerprint instead of a spurious conflict.
 *
 * Every field is fed through [MessageDigest.updateField], which precedes each value with a marker
 * byte and, when present, a fixed-width byte-length before the value's own bytes. That makes the
 * encoding unambiguous for a fixed field schema without a human-readable separator the data could
 * itself contain - the injectivity property a delimiter-joined string cannot offer, since
 * [com.finaxis.platform.accounting.domain.PostingLeg.subledgerReference] and other free-text fields
 * are bounded in length only, never in content.
 */
object PostingFingerprint {
    private const val ALGORITHM = "SHA-256"
    private const val ABSENT: Byte = 0
    private const val PRESENT: Byte = 1

    /** Computes the lower-case hex SHA-256 that `chk_posting_request_fingerprint` expects. */
    fun of(
        request: LedgerPostingRequest,
        branchId: UUID?,
        dates: AccountingDates,
        currencyCode: String,
    ): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val source = request.source
        digest.updateField(source.sourceModule)
        digest.updateField(source.sourceType)
        digest.updateField(source.sourceId.toString())
        digest.updateField(source.idempotencyKey)
        digest.updateField(request.eventCode)
        digest.updateField(request.entryType.name)
        digest.updateField(branchId?.toString())
        digest.updateField(request.correctsPostingRequestId?.toString())
        digest.updateField(request.reversesJournalEntryId?.toString())
        digest.updateField(dates.transactionDate.toString())
        digest.updateField(dates.valueDate.toString())
        digest.updateField(dates.postingDate.toString())
        digest.updateField(currencyCode)
        digest.updateField(request.productClass)
        // Settled to storage scale first, so a caller-supplied `500.0` and `500.00` hash
        // identically instead of conflicting on a difference that carries no financial meaning.
        // Sorted, like the legs an earlier revision hashed: a retry that happens to assemble its
        // facts in a different order is still the same request. Not deduplicated - a caller that
        // asserts the same code twice is a defect the resolver refuses later, and refusing it
        // consistently on every attempt requires the fingerprint to see both occurrences, not
        // collapse them first.
        request.financialFacts
            .map { fact -> fact.code to MoneyPolicy.requireSettled(fact.amount) }
            .sortedWith(compareBy({ it.first }, { it.second.amount.toPlainString() }))
            .forEach { (code, settled) ->
                digest.updateField(code)
                digest.updateField(settled.amount.toPlainString())
                digest.updateField(settled.currency)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Feeds one field into the digest, unambiguously.
     *
     * A leading marker byte tells a null field from an empty string, which are otherwise
     * indistinguishable once hashed. A present field is then preceded by its length as a fixed
     * 4-byte big-endian integer rather than a decimal string, so nothing about the encoding depends
     * on the value never containing a delimiter character - there is no delimiter to contain.
     */
    private fun MessageDigest.updateField(value: String?) {
        if (value == null) {
            update(ABSENT)
            return
        }
        update(PRESENT)
        val bytes = value.toByteArray(Charsets.UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }
}
