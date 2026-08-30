package com.finaxis.platform.notifications.adapter.outbound.persistence

import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipient
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipientDirectory
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/** Resolves welcome-email recipient context directly, without depending on lifecycle/iam. */
@Component
class JooqWelcomeEmailRecipientDirectory(
    private val dsl: DSLContext,
) : WelcomeEmailRecipientDirectory {
    override fun findRecipient(
        userId: UUID,
        organisationId: UUID,
    ): WelcomeEmailRecipient? {
        val user =
            dsl
                .select(USER_ACCOUNT.EMAIL, USER_ACCOUNT.DISPLAY_NAME)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.ID.eq(userId))
                .fetchOne() ?: return null
        val organisationDisplayName =
            dsl
                .select(ORGANISATION.DISPLAY_NAME)
                .from(ORGANISATION)
                .where(ORGANISATION.ID.eq(organisationId))
                .fetchOne(ORGANISATION.DISPLAY_NAME) ?: return null
        return WelcomeEmailRecipient(
            email = user.value1()!!,
            displayName = user.value2()!!,
            organisationDisplayName = organisationDisplayName,
        )
    }
}
