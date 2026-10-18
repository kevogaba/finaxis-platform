package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.query.InvitationDispatch
import com.finaxis.platform.jooq.tables.references.IDENTITY_DISPATCH_LOG
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL

/**
 * The newest Keycloak dispatch of a membership's user in the membership's organisation, as a
 * lateral table to left-join to the membership select, so the membership and its dispatch are read
 * by one statement and one snapshot: a worker finishing between two reads cannot produce a pair
 * that never existed (#274).
 */
internal class InvitationDispatchReader(
    dsl: DSLContext,
) {
    private val newest: Table<*> =
        DSL.lateral(
            dsl
                .select(
                    IDENTITY_DISPATCH_LOG.STATUS,
                    IDENTITY_DISPATCH_LOG.ATTEMPTS,
                    IDENTITY_DISPATCH_LOG.LAST_ERROR,
                    IDENTITY_DISPATCH_LOG.UPDATED_AT,
                ).from(IDENTITY_DISPATCH_LOG)
                .where(
                    IDENTITY_DISPATCH_LOG.ORGANISATION_ID.eq(
                        USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                    ),
                ).and(IDENTITY_DISPATCH_LOG.USER_ID.eq(USER_ORGANISATION_MEMBERSHIP.USER_ID))
                .and(IDENTITY_DISPATCH_LOG.DISPATCH_TYPE.eq(KEYCLOAK_PROVISIONING))
                .orderBy(IDENTITY_DISPATCH_LOG.CREATED_AT.desc(), IDENTITY_DISPATCH_LOG.ID.desc())
                .limit(1)
                .asTable("newest_dispatch"),
        )

    private val status = requireNotNull(newest.field(IDENTITY_DISPATCH_LOG.STATUS))
    private val attempts = requireNotNull(newest.field(IDENTITY_DISPATCH_LOG.ATTEMPTS))
    private val lastError = requireNotNull(newest.field(IDENTITY_DISPATCH_LOG.LAST_ERROR))
    private val updatedAt = requireNotNull(newest.field(IDENTITY_DISPATCH_LOG.UPDATED_AT))

    /** The table to left-join to the membership select, on `true`. */
    val table: Table<*> get() = newest

    /** The columns this reader needs in the membership select. */
    val fields = listOf(status, attempts, lastError, updatedAt)

    /** The dispatch in a membership record that joined [table], or null when it had none. */
    fun of(record: Record): InvitationDispatch? =
        record.get(status)?.let { dispatchStatus ->
            InvitationDispatch(
                status = dispatchStatus,
                attempts = requireNotNull(record.get(attempts)),
                lastError = record.get(lastError),
                updatedAt = requireNotNull(record.get(updatedAt)).toInstant(),
            )
        }

    private companion object {
        const val KEYCLOAK_PROVISIONING = "KEYCLOAK_PROVISIONING"
    }
}
