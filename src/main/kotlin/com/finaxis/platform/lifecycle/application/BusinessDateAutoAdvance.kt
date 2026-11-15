package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.lifecycle.domain.BusinessDateCalendar
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What the scheduled advance did with one organisation's business date for one day (#277). */
enum class ScheduledAdvanceOutcome {
    /** The date moved one day. */
    ADVANCED,

    /** The organisation is no longer `ACTIVE`. */
    NOT_ACTIVE,

    /** The business date is not `OPEN` (a close-of-business is under way or done). */
    NOT_OPEN,

    /** The date is no longer the one read: someone or something else moved it first. */
    MOVED,

    /** A BLOCKING close-of-business readiness check fails (ADR 0038). */
    NOT_READY,

    /** The tenant no longer stores `business_date_auto_advance_enabled = true`. */
    NOT_OPTED_IN,
}

/**
 * Whether the scheduled advance runs, and how far (#277). Off unless a deployment turns it on, as
 * email delivery is: a tenant's opt-in does nothing until the platform operator does too.
 */
@ConfigurationProperties(prefix = "finaxis.lifecycle.business-date.auto-advance")
data class BusinessDateAutoAdvanceProperties(
    /** `OFF` (nothing advances), `DRY_RUN` (logs what it would advance) or `ON`. */
    val mode: Mode = Mode.OFF,
    /** The most days one organisation advances in one run, so a long gap catches up in steps. */
    val maxStepsPerRun: Int = DEFAULT_MAX_STEPS,
    /**
     * The most organisations one run considers, so a run stays bounded. When more are opted in,
     * each hour's run takes the next window of this many in organisation-id order.
     */
    val maxOrganisationsPerRun: Int = DEFAULT_MAX_ORGANISATIONS,
) {
    init {
        require(maxStepsPerRun > 0) {
            "finaxis.lifecycle.business-date.auto-advance.max-steps-per-run must be positive."
        }
        require(maxOrganisationsPerRun > 0) {
            "finaxis.lifecycle.business-date.auto-advance.max-organisations-per-run must be " +
                "positive."
        }
    }

    /** How the scheduled advance acts on what it finds. */
    enum class Mode { OFF, DRY_RUN, ON }

    private companion object {
        const val DEFAULT_MAX_STEPS = 7
        const val DEFAULT_MAX_ORGANISATIONS = 500
    }
}

/** One organisation the scheduled advance may move: its zone and the date it holds now. */
data class AutoAdvanceCandidate(
    val organisationId: UUID,
    val timezone: String,
    val currentBusinessDate: LocalDate,
)

/** Finds the organisations whose business date the scheduled advance may move. */
interface AutoAdvanceCandidateStore {
    /** How many organisations [autoAdvanceCandidates] can return in all. */
    fun countAutoAdvanceCandidates(): Int

    /**
     * The `ACTIVE` organisations, the platform organisation excepted, whose business date is `OPEN`
     * and whose current `business_date_auto_advance_enabled` row is `true`, in organisation-id
     * order, skipping the first [offset], at most [limit]. The setting defaults to `false`, so a
     * tenant with no row is never a candidate.
     */
    fun autoAdvanceCandidates(
        limit: Int,
        offset: Int,
    ): List<AutoAdvanceCandidate>
}

/** What one run of the scheduled advance did, for its log line and its tests. */
data class AutoAdvanceRun(
    val mode: BusinessDateAutoAdvanceProperties.Mode,
    val candidates: Int,
    val advanced: Int,
    val skipped: Map<String, Int>,
)

/**
 * The scheduled business-date advance (#277, ADR 0041): for each opted-in organisation whose
 * business date is behind its local date, advance it one day at a time, each day its own
 * transaction through [BusinessDateService.advanceAsSystem], until it reaches the local date or the
 * per-run cap. A day it may not advance (opted out, not `OPEN`, moved, not ready, the lock busy, or
 * any other failure) stops that organisation for this run and is tried again on the next; one
 * organisation's failure never stops another's. When more organisations are opted in than one run
 * considers, each hour takes the next window of them ([BoundedRotation]), so tenants whose dates
 * cannot move never hold the others back for good. Every advance writes the same history, audit
 * row, outbox event and daily-balance build a manual advance writes, as the system actor.
 */
