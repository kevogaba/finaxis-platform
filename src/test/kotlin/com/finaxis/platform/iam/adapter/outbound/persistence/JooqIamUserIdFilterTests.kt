package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.query.BranchAssignmentFilter
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
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

/**
 * Store-level proof of the `user_id` filter on memberships and branch assignments (#188): it
 * narrows to one user, combines with the other filters and the branch restriction, and never
 * reaches a row of another organisation even for a user who holds rows in both.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqIamUserIdFilterTests(
    private val dsl: DSLContext,
    private val queries: JooqIamAdministrationQueries,
) {
    private val orgA = insertOrganisation()
    private val orgB = insertOrganisation()
    private val branchX = insertBranch(orgA)
    private val branchY = insertBranch(orgA)
    private val branchZ = insertBranch(orgB)
    private val shared = insertUser("shared")
    private val other = insertUser("other")

    private val sharedInA = insertMembership(orgA, shared, "ACTIVE")
    private val otherInA = insertMembership(orgA, other, "SUSPENDED")
    private val sharedInB = insertMembership(orgB, shared, "ACTIVE")

    private val sharedOnX = insertAssignment(orgA, shared, branchX, "ACTIVE")
    private val sharedOnY = insertAssignment(orgA, shared, branchY, "REVOKED")
    private val otherOnX = insertAssignment(orgA, other, branchX, "ACTIVE")
    private val sharedOnZ = insertAssignment(orgB, shared, branchZ, "ACTIVE")

    @Test
    fun `memberships narrow to one user inside the organisation only`() {
        assertEquals(
            listOf(sharedInA),
            memberships(orgA, MembershipFilter(userId = shared)),
        )
        assertEquals(
            listOf(sharedInB),
            memberships(orgB, MembershipFilter(userId = shared)),
        )
        assertEquals(
            setOf(sharedInA, otherInA),
            memberships(orgA, MembershipFilter()).toSet(),
            "no user_id keeps the whole organisation",
        )
    }

    @Test
    fun `memberships combine the user with the status filter`() {
        assertEquals(
            listOf(sharedInA),
            memberships(orgA, MembershipFilter(userId = shared, membershipStatus = "ACTIVE")),
        )
        assertEquals(
            emptyList(),
            memberships(orgA, MembershipFilter(userId = shared, membershipStatus = "SUSPENDED")),
        )
    }

    @Test
    fun `memberships of an unknown user are an empty page with a zero total`() {
        val page = queries.searchMemberships(orgA, MembershipFilter(userId = uuidV7()))

        assertEquals(emptyList(), page.items)
        assertEquals(0, page.page.totalItems)
    }

    @Test
    fun `branch assignments narrow to one user across all of its branches`() {
        assertEquals(
            setOf(sharedOnX, sharedOnY),
            assignments(orgA, BranchAssignmentFilter(userId = shared)).toSet(),
        )
        assertEquals(
            listOf(sharedOnZ),
            assignments(orgB, BranchAssignmentFilter(userId = shared)),
        )
    }

    @Test
    fun `branch assignments combine the user with status branch and restriction`() {
        assertEquals(
            listOf(sharedOnX),
            assignments(orgA, BranchAssignmentFilter(userId = shared, status = "ACTIVE")),
        )
        assertEquals(
            listOf(sharedOnY),
            assignments(orgA, BranchAssignmentFilter(userId = shared, branchId = branchY)),
        )
        assertEquals(
            listOf(sharedOnY),
            assignments(
                orgA,
                BranchAssignmentFilter(userId = shared),
                restrictToBranchIds = setOf(branchY),
            ),
            "the restriction and the user filter both hold",
        )
        assertEquals(
            emptyList(),
            assignments(
                orgA,
                BranchAssignmentFilter(userId = other),
                restrictToBranchIds = setOf(branchY),
            ),
        )
    }

    private fun memberships(
        organisationId: UUID,
        filter: MembershipFilter,
    ): List<UUID> = queries.searchMemberships(organisationId, filter).items.map { it.id }

    private fun assignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
        restrictToBranchIds: Set<UUID>? = null,
    ): List<UUID> =
        queries
            .searchBranchAssignments(organisationId, filter, restrictToBranchIds)
            .items
            .map { it.id }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "org-$id")
            .set(ORGANISATION.DISPLAY_NAME, "User Filter Org")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(organisationId: UUID): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "User Filter Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(username: String): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "$username-$id")
            .set(USER_ACCOUNT.EMAIL, "$username-$id@t.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, username)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
        status: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, status)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
        status: String,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ID, id)
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, "VIEW")
            .set(USER_BRANCH_ASSIGNMENT.STATUS, status)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
            .execute()
        return id
    }
}
