package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.iam.application.query.RoleFilter
import com.finaxis.platform.iam.application.query.UserInTenantFilter
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.ROLE
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

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqIamAdministrationQueriesTests(
    private val dsl: DSLContext,
    private val queries: JooqIamAdministrationQueries,
) {
    @Test
    fun `searchUsers resolves users in tenant`() {
        val orgId = insertOrganisation()
        val userId = insertUserAccount("alice", "alice@example.test", "Alice")
        insertMembership(orgId, userId, "ACTIVE", "STAFF")

        val page = queries.searchUsers(orgId, UserInTenantFilter(q = "Alice"))
        assertEquals(1, page.items.size)
        assertEquals(userId, page.items.single().id)
    }

    @Test
    fun `searchUsers returns empty page for extreme page offset`() {
        val orgId = insertOrganisation()
        val userId = insertUserAccount("extreme", "extreme@example.test", "Extreme")
        insertMembership(orgId, userId, "ACTIVE", "STAFF")

        val page = queries.searchUsers(orgId, UserInTenantFilter(page = Int.MAX_VALUE, size = 100))

        assertEquals(emptyList(), page.items)
        assertEquals(1, page.page.totalItems)
        assertEquals(false, page.page.hasNext)
    }

    @Test
    fun `findUserInTenant resolves details and hides cross tenant users`() {
        val orgId = insertOrganisation()
        val otherOrgId = insertOrganisation()
        val userId = insertUserAccount("alice", "alice@example.test", "Alice")
        insertMembership(orgId, userId, "ACTIVE", "STAFF")

        val user = queries.findUserInTenant(orgId, userId)
        val crossTenantUser = queries.findUserInTenant(otherOrgId, userId)

        assertEquals(userId, user?.id)
        assertEquals("alice@example.test", user?.email)
        assertNull(crossTenantUser)
    }

    @Test
    fun `searchRoles retrieves organization roles`() {
        val orgId = insertOrganisation()
        val roleId = insertRole(orgId, "MAKER", "Maker Role")

        val page = queries.searchRoles(orgId, RoleFilter(q = "Maker"))
        assertEquals(1, page.items.size)
        assertEquals(roleId, page.items.single().id)
    }

    @Test
    fun `searchMemberships scopes filters ordering and pages to the selected tenant`() {
        val orgId = insertOrganisation()
        val otherOrgId = insertOrganisation()
        val firstUserId = insertUserAccount("alice", "alice@example.test", "Alice")
        val secondUserId = insertUserAccount("bob", "bob@example.test", "Bob")
        val otherUserId = insertUserAccount("other", "other@example.test", "Other")
        val first = insertMembership(orgId, firstUserId, "ACTIVE", "STAFF")
        val second = insertMembership(orgId, secondUserId, "SUSPENDED", "AUDITOR")
        insertMembership(otherOrgId, otherUserId, "ACTIVE", "STAFF")

        val active = queries.searchMemberships(orgId, MembershipFilter(membershipStatus = "ACTIVE"))
        val firstPage = queries.searchMemberships(orgId, MembershipFilter(size = 1))
        val secondPage = queries.searchMemberships(orgId, MembershipFilter(page = 1, size = 1))

        assertEquals(listOf(first), active.items.map { it.id })
        assertEquals(listOf(second), firstPage.items.map { it.id })
        assertEquals(listOf(first), secondPage.items.map { it.id })
    }

    @Test
    fun `findMembershipById returns no cross tenant membership`() {
        val orgId = insertOrganisation()
        val otherOrgId = insertOrganisation()
        val userId = insertUserAccount("alice", "alice@example.test", "Alice")
        val membershipId = insertMembership(otherOrgId, userId, "ACTIVE", "STAFF")

        assertNull(queries.findMembershipById(orgId, membershipId))
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "org-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Org")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUserAccount(
        username: String,
        email: String,
        name: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, email)
            .set(USER_ACCOUNT.DISPLAY_NAME, name)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(
        orgId: UUID,
        userId: UUID,
        status: String,
        type: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, orgId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, type)
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertRole(
        orgId: UUID,
        code: String,
        name: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ROLE)
            .set(ROLE.ID, id)
            .set(ROLE.ORGANISATION_ID, orgId)
            .set(ROLE.ROLE_CODE, code)
            .set(ROLE.ROLE_NAME, name)
            .set(ROLE.SYSTEM_ROLE, false)
            .set(ROLE.STATUS, "ACTIVE")
            .set(ROLE.CREATED_AT, now)
            .set(ROLE.UPDATED_AT, now)
            .execute()
        return id
    }
}
