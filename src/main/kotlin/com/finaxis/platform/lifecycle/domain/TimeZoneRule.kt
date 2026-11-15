package com.finaxis.platform.lifecycle.domain

import com.finaxis.platform.common.application.InvalidOperationException
import java.time.ZoneId

/**
 * The one rule for a time-zone id the platform accepts on a write (#276): an id from the IANA
 * region list the JDK carries, spelled exactly (`Africa/Nairobi`, `UTC`, `Etc/GMT-3`).
 *
 * The organisation, its branches and the `default_timezone` setting all apply it, so a zone one of
 * them accepts the others accept too. A raw offset (`+03:00`, `UTC+3`, `GMT+3`) is refused even
 * though `ZoneId.of` parses it: PostgreSQL reads `AT TIME ZONE '+03:00'` with the POSIX sign, three
 * hours the wrong way (see V18's header), so an offset id dates a row differently in SQL than in
 * Kotlin. `Etc/GMT-3` names the same fixed offset with one meaning in both. A zone stored before
 * this rule is read as it is; only new writes are checked.
 */
object TimeZoneRule {
    /** The detail a refused zone answers with; it names the field and gives an example. */
    const val SAFE_DETAIL: String =
        "timezone must be an IANA time-zone id, for example Africa/Nairobi."

    /** Whether [value] is an accepted time-zone id. */
    fun isValid(value: String): Boolean = value in ZoneId.getAvailableZoneIds()

    /**
     * Returns [value] when it is an accepted time-zone id, and otherwise throws
     * [InvalidOperationException] carrying [safeDetail].
     */
    fun require(
        value: String,
        safeDetail: String = SAFE_DETAIL,
    ): String {
        if (!isValid(value)) {
            throw InvalidOperationException(safeDetail = safeDetail)
        }
        return value
    }
}
