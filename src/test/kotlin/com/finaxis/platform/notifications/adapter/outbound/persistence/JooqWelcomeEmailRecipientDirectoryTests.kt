package com.finaxis.platform.notifications.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import org.jooq.DSLContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqWelcomeEmailRecipientDirectoryTests(
    private val dsl: DSLContext,
) {
    private val directory = JooqWelcomeEmailRecipientDirectory(dsl)

    @Test
    fun `resolves recipient email display name and organisation display name`() {
        val organisationId = seedOrganisation("Acme Bank")
        val userId = seedUser("ada@example.test", "Ada Lovelace")

        val recipient = directory.findRecipient(userId, organisationId)

        assertEquals("ada@example.test", recipient?.email)
        assertEquals("Ada Lovelace", recipient?.displayName)
        assertEquals("Acme Bank", recipient?.organisationDisplayName)
    }

    @Test
    fun `returns null when the user does not exist`() {
        val organisationId = seedOrganisation("Acme Bank")

        assertNull(directory.findRecipient(uuidV7(), organisationId))
    }

    private fun seedOrganisation(displayName: String) =
        uuidV7().also { id ->
            dsl
                .insertInto(ORGANISATION)
                .set(ORGANISATION.ID, id)
                .set(ORGANISATION.TENANT_CODE, "tc-${id.toString().take(8)}")
                .set(ORGANISATION.DISPLAY_NAME, displayName)
                .set(ORGANISATION.COUNTRY_CODE, "KE")
                .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
                .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
                .set(ORGANISATION.STATUS, "ACTIVE")
                .set(ORGANISATION.CREATED_AT, OffsetDateTime.now())
                .set(ORGANISATION.UPDATED_AT, OffsetDateTime.now())
                .execute()
        }

    private fun seedUser(
        email: String,
        displayName: String,
    ) = uuidV7().also { id ->
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, email.substringBefore("@"))
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, OffsetDateTime.now())
            .set(USER_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }
}
