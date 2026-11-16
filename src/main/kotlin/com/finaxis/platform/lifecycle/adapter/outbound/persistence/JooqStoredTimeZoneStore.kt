package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.RefusedTimeZones
import com.finaxis.platform.lifecycle.application.StoredTimeZoneStore
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Reads `organisation.timezone` and `branch.timezone` for the stored-zone report (#331). Each
 * table is one count and one id-ordered, limited id read, with the accepted ids bound as
 * parameters: the comparison is the application's rule, not PostgreSQL's zone list.
 */
@Component
class JooqStoredTimeZoneStore(
    private val dsl: DSLContext,
) : StoredTimeZoneStore {
    override fun organisationsOutside(
        accepted: Set<String>,
        sampleSize: Int,
    ): RefusedTimeZones =
        outside(ORGANISATION, ORGANISATION.ID, ORGANISATION.TIMEZONE, accepted, sampleSize)

    override fun branchesOutside(
        accepted: Set<String>,
        sampleSize: Int,
    ): RefusedTimeZones = outside(BRANCH, BRANCH.ID, BRANCH.TIMEZONE, accepted, sampleSize)

    private fun outside(
        table: Table<*>,
        id: Field<UUID?>,
        zone: Field<String?>,
        accepted: Set<String>,
        sampleSize: Int,
    ): RefusedTimeZones {
        val refused = zone.notIn(accepted)
        return RefusedTimeZones(
            count = dsl.fetchCount(table, refused),
            sampleIds =
                dsl
                    .select(id)
                    .from(table)
                    .where(refused)
                    .orderBy(id)
                    .limit(sampleSize)
                    .fetch(id)
                    .filterNotNull(),
        )
    }
}
