package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.lifecycle.domain.TimeZoneRule
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The rows of one table whose stored time zone [TimeZoneRule] refuses: how many, and the ids of
 * the first few in id order. Ids, never the stored text, which a branch create before #276 did not
 * check at all.
 */
data class RefusedTimeZones(
    val count: Int,
    val sampleIds: List<UUID>,
)

/** What [StoredTimeZoneReport] found in each table that stores a time zone. */
data class StoredTimeZoneFindings(
    val organisations: RefusedTimeZones,
    val branches: RefusedTimeZones,
) {
    /** True when every stored zone is one [TimeZoneRule] accepts. */
    val clean: Boolean
        get() = organisations.count == 0 && branches.count == 0
}

/** Reads the stored time zones [StoredTimeZoneReport] checks. */
interface StoredTimeZoneStore {
    /** Organisations whose `timezone` is not one of [accepted], with up to [sampleSize] ids. */
    fun organisationsOutside(
        accepted: Set<String>,
        sampleSize: Int,
    ): RefusedTimeZones

    /** Branches whose `timezone` is not one of [accepted], with up to [sampleSize] ids. */
    fun branchesOutside(
        accepted: Set<String>,
        sampleSize: Int,
    ): RefusedTimeZones
}

/**
 * Finds the organisations and branches that still store a time zone [TimeZoneRule] refuses
 * (#331): a value written before #276 that `V37` could not rewrite to an exact IANA equivalent,
 * such as a `+05:30` offset or a misspelt id. It applies the rule itself, not PostgreSQL's zone
 * list, so it agrees with every write path. A platform-wide operational check with no caller
 * input, run at startup by `StoredTimeZoneStartupWarning`; both reads are bounded.
 */
@Service
class StoredTimeZoneReport(
    private val store: StoredTimeZoneStore,
) {
    /** The stored zones the rule refuses, per table. */
    fun refused(): StoredTimeZoneFindings {
        val accepted = TimeZoneRule.acceptedIds()
        return StoredTimeZoneFindings(
            organisations = store.organisationsOutside(accepted, SAMPLE_SIZE),
            branches = store.branchesOutside(accepted, SAMPLE_SIZE),
        )
    }

    /** Constants of the report. */
    companion object {
        /** How many ids of each table the report names. */
        const val SAMPLE_SIZE: Int = 10
    }
}
