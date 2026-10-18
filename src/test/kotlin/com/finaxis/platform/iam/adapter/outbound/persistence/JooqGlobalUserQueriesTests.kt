package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.query.GlobalUserFilter
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.impl.DSL
import org.jooq.impl.DefaultExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The platform user directory store (#160): filters, counts and a constant statement count. */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqGlobalUserQueriesTests(
    private val dsl: DSLContext,
    private val store: JooqGlobalUserQueries,
) {
    @Test
    fun `a search spans organisations, counts memberships and filters by status and tenant`() {
        val tag = uuidV7().toString().take(8)
        val orgA = insertOrganisation("dir-a-$tag")
        val orgB = insertOrganisation("dir-b-$tag")
        val ann = insertUser("ann-$tag", "ACTIVE")
        val bob = insertUser("bob-$tag", "SUSPENDED")
        val loner = insertUser("loner-$tag", "ACTIVE")
        insertMembership(orgA, ann)
        insertMembership(orgB, ann)
        insertMembership(orgA, bob)

        val all = store.searchUsers(GlobalUserFilter(q = tag, size = 10))
        assertEquals(3L, all.page.totalItems)
        val byName = all.items.associateBy { it.username }
        assertEquals(2, byName.getValue("ann-$tag").membershipCount)
        assertEquals(1, byName.getValue("bob-$tag").membershipCount)
        assertEquals(0, byName.getValue("loner-$tag").membershipCount)

        val suspended = store.searchUsers(GlobalUserFilter(q = tag, userStatus = "SUSPENDED"))
        assertEquals(listOf(bob), suspended.items.map { it.id })

        val inB = store.searchUsers(GlobalUserFilter(organisationId = orgB))
        assertEquals(listOf(ann), inB.items.map { it.id })
        assertEquals(setOf(ann, bob, loner), all.items.map { it.id }.toSet())
    }

    @Test
    fun `sorting by a named key honours the direction and the default is newest first`() {
        val tag = uuidV7().toString().take(8)
        insertUser("a-$tag", "ACTIVE")
        insertUser("b-$tag", "ACTIVE")

        val asc = store.searchUsers(GlobalUserFilter(q = tag, sortBy = "username", sortDir = "ASC"))
        assertEquals(listOf("a-$tag", "b-$tag"), asc.items.map { it.username })
        val desc =
            store.searchUsers(GlobalUserFilter(q = tag, sortBy = "username", sortDir = "DESC"))
        assertEquals(listOf("b-$tag", "a-$tag"), desc.items.map { it.username })
        val newest = store.searchUsers(GlobalUserFilter(q = tag, sortDir = "ASC"))
        assertEquals(listOf("b-$tag", "a-$tag"), newest.items.map { it.username })
    }

    @Test
    fun `a profile lists the memberships newest first with the tenant and keeps the full count`() {
        val tag = uuidV7().toString().take(8)
        val user = insertUser("profile-$tag", "ACTIVE")
        val older = insertOrganisation("dir-old-$tag")
        val newer = insertOrganisation("dir-new-$tag")
        insertMembership(older, user, OffsetDateTime.parse("2026-01-01T00:00:00Z"))
        insertMembership(newer, user, OffsetDateTime.parse("2026-02-01T00:00:00Z"))

        val profile = assertNotNull(store.findUser(user))

        assertEquals(2, profile.user.membershipCount)
        assertEquals(listOf(newer, older), profile.memberships.map { it.organisationId })
        assertEquals("dir-new-$tag", profile.memberships.first().tenantCode)
        assertNull(store.findUser(uuidV7()))
    }

    @Test
    fun `a search costs the same statements whatever the page size`() {
        val tag = uuidV7().toString().take(8)
        repeat(6) { insertUser("n$it-$tag", "ACTIVE") }
        val counter = AtomicInteger()
        val counting =
            JooqGlobalUserQueries(
                DSL.using(
                    dsl.configuration().derive(
                        DefaultExecuteListenerProvider(
                            object : DefaultExecuteListener() {
                                override fun executeStart(ctx: ExecuteContext) {
                                    counter.incrementAndGet()
                                }
                            },
                        ),
                    ),
                ),
            )

        counting.searchUsers(GlobalUserFilter(q = tag, size = 1))
        val small = counter.getAndSet(0)
        counting.searchUsers(GlobalUserFilter(q = tag, size = 50))

        assertEquals(small, counter.get())
    }

    private fun insertOrganisation(code: String): UUID {
        val id = uuidV7()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, code)
            .set(ORGANISATION.DISPLAY_NAME, "Directory $code")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, NOW)
            .set(ORGANISATION.UPDATED_AT, NOW)
            .execute()
        return id
    }

    private fun insertUser(
        username: String,
        status: String,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, "$username@directory.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, username)
            .set(USER_ACCOUNT.STATUS, status)
            .set(USER_ACCOUNT.CREATED_AT, OffsetDateTime.now())
            .set(USER_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
        createdAt: OffsetDateTime = NOW,
    ) {
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, uuidV7())
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, createdAt)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, createdAt)
            .execute()
    }

    private companion object {
        val NOW: OffsetDateTime = OffsetDateTime.parse("2026-01-01T09:00:00Z")
    }
}
