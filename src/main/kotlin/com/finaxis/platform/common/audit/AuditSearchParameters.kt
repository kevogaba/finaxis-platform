package com.finaxis.platform.common.audit

import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.common.web.api.requireValidSort
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * The query parameters of an audit search as a client sent them (#183). [toFilter] parses the
 * closed sets (`outcome`, `severity`, `min_severity`, `actor_type`, `sort_dir`) case-insensitively;
 * a value outside its set is a 400 `invalid_parameter` naming the parameter, whose message never
 * repeats the rejected value. The bounds that need the whole filter are [requireValid]'s.
 */
data class AuditSearchParameters(
    val entityType: String? = null,
    val entityId: UUID? = null,
    val actorId: UUID? = null,
    val action: String? = null,
    val occurredFrom: Instant? = null,
    val occurredTo: Instant? = null,
    val outcome: String? = null,
    val severity: String? = null,
    val minSeverity: String? = null,
    val branchId: UUID? = null,
    val actionPrefix: String? = null,
    val q: String? = null,
    val actorType: String? = null,
    val actorSubject: String? = null,
    val sortDir: String? = null,
) {
    /**
     * Returns the filter of [organisationId] these parameters describe.
     *
     * @throws InvalidPageRequestException when a closed-set value is outside its set
     */
    fun toFilter(
        organisationId: UUID,
        page: Int,
        size: Int,
    ): AuditEventFilter {
        requireValidSort(sortBy = null, sortDir = sortDir, allowedFields = emptySet())
        return AuditEventFilter(
            organisationId = organisationId,
            entityType = entityType,
            entityId = entityId,
            actorId = actorId,
            action = action,
            occurredFrom = occurredFrom,
            occurredTo = occurredTo,
            outcome = outcome?.let { parse<AuditOutcome>("outcome", it) },
            severity = severity?.let { parse<AuditSeverity>("severity", it) },
            minSeverity = minSeverity?.let { parse<AuditSeverity>("min_severity", it) },
            branchId = branchId,
            actionPrefix = actionPrefix,
            q = q,
            actorType = actorType?.let { parse<AuditActorType>("actor_type", it) },
            actorSubject = actorSubject,
            ascending = sortDir?.uppercase(Locale.ROOT) == "ASC",
            page = page,
            size = size,
        )
    }

    private inline fun <reified E : Enum<E>> parse(
        parameter: String,
        value: String,
    ): E {
        val upper = value.uppercase(Locale.ROOT)
        return enumValues<E>().firstOrNull { it.name == upper }
            ?: throw InvalidPageRequestException(
                parameter,
                "Must be one of: ${enumValues<E>().joinToString(", ")}.",
            )
    }
}

/** The longest window, `occurred_from` to `occurred_to` (or now), a text search may cover. */
val AUDIT_TEXT_SEARCH_MAX_WINDOW: Duration = Duration.ofDays(31)

private val ACTION_PREFIX_LENGTH = 2..64
private val SEARCH_TEXT_LENGTH = 3..64
private const val ACTOR_SUBJECT_MAX_LENGTH = 255

/**
 * Rejects a filter outside the #183 bounds with a 400 naming the parameter: `severity` with
 * `min_severity`; an `action_prefix` outside 2 to 64 characters; a `q` outside 3 to 64
 * characters, without `occurred_from`, with `occurred_from` after `occurred_to` (or [now]), or
 * over a window longer than [AUDIT_TEXT_SEARCH_MAX_WINDOW] (to `occurred_to`, or [now]), which
 * keeps the unindexed substring match to a bounded slice of the tenant's log; and a blank or
 * over-long `actor_subject`.
 *
 * @throws InvalidPageRequestException naming the offending parameter
 */
fun AuditEventFilter.requireValid(now: Instant) {
    if (severity != null && minSeverity != null) {
        invalid("min_severity", "Use severity or min_severity, not both.")
    }
    actionPrefix?.let {
        if (it.length !in ACTION_PREFIX_LENGTH) {
            invalid("action_prefix", "Must be 2 to 64 characters.")
        }
    }
    q?.let { requireBoundedTextSearch(it, now) }
    actorSubject?.let {
        if (it.isBlank() || it.length > ACTOR_SUBJECT_MAX_LENGTH) {
            invalid("actor_subject", "Must be 1 to 255 characters and not blank.")
        }
    }
}

private fun AuditEventFilter.requireBoundedTextSearch(
    text: String,
    now: Instant,
) {
    if (text.length !in SEARCH_TEXT_LENGTH) {
        invalid("q", "Must be 3 to 64 characters.")
    }
    val from = occurredFrom ?: invalid("occurred_from", "Required with q.")
    val until = occurredTo ?: now
    // A from after the upper bound gives a negative window, which must not pass as a short one.
    if (from.isAfter(until)) {
        invalid("occurred_from", "With q, must not be after occurred_to or now.")
    }
    if (Duration.between(from, until) > AUDIT_TEXT_SEARCH_MAX_WINDOW) {
        invalid("occurred_from", "With q, must be at most 31 days before occurred_to or now.")
    }
}

private fun invalid(
    parameter: String,
    message: String,
): Nothing = throw InvalidPageRequestException(parameter, message)
