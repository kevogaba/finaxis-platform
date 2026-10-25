package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.iam.application.query.InvitationDispatch
import com.finaxis.platform.jooq.tables.references.IDENTITY_DISPATCH_LOG
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG
import com.finaxis.platform.lifecycle.application.GENERATION_SUFFIX_PREFIX
import com.finaxis.platform.lifecycle.application.KEYCLOAK_PROVISIONING_KEY_TAG
import com.finaxis.platform.lifecycle.domain.MembershipLifecycleTransition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/**
 * The Keycloak dispatch of the membership's CURRENT life: the row filed under the key
 * `keycloakDispatchKey` derives for the membership's generation (ADR 0037). It is a lateral table
 * to left-join to the membership select, so the membership and its dispatch are read by one
 * statement and one snapshot: a worker finishing between two reads cannot produce a pair that never
 * existed (#274). A re-invited membership has no row under its new key until the approval queues
 * one, and then reads as awaiting approval, never as the first life's `COMPLETED` or `FAILED`.
 */
internal class InvitationDispatchReader(
    dsl: DSLContext,
) {
    private val current: Table<*> =
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
                .and(IDENTITY_DISPATCH_LOG.DISPATCH_TYPE.eq(KEYCLOAK_PROVISIONING_KEY_TAG))
                // The key is unique, so the limit is only a statement of the one-row contract.
                .and(IDENTITY_DISPATCH_LOG.DISPATCH_KEY.eq(currentKey(dsl)))
                .limit(1)
                .asTable("current_dispatch"),
        )

    private val status = requireNotNull(current.field(IDENTITY_DISPATCH_LOG.STATUS))
    private val attempts = requireNotNull(current.field(IDENTITY_DISPATCH_LOG.ATTEMPTS))
    private val lastError = requireNotNull(current.field(IDENTITY_DISPATCH_LOG.LAST_ERROR))
    private val updatedAt = requireNotNull(current.field(IDENTITY_DISPATCH_LOG.UPDATED_AT))

    /** The table to left-join to the membership select, on `true`. */
    val table: Table<*> get() = current

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
        /**
         * `keycloakDispatchKey(organisation, user, generation)` as SQL over the membership row, its
         * generation being the count of its `REINVITE` transition rows (as the lifecycle store
         * reads it). Built from the key's own constants, and proven equal to the function by
         * `MemberReinviteIntegrationTests`.
         */
        fun currentKey(dsl: DSLContext): Field<String?> {
            val generation =
                dsl
                    .selectCount()
                    .from(USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG)
                    .where(
                        USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.ORGANISATION_ID.eq(
                            USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID,
                        ),
                    ).and(
                        USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.ENTITY_ID.eq(
                            USER_ORGANISATION_MEMBERSHIP.ID,
                        ),
                    ).and(
                        USER_ORGANISATION_MEMBERSHIP_TRANSITION_LOG.TRANSITION_NAME.eq(
                            MembershipLifecycleTransition.REINVITE.name,
                        ),
                    ).asField<Int>()
            return DSL.concat(
                DSL.cast(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, SQLDataType.VARCHAR),
                DSL.inline(":"),
                DSL.cast(USER_ORGANISATION_MEMBERSHIP.USER_ID, SQLDataType.VARCHAR),
                DSL.inline(":$KEYCLOAK_PROVISIONING_KEY_TAG"),
                DSL
                    .`when`(
                        generation.gt(0),
                        DSL.concat(
                            DSL.inline(GENERATION_SUFFIX_PREFIX),
                            DSL.cast(generation, SQLDataType.VARCHAR),
                        ),
                    ).otherwise(DSL.inline("")),
            )
        }
    }
}
