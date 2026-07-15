package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.FirstLoginActivationStore
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * jOOQ adapter for login metadata updates owned by the lifecycle module.
 */
@Component
class JooqFirstLoginActivationStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : FirstLoginActivationStore {
    /**
     * Refreshes the user's last-login timestamp after successful runtime principal resolution.
     */
    override fun updateLastLoginAt(userId: UUID) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .update(USER_ACCOUNT)
            .set(USER_ACCOUNT.LAST_LOGIN_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, actorId())
            .set(USER_ACCOUNT.ROW_VERSION, USER_ACCOUNT.ROW_VERSION.plus(1))
            .where(USER_ACCOUNT.ID.eq(userId))
            .execute()
    }

    private fun actorId(): UUID = RequestContexts.actor()?.userId ?: SystemActor.ID
}
