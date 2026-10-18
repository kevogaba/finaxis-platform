package com.finaxis.platform.common.persistence

import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/**
 * Resolves the makers a page of records names to [ActorRef]s in one statement, so a list shows who
 * made each row without a read per row and without widening the list's own query.
 */
class ActorRefLookup(
    private val dsl: DSLContext,
) {
    /**
     * The users among [userIds] that exist, with their display name and whether each is a member
     * of [organisationId] (nobody is when it is null). The system actor, an unknown id and a null
     * have no account, so they are absent: a record made by the system names no maker.
     */
    fun find(
        organisationId: UUID?,
        userIds: Collection<UUID?>,
    ): Map<UUID, ActorRef> {
        val ids = userIds.filterNotNull().distinct()
        if (ids.isEmpty()) return emptyMap()
        val member =
            if (organisationId == null) {
                DSL.inline(false)
            } else {
                DSL.field(
                    DSL.exists(
                        DSL
                            .selectOne()
                            .from(USER_ORGANISATION_MEMBERSHIP)
                            .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                            .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(USER_ACCOUNT.ID)),
                    ),
                )
            }
        return dsl
            .select(USER_ACCOUNT.ID, USER_ACCOUNT.DISPLAY_NAME, member)
            .from(USER_ACCOUNT)
            .where(USER_ACCOUNT.ID.`in`(ids))
            .fetch()
            .associate { record ->
                val id = requireNotNull(record.get(USER_ACCOUNT.ID))
                id to
                    ActorRef(id, record.get(USER_ACCOUNT.DISPLAY_NAME), record.get(member) == true)
            }
    }
}
