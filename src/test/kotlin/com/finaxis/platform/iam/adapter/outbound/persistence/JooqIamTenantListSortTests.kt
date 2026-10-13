package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.query.BranchAssignmentFilter
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.iam.application.query.UserInTenantFilter
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
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Store-level proof of the sort contract on the three tenant lists that #190 made honour `sort_by`
 * and `sort_dir`: every allow-listed key orders the rows in both directions across two pages, with
 * the `id desc` tiebreak after the key, and a list without `sort_by` keeps its default order.
 *
 * The fixture holds keys that tie (two rows share a status), so the tiebreak is exercised, and a
 * second organisation whose rows must never appear in the first one's lists.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqIamTenantListSortTests(
    private val dsl: DSLContext,
    private val queries: JooqIamAdministrationQueries,
) {
    @Test
    fun `memberships order by every allow-listed key in both directions across two pages`() {
        val seeded = seed()
        seed(suffix = "-other")

        val sortKeys = IamQueryService.allowedMembershipSorts
        assertEquals(sortKeys, MEMBERSHIP_KEYS.keys, "a service sort key needs a store expectation")
        sortKeys.forEach { sortBy ->
            val key = MEMBERSHIP_KEYS.getValue(sortBy)
            DIRECTIONS.forEach { (sortDir, ascending) ->
                val expected =
                    expectedOrder(seeded.rows, ascending, key) { it.membershipId }
                val actual =
                    (0..1).flatMap { page ->
                        queries
                            .searchMemberships(
                                seeded.orgId,
                                MembershipFilter(
                                    page = page,
                                    size = 2,
                                    sortBy = sortBy,
                                    sortDir = sortDir,
                                ),
                            ).items
                            .map { it.id }
                    }
                assertEquals(expected, actual, "memberships sort_by=$sortBy sort_dir=$sortDir")
            }
        }
    }

    @Test
    fun `tenant users order by every allow-listed key in both directions across two pages`() {
        val seeded = seed()
        seed(suffix = "-other")

        val sortKeys = IamQueryService.allowedUserSorts
        assertEquals(sortKeys, USER_KEYS.keys, "a service sort key needs a store expectation")
        sortKeys.forEach { sortBy ->
            val key = USER_KEYS.getValue(sortBy)
            DIRECTIONS.forEach { (sortDir, ascending) ->
                val expected = expectedOrder(seeded.rows, ascending, key) { it.userId }
                val actual =
                    (0..1).flatMap { page ->
                        queries
                            .searchUsers(
                                seeded.orgId,
                                UserInTenantFilter(
                                    page = page,
                                    size = 2,
                                    sortBy = sortBy,
                                    sortDir = sortDir,
                                ),
                            ).items
                            .map { it.id }
                    }
                assertEquals(expected, actual, "users sort_by=$sortBy sort_dir=$sortDir")
            }
        }
    }

    @Test
    fun `branch assignments order by every allow-listed key in both directions across two pages`() {
        val seeded = seed()
        seed(suffix = "-other")

        val sortKeys = IamQueryService.allowedBranchAssignmentSorts
        assertEquals(sortKeys, ASSIGNMENT_KEYS.keys, "a service sort key needs a store expectation")
        sortKeys.forEach { sortBy ->
            val key = ASSIGNMENT_KEYS.getValue(sortBy)
            DIRECTIONS.forEach { (sortDir, ascending) ->
                val expected = expectedOrder(seeded.rows, ascending, key) { it.assignmentId }
                val actual =
                    (0..1).flatMap { page ->
                        queries
                            .searchBranchAssignments(
                                seeded.orgId,
                                BranchAssignmentFilter(
                                    page = page,
                                    size = 2,
                                    sortBy = sortBy,
                                    sortDir = sortDir,
                                ),
                            ).items
                            .map { it.id }
                    }
                assertEquals(
                    expected,
                    actual,
                    "branch assignments sort_by=$sortBy sort_dir=$sortDir",
                )
            }
        }
    }

    @Test
    fun `a branch restricted sorted assignment list pages only the rows of the visible branch`() {
        val seeded = seed()
        seed(suffix = "-other")
        val visible = seeded.branchIds.first()
        val visibleRows = seeded.rows.filter { it.branchId == visible }

        val sortKeys = IamQueryService.allowedBranchAssignmentSorts
        assertEquals(sortKeys, ASSIGNMENT_KEYS.keys, "a service sort key needs a store expectation")
        sortKeys.forEach { sortBy ->
            val key = ASSIGNMENT_KEYS.getValue(sortBy)
            DIRECTIONS.forEach { (sortDir, ascending) ->
                val expected = expectedOrder(visibleRows, ascending, key) { it.assignmentId }
                // Size 1 over two visible rows: the restriction must hold in the paged query.
                val actual =
                    (0..1).flatMap { page ->
                        queries
                            .searchBranchAssignments(
                                seeded.orgId,
                                BranchAssignmentFilter(
                                    page = page,
                                    size = 1,
                                    sortBy = sortBy,
                                    sortDir = sortDir,
                                ),
                                restrictToBranchIds = setOf(visible),
                            ).items
                            .map { it.id }
                    }
                assertEquals(
                    expected,
                    actual,
                    "restricted sort_by=$sortBy sort_dir=$sortDir",
                )
            }
        }
    }

    @Test
    fun `lists without sort_by keep their default order and ignore sort_dir`() {
        val seeded = seed()
        seed(suffix = "-other")

        val membershipDefault =
            expectedOrder(seeded.rows, false, { it.createdAt }) { it.membershipId }
        val userDefault = expectedOrder(seeded.rows, false, { it.createdAt }) { it.userId }
        val assignmentDefault =
            expectedOrder(seeded.rows, false, { it.assignedAt }) { it.assignmentId }

        listOf(null, "ASC", "DESC").forEach { sortDir ->
            val memberships =
                (0..1).flatMap { page ->
                    queries
                        .searchMemberships(
                            seeded.orgId,
                            MembershipFilter(page = page, size = 2, sortDir = sortDir),
                        ).items
                        .map { it.id }
                }
            val users =
                (0..1).flatMap { page ->
                    queries
                        .searchUsers(
                            seeded.orgId,
                            UserInTenantFilter(page = page, size = 2, sortDir = sortDir),
                        ).items
                        .map { it.id }
                }
            val assignments =
                (0..1).flatMap { page ->
                    queries
                        .searchBranchAssignments(
                            seeded.orgId,
                            BranchAssignmentFilter(page = page, size = 2, sortDir = sortDir),
                        ).items
                        .map { it.id }
                }
            assertEquals(membershipDefault, memberships, "memberships sort_dir=$sortDir")
            assertEquals(userDefault, users, "users sort_dir=$sortDir")
            assertEquals(assignmentDefault, assignments, "assignments sort_dir=$sortDir")
        }
    }

    /** Orders [rows] by [key] in the given direction, then by the row's own id, descending. */
    private fun expectedOrder(
        rows: List<Row>,
        ascending: Boolean,
        key: (Row) -> Comparable<*>?,
        idOf: (Row) -> UUID,
    ): List<UUID> {
        val byKey = if (ascending) compareBy<Row>(key) else compareByDescending<Row>(key)
        return rows
            .sortedWith(byKey.thenByDescending { idOf(it).toString() })
            .map(idOf)
    }

    private fun seed(suffix: String = ""): Seeded {
        val orgId = insertOrganisation()
        val branchIds = listOf(insertBranch(orgId), insertBranch(orgId))
        val rows =
            PEOPLE.mapIndexed { index, person ->
                val at = BASE.plusMinutes(index.toLong())
                val branchId = branchIds[index % branchIds.size]
                val userId = insertUser(person, suffix, at)
                Row(
                    person = person,
                    userId = userId,
                    membershipId = insertMembership(orgId, userId, person, at),
                    assignmentId = insertAssignment(orgId, userId, branchId, person, at),
                    branchId = branchId,
                    createdAt = at,
                    assignedAt = at,
                )
            }
        return Seeded(orgId, branchIds, rows)
    }

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "org-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Sort Org")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(orgId: UUID): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, orgId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "Sort Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(
        person: Person,
        suffix: String,
        at: OffsetDateTime,
    ): UUID {
        val id = uuidV7()
        val username = "${person.username}$suffix"
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, "$username@t.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, person.displayName)
            .set(USER_ACCOUNT.STATUS, person.userStatus)
            .set(USER_ACCOUNT.CREATED_AT, at)
            .set(USER_ACCOUNT.UPDATED_AT, at)
            .execute()
        return id
    }

    private fun insertMembership(
        orgId: UUID,
        userId: UUID,
        person: Person,
        at: OffsetDateTime,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, orgId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, person.membershipStatus)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, person.membershipType)
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, at)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, at)
            .execute()
        return id
    }

    private fun insertAssignment(
        orgId: UUID,
        userId: UUID,
        branchId: UUID,
        person: Person,
        at: OffsetDateTime,
    ): UUID {
        val id = uuidV7()
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ID, id)
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, orgId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, person.assignmentType)
            .set(USER_BRANCH_ASSIGNMENT.STATUS, person.assignmentStatus)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, at)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, at)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, at)
            .execute()
        return id
    }

    /** One seeded organisation: its branches and the rows, in ascending creation order. */
    private data class Seeded(
        val orgId: UUID,
        val branchIds: List<UUID>,
        val rows: List<Row>,
    )

    /** One seeded person, with the ids of its membership, account and branch assignment. */
    private data class Row(
        val person: Person,
        val userId: UUID,
        val membershipId: UUID,
        val assignmentId: UUID,
        val branchId: UUID,
        val createdAt: OffsetDateTime,
        val assignedAt: OffsetDateTime,
    )

    /** The attribute values of one seeded person. */
    private data class Person(
        val username: String,
        val displayName: String,
        val userStatus: String,
        val membershipStatus: String,
        val membershipType: String,
        val assignmentType: String,
        val assignmentStatus: String,
    )

    private companion object {
        val BASE: OffsetDateTime = OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC)

        /**
         * Rows in ascending creation order; `createdAt` and `assignedAt` follow the index. Rows 0
         * and 2 tie on the statuses and rows 0 and 3 tie on the types, so the id tiebreak decides
         * those orders. The usernames, display names and emails are unique.
         */
        val PEOPLE =
            listOf(
                Person("delta", "Dana", "ACTIVE", "ACTIVE", "STAFF", "VIEW", "ACTIVE"),
                Person("alpha", "Ali", "SUSPENDED", "SUSPENDED", "AUDITOR", "HOME", "REVOKED"),
                Person("charlie", "Cy", "ACTIVE", "ACTIVE", "ADMIN", "APPROVE", "ACTIVE"),
                Person(
                    "bravo",
                    "Bea",
                    "INVITED",
                    "PENDING_APPROVAL",
                    "STAFF",
                    "VIEW",
                    "INACTIVE",
                ),
            )

        val DIRECTIONS = listOf("ASC" to true, "DESC" to false)

        val MEMBERSHIP_KEYS: Map<String, (Row) -> Comparable<*>?> =
            mapOf(
                "createdAt" to { row: Row -> row.createdAt },
                "membershipStatus" to { row: Row -> row.person.membershipStatus },
                "membershipType" to { row: Row -> row.person.membershipType },
            )

        val USER_KEYS: Map<String, (Row) -> Comparable<*>?> =
            mapOf(
                "createdAt" to { row: Row -> row.createdAt },
                "username" to { row: Row -> row.person.username },
                "displayName" to { row: Row -> row.person.displayName },
                "email" to { row: Row -> "${row.person.username}@t.test" },
                "userStatus" to { row: Row -> row.person.userStatus },
                "membershipStatus" to { row: Row -> row.person.membershipStatus },
            )

        val ASSIGNMENT_KEYS: Map<String, (Row) -> Comparable<*>?> =
            mapOf(
                "assignedAt" to { row: Row -> row.assignedAt },
                "assignmentType" to { row: Row -> row.person.assignmentType },
                "status" to { row: Row -> row.person.assignmentStatus },
            )
    }
}
