package com.finaxis.platform.common.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.common.application.shownTo
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The maker lookup: names, the member flag, and what it leaves out. */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class ActorRefLookupTests(
    private val dsl: DSLContext,
) {
    private val lookup = ActorRefLookup(dsl)
    private val org = insertOrganisation()
    private val member = insertUser("Mary Member")
    private val outsider = insertUser("Olu Outsider")

    init {
        insertMembership(org, member)
    }

    @Test
    fun `a member and an outsider come back with their names and the member flag`() {
        val refs = lookup.find(org, listOf(member, outsider))

        assertEquals(ActorRef(member, "Mary Member", true), refs[member])
        assertEquals(ActorRef(outsider, "Olu Outsider", false), refs[outsider])
    }

    @Test
    fun `the system actor, an unknown id and a null are simply absent`() {
        val refs = lookup.find(org, listOf(SystemActor.ID, uuidV7(), null, member, member))

        assertEquals(setOf(member), refs.keys)
    }

    @Test
    fun `no ids is no statement and no organisation means nobody is a member`() {
        assertEquals(emptyMap(), lookup.find(org, emptyList()))
        assertEquals(false, lookup.find(null, listOf(member)).getValue(member).member)
    }

    @Test
    fun `membership in another organisation does not make a user a member here`() {
        val otherOrg = insertOrganisation()

        assertEquals(false, lookup.find(otherOrg, listOf(member)).getValue(member).member)
    }

    @Test
    fun `a tenant caller sees only a member and a platform caller sees both`() {
        val refs = lookup.find(org, listOf(member, outsider))

        assertEquals(refs[member], refs[member].shownTo(platformCaller = false))
        assertNull(refs[outsider].shownTo(platformCaller = false))
        assertEquals(refs[outsider], refs[outsider].shownTo(platformCaller = true))
        assertNull((null as ActorRef?).shownTo(platformCaller = true))
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "org-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Actor Org")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(displayName: String): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "u-$id")
            .set(USER_ACCOUNT.EMAIL, "u-$id@t.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, displayName)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, uuidV7())
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
    }
}