@Service
class BusinessDateAutoAdvance(
    private val candidates: AutoAdvanceCandidateStore,
    private val businessDates: BusinessDateService,
    private val properties: BusinessDateAutoAdvanceProperties,
    private val clock: Clock,
) {
    private val warnedWhileOff = AtomicBoolean(false)

    /** Runs the advance once over every candidate, as the configured mode allows. */
    fun run(): AutoAdvanceRun {
        val mode = properties.mode
        val limit = properties.maxOrganisationsPerRun
        val total = candidates.countAutoAdvanceCandidates()
        if (mode == BusinessDateAutoAdvanceProperties.Mode.OFF) {
            warnWhileOff(total)
            return AutoAdvanceRun(mode, minOf(total, limit), 0, emptyMap())
        }
        val hour = clock.instant().epochSecond / SECONDS_PER_HOUR
        val found =
            candidates.autoAdvanceCandidates(
                limit,
                BoundedRotation.windowOffset(total, limit, hour),
            )
        var advanced = 0
        val skipped = mutableMapOf<String, Int>()
        found.forEach { candidate ->
            val result = advance(candidate, mode)
            advanced += result.days
            result.stoppedBy?.let { skipped.merge(it, 1, Int::plus) }
        }
        val run = AutoAdvanceRun(mode, found.size, advanced, skipped)
        log.info(
            "Scheduled business-date advance ({}): {} candidate(s), {} day(s) advanced, " +
                "stopped by {}",
            mode,
            run.candidates,
            run.advanced,
            run.skipped,
        )
        return run
    }

    private fun advance(
        candidate: AutoAdvanceCandidate,
        mode: BusinessDateAutoAdvanceProperties.Mode,
    ): CandidateResult {
        val target = localToday(candidate) ?: return CandidateResult(0, INVALID_ZONE)
        var date = candidate.currentBusinessDate
        var days = 0
        var stoppedBy: String? = null
        while (stoppedBy == null && date.isBefore(target) && days < properties.maxStepsPerRun) {
            stoppedBy = step(candidate.organisationId, date, mode)
            if (stoppedBy == null) {
                date = date.plusDays(1)
                days++
            }
        }
        return CandidateResult(days, stoppedBy)
    }

    /** The organisation's local date, or null when its stored zone no longer parses. */
    private fun localToday(candidate: AutoAdvanceCandidate): LocalDate? =
        try {
            BusinessDateCalendar.localToday(clock, candidate.timezone)
        } catch (_: DateTimeException) {
            null
        }

    /** Advances one day; returns null when it did, else why this organisation stops here. */
    private fun step(
        organisationId: UUID,
        from: LocalDate,
        mode: BusinessDateAutoAdvanceProperties.Mode,
    ): String? {
        if (mode == BusinessDateAutoAdvanceProperties.Mode.DRY_RUN) {
            log.info(
                "Dry run: would advance organisation {} from {} to {}",
                organisationId,
                from,
                from.plusDays(1),
            )
            return null
        }
        return when (val outcome = advanceOneDay(organisationId, from)) {
            null -> FAILED
            ScheduledAdvanceOutcome.ADVANCED -> null
            else -> outcome.name
        }
    }

    /**
     * One day's advance; null when it failed. One organisation's failure must not stop the others,
     * so any runtime failure is caught here and the run moves on, logged by its class: the message
     * can carry SQL or data (the V24 rule).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun advanceOneDay(
        organisationId: UUID,
        from: LocalDate,
    ): ScheduledAdvanceOutcome? =
        try {
            businessDates.advanceAsSystem(organisationId, from, REASON)
        } catch (ex: ConflictException) {
            log.info(
                "Scheduled advance of organisation {} from {} deferred: {}",
                organisationId,
                from,
                ex.code,
            )
            ScheduledAdvanceOutcome.MOVED
        } catch (ex: RuntimeException) {
            log.warn(
                "Scheduled advance of organisation {} from {} failed: {}",
                organisationId,
                from,
                ex.javaClass.name,
            )
            null
        }

    private fun warnWhileOff(candidates: Int) {
        if (candidates > 0 && warnedWhileOff.compareAndSet(false, true)) {
            log.warn(
                "{} tenant(s) have business_date_auto_advance_enabled=true, but " +
                    "finaxis.lifecycle.business-date.auto-advance.mode is OFF: nothing is advanced",
                candidates,
            )
        }
    }

    private data class CandidateResult(
        val days: Int,
        val stoppedBy: String?,
    )

    private companion object {
        val log: Logger = LoggerFactory.getLogger(BusinessDateAutoAdvance::class.java)
        const val SECONDS_PER_HOUR = 3600L

        /** The reason every scheduled advance records in its history and audit row. */
        const val REASON = "Scheduled advance (business_date_auto_advance_enabled)"
        const val INVALID_ZONE = "INVALID_ZONE"
        const val FAILED = "FAILED"
    }
}
