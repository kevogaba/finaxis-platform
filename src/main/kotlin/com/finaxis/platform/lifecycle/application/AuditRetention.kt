package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.persistence.TransactionLockBound
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Whether the audit retention job deletes, and how much at a time (#278, ADR 0042). Off unless a
 * deployment turns it on: deleting audit rows cannot be undone, so the first production run should
 * be a dry run that only counts.
 */
@ConfigurationProperties(prefix = "finaxis.audit.retention")
data class AuditRetentionProperties(
    /** `OFF` (nothing read or deleted), `DRY_RUN` (counts and logs) or `ON` (deletes). */
    val mode: Mode = Mode.OFF,
    /** Rows one delete statement removes, so no transaction holds many row locks at once. */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** Batches one organisation gets per run, so one run stays bounded; the next run carries on. */
    val maxBatchesPerRun: Int = DEFAULT_MAX_BATCHES,
    /**
     * The most organisations one run considers. When more store a period, each UTC day's run takes
     * the next window of this many in organisation-id order.
     */
    val maxOrganisationsPerRun: Int = DEFAULT_MAX_ORGANISATIONS,
    /** The bound on a batch's wait for a row lock held by another transaction. */
    val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
) {
    init {
        require(batchSize > 0) { "finaxis.audit.retention.batch-size must be positive." }
        require(maxBatchesPerRun > 0) {
            "finaxis.audit.retention.max-batches-per-run must be positive."
        }
        require(maxOrganisationsPerRun > 0) {
            "finaxis.audit.retention.max-organisations-per-run must be positive."
        }
    }

    /** How the retention job acts on what it finds. */
    enum class Mode { OFF, DRY_RUN, ON }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 500
        const val DEFAULT_MAX_BATCHES = 20
        const val DEFAULT_MAX_ORGANISATIONS = 500
        val DEFAULT_LOCK_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

/** One organisation with a retention period stored, and that period as stored. */
data class AuditRetentionTarget(
    val organisationId: UUID,
    val storedDays: Int,
)

/**
 * The audit rows the retention job may delete (#278, ADR 0042), and the one place that deletes
 * them. A row may go only when all of these hold: it is the organisation's, it is older than the
 * cutoff, its severity is `INFO` and its outcome `SUCCESS` (every refusal, failure and notable row
 * is kept), and it is not an amendment the "approver is never an amender" rules still read: a
 * `branch.update` of a branch not yet `ACTIVE` or closed, or an `organisation.amend_draft` of an
 * organisation not yet approved (#255).
 */
interface AuditRetentionStore {
    /** How many organisations have a current `audit_retention_days` row. */
    fun countRetentionTargets(): Int

    /**
     * The organisations with a current `audit_retention_days` row, in organisation-id order,
     * skipping the first [offset], at most [limit].
     */
    fun retentionTargets(
        limit: Int,
        offset: Int,
    ): List<AuditRetentionTarget>

    /** How many rows [deleteBatch] would remove in all, for a dry run. */
    fun countDeletable(
        organisationId: UUID,
        cutoff: Instant,
    ): Long

    /**
     * Takes the organisation's retention advisory lock for the caller's transaction without
     * waiting, so two runs never delete side by side; false when another run holds it.
     */
    fun tryLockOrganisation(organisationId: UUID): Boolean

    /**
     * Deletes at most [batchSize] of the oldest deletable rows in the caller's transaction and
     * returns how many it deleted.
     */
    fun deleteBatch(
        organisationId: UUID,
        cutoff: Instant,
        batchSize: Int,
    ): Int
}

/** What one retention run did, for its log line and its tests. */
data class AuditRetentionRun(
    val mode: AuditRetentionProperties.Mode,
    val organisations: Int,
    val deleted: Long,
    val deletable: Long,
)

/** The instant before which a row may go: [storedDays] ago, raised to the catalogue's floor. */
internal fun retentionCutoff(
    now: Instant,
    storedDays: Int,
): Instant {
    val days = maxOf(storedDays, TenantSettingCatalog.AUDIT_RETENTION_MINIMUM_DAYS).toLong()
    return now.minus(Duration.ofDays(days))
}

/**
 * One retention batch of one organisation, in a transaction of its own (#278, ADR 0042). Inside
 * it, in this order: the lock wait is bounded; the organisation's retention advisory lock is
 * taken without waiting; the period is read again under the lock every writer of
 * `audit_retention_days` takes, so a period raised or removed since the run listed the
 * organisation is the one that applies, and a write of it waits for this batch to commit; at most
 * `batchSize` rows before the cutoff are deleted; and the `audit.retention_purge` row recording
 * them is written. The deletion and its record therefore commit together or not at all.
 */
@Service
class AuditRetentionBatch(
    private val store: AuditRetentionStore,
    private val settings: OrganisationSettingsStore,
    private val auditService: AuditService,
    private val lockBound: TransactionLockBound,
    private val clock: Clock,
) {
    /**
     * Runs one batch and returns how many rows it deleted: zero when another run holds the
     * organisation, when it no longer stores a period, or when nothing is left before the cutoff.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun purgeBatch(
        organisationId: UUID,
        batchSize: Int,
        lockTimeout: Duration,
    ): Int {
        lockBound.applyToCurrentTransaction(lockTimeout)
        val storedDays =
            if (store.tryLockOrganisation(organisationId)) currentPeriod(organisationId) else null
        if (storedDays == null) {
            return 0
        }
        val cutoff = retentionCutoff(clock.instant(), storedDays)
        val deleted = store.deleteBatch(organisationId, cutoff, batchSize)
        if (deleted > 0) {
            record(organisationId, cutoff, deleted)
        }
        return deleted
    }

    /** The period stored now, read under the setting's write lock; null when none is stored. */
    private fun currentPeriod(organisationId: UUID): Int? =
        settings
            .lockedCurrentSetting(organisationId, TenantSettingCatalog.AUDIT_RETENTION_DAYS_KEY)
            ?.value
            ?.toIntOrNull()

    /** The batch's own record, in the batch's transaction, `HIGH` so no batch can delete it. */
    private fun record(
        organisationId: UUID,
        cutoff: Instant,
        deleted: Int,
    ) {
        auditService.record(
            AuditCommand(
                actorType = SYSTEM_ACTOR_TYPE,
                actorId = SystemActor.ID.toString(),
                tenantId = organisationId.toString(),
                action = PURGE_ACTION,
                resourceType = RESOURCE_TYPE,
                resourceId = organisationId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.HIGH,
                reason = "Audit retention (audit_retention_days)",
                metadata =
                    mapOf(
                        "cutoff" to cutoff.toString(),
                        "deleted" to deleted,
                    ),
            ),
        )
    }

    private companion object {
        /** The action of the row each batch that deleted rows writes. */
        const val PURGE_ACTION = "audit.retention_purge"
        const val RESOURCE_TYPE = "AUDIT_EVENT"
        const val SYSTEM_ACTOR_TYPE = "SYSTEM"
    }
}

/**
 * The audit retention job (#278, ADR 0042): for each organisation that stores
 * `audit_retention_days`, delete the deletable audit rows older than that many days, never fewer
 * than the catalogue's floor whatever is stored, in bounded batches ([AuditRetentionBatch]), each
 * committing with its own `audit.retention_purge` record. An organisation with no row keeps
 * everything. When more organisations store a period than one run considers, each UTC day takes
 * the next window of them ([BoundedRotation]). A failure is logged without its message and stops
 * only that organisation for this run.
 */
@Service
class AuditRetention(
    private val store: AuditRetentionStore,
    private val batches: AuditRetentionBatch,
    private val properties: AuditRetentionProperties,
    private val clock: Clock,
) {
    /** Runs the retention once over this day's window of the organisations that store a period. */
    fun run(): AuditRetentionRun {
        val mode = properties.mode
        if (mode == AuditRetentionProperties.Mode.OFF) {
            return AuditRetentionRun(mode, 0, 0, 0)
        }
        val limit = properties.maxOrganisationsPerRun
        val day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toEpochDay()
        val targets =
            store.retentionTargets(
                limit,
                BoundedRotation.windowOffset(store.countRetentionTargets(), limit, day),
            )
        var deleted = 0L
        var deletable = 0L
        targets.forEach { target ->
            warnBelowFloor(target)
            if (mode == AuditRetentionProperties.Mode.DRY_RUN) {
                deletable += countDeletable(target)
            } else {
                deleted += purge(target.organisationId)
            }
        }
        val run = AuditRetentionRun(mode, targets.size, deleted, deletable)
        log.info(
            "Audit retention ({}): {} organisation(s), {} row(s) deleted, {} deletable",
            mode,
            run.organisations,
            run.deleted,
            run.deletable,
        )
        return run
    }

    private fun warnBelowFloor(target: AuditRetentionTarget) {
        val floor = TenantSettingCatalog.AUDIT_RETENTION_MINIMUM_DAYS
        if (target.storedDays < floor) {
            log.warn(
                "Organisation {} stores audit_retention_days={}, below the floor of {}: " +
                    "keeping {} days",
                target.organisationId,
                target.storedDays,
                floor,
                floor,
            )
        }
    }

    // One organisation's failure must not stop the others, so any runtime failure is caught and
    // the run moves on. The message is not logged: it can carry SQL or data (the V24 rule).
    @Suppress("TooGenericExceptionCaught")
    private fun countDeletable(target: AuditRetentionTarget): Long =
        try {
            val cutoff = retentionCutoff(clock.instant(), target.storedDays)
            store.countDeletable(target.organisationId, cutoff).also { count ->
                log.info(
                    "Dry run: would delete {} audit row(s) of organisation {} before {}",
                    count,
                    target.organisationId,
                    cutoff,
                )
            }
        } catch (ex: RuntimeException) {
            log.warn(
                "Audit retention dry run of organisation {} failed: {}",
                target.organisationId,
                ex.javaClass.name,
            )
            0L
        }

    // As above: a failed batch rolls back with its record, and only this organisation stops.
    @Suppress("TooGenericExceptionCaught")
    private fun purge(organisationId: UUID): Long {
        var deleted = 0L
        try {
            var batchesRun = 0
            var last: Int
            do {
                last =
                    batches.purgeBatch(
                        organisationId,
                        properties.batchSize,
                        properties.lockTimeout,
                    )
                deleted += last
                batchesRun++
            } while (last == properties.batchSize && batchesRun < properties.maxBatchesPerRun)
        } catch (ex: RuntimeException) {
            log.warn(
                "Audit retention of organisation {} stopped after {} row(s): {}",
                organisationId,
                deleted,
                ex.javaClass.name,
            )
        }
        return deleted
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AuditRetention::class.java)
    }
}
