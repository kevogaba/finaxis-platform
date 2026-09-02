package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.domain.AccountingDates
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.PostingLeg
import java.security.MessageDigest

/**
 * A stable digest of what a posting asked for, so a retry can be told from a conflicting reuse of
 * the same source reference without storing the request.
 *
 * Covers the source triple, the event, the entry type, the four dates, the currency and every leg
 * — account, side, amount at storage scale, and sub-ledger reference. Legs are sorted before
 * hashing so a retry that happens to iterate a collection in a different order is still the same
 * request. Narratives are excluded: a retry that rewords its description is not a different
 * financial fact. Never the raw payload, and nothing a caller would consider sensitive.
 */
object PostingFingerprint {
    private const val ALGORITHM = "SHA-256"
    private const val SEPARATOR = "\n"

    /** Computes the lower-case hex SHA-256 that `chk_posting_request_fingerprint` expects. */
    fun of(
        source: AccountingSourceReference,
        eventCode: String,
        entryType: JournalEntryType,
        dates: AccountingDates,
        currencyCode: String,
        legs: List<PostingLeg>,
    ): String {
        val canonical =
            buildList {
                add(source.sourceModule)
                add(source.sourceType)
                add(source.sourceId.toString())
                add(source.idempotencyKey)
                add(eventCode)
                add(entryType.name)
                add(dates.transactionDate.toString())
                add(dates.valueDate.toString())
                add(dates.postingDate.toString())
                add(currencyCode)
                legs
                    .map { leg ->
                        listOf(
                            leg.accountId.toString(),
                            leg.side.name,
                            leg.amount.amount.toPlainString(),
                            leg.amount.currency,
                            leg.subledgerReference.orEmpty(),
                        ).joinToString("|")
                    }.sorted()
                    .forEach(::add)
            }.joinToString(SEPARATOR)
        return MessageDigest
            .getInstance(ALGORITHM)
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
