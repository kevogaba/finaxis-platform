package com.finaxis.platform.iam.application.role

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The invariant of #297 against real PostgreSQL: **no ACTIVE assignment ever points at an ARCHIVED
 * role**. Archiving counts a role's ACTIVE assignments and then writes the status, and the two
 * paths that create an assignment (`RoleManagementService.assignRoleToUser` and the invitation)
 * check the role's status and then insert, so without one shared lock between "check" and
 * "write" each side can pass its check against what the other has not yet committed. Both paths
 * and the archive therefore take the role's row lock first (ADR 0035, superseding ADR 0034
 * decision 2).
 *
 * Each ordering is proved twice. The first four scenarios are latch-driven and deterministic: a
 * real service call is held open inside an outer transaction (the service joins it, so its lock
 * and its writes stay uncommitted), and the contending call is waited for until PostgreSQL itself
 * reports it blocked behind that holder, so a missing lock fails the scenario by deadline rather
 * than passing by luck. The last two release both sides together through a barrier and assert the
 * invariant over many iterations.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RoleArchiveRaceIntegrationTests(
    private val roles: RoleManagementService,
    private val users: UserProvisioningService,
    private val dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val admin = seedUser()
    private val organisationId = fixture.createActiveOrganisation("role-race", admin)
    private val member = seedUser().also { fixture.grantTenantAdmin(organisationId, it) }
    private val transactions = TransactionTemplate(transactionManager)
    private val probe = LockOverlapProbe(dsl)

    @Test
    fun `an archive waits for an assignment in flight and then refuses, naming it`() {
        val roleId = newRole("ASSIGN_FIRST")

        val outcome = holdThenContend(holder = { assign(roleId) }, contender = { archive(roleId) })

        val refusal = assertIs<ConflictException>(outcome)
        assertTrue("1 active assignment" in refusal.safeDetail, refusal.safeDetail)
        assertEquals("ACTIVE", statusOf(roleId))
        assertEquals(1, activeAssignments(roleId))
    }

    @Test
    fun `an assignment waits for an archive in flight and then is refused`() {
        val roleId = newRole("ARCHIVE_FIRST")

        val outcome = holdThenContend(holder = { archive(roleId) }, contender = { assign(roleId) })

        assertIs<InvalidOperationException>(outcome)
        assertEquals("ARCHIVED", statusOf(roleId))
        assertEquals(0, activeAssignments(roleId))
    }

    @Test
    fun `an archive waits for an invitation in flight and then refuses, naming it`() {
        val roleId = newRole("INVITE_FIRST")

        val outcome = holdThenContend(holder = { invite(roleId) }, contender = { archive(roleId) })

        val refusal = assertIs<ConflictException>(outcome)
        assertTrue("1 active assignment" in refusal.safeDetail, refusal.safeDetail)
        assertEquals("ACTIVE", statusOf(roleId))
        assertEquals(1, activeAssignments(roleId))
    }

    @Test
    fun `an invitation waits for an archive in flight and then is refused`() {
        val roleId = newRole("ARCHIVE_BEFORE_INVITE")

        val outcome = holdThenContend(holder = { archive(roleId) }, contender = { invite(roleId) })

        assertIs<InvalidOperationException>(outcome)
        assertEquals("ARCHIVED", statusOf(roleId))
        assertEquals(0, activeAssignments(roleId))
    }

    @Test
    fun `archive and assign released together never leave an assignment on an archived role`() {
        repeat(ITERATIONS) {
            val roleId = newRole("RACE_ASSIGN_$it")

            raceTogether({ archive(roleId) }, { assign(roleId) })

            assertInvariant(roleId)
        }
    }

    @Test
    fun `archive and invite released together never leave an assignment on an archived role`() {
        repeat(ITERATIONS) {
            val roleId = newRole("RACE_INVITE_$it")

            raceTogether({ archive(roleId) }, { invite(roleId) })

            assertInvariant(roleId)
        }
    }

    /**
     * Runs [holder] inside an outer transaction that stays open until the [contender] is observed
     * blocked behind it, then lets the holder commit. Returns what the contender threw, or null.
     */
    private fun holdThenContend(
        holder: () -> Unit,
        contender: () -> Unit,
    ): Throwable? {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holderPid = AtomicPid()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val holding =
                executor.submit {
                    transactions.execute {
                        holder()
                        holderPid.value = probe.currentBackendPid()
                        held.countDown()
                        assertTrue(release.await(LATCH_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(held.await(LATCH_SECONDS, TimeUnit.SECONDS), "the holder never took hold")

            val contending =
                executor.submit<Throwable?> {
                    runCatching(
                        contender,
                    ).exceptionOrNull()
                }
            try {
                // The barrier that gives the scenario its teeth: without it the holder commits
                // before the contender issues a statement and this passes with no lock at all.
                probe.awaitBlockedBehind(holderPid.value)
            } finally {
                release.countDown()
            }
            holding.get(FUTURE_SECONDS, TimeUnit.SECONDS)
            return contending.get(FUTURE_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun raceTogether(
        first: () -> Unit,
        second: () -> Unit,
    ) {
        val barrier = CyclicBarrier(2)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures: List<Future<Throwable?>> =
                listOf(first, second).map { action ->
                    executor.submit<Throwable?> {
                        barrier.await(LATCH_SECONDS, TimeUnit.SECONDS)
                        runCatching(action).exceptionOrNull()
                    }
                }
            futures.forEach { future ->
                val failure = future.get(FUTURE_SECONDS, TimeUnit.SECONDS)
                // A loser is refused cleanly; anything else (a deadlock, a 500) is a defect.
                assertTrue(
                    failure == null ||
                        failure is ConflictException ||
                        failure is InvalidOperationException,
                    "unexpected failure: $failure",
                )
            }
        }
    }

    private fun assertInvariant(roleId: UUID) {
        val status = statusOf(roleId)
        val assignments = activeAssignments(roleId)
        assertFalse(
            status == "ARCHIVED" && assignments > 0,
            "role $roleId is ARCHIVED yet holds $assignments ACTIVE assignment(s)",
        )
        // Exactly one side won: archived with nothing, or still active with the one assignment.
        assertTrue(
            (status == "ARCHIVED" && assignments == 0) || (status == "ACTIVE" && assignments == 1),
            "unexpected end state $status with $assignments assignment(s)",
        )
    }

    private fun newRole(code: String): UUID =
        withRequestContext {
            roles
                .createTenantRole(
                    CreateTenantRole(
                        organisationId,
                        "${code}_${uuidV7().toString().takeLast(6).uppercase()}",
                        code,
                        null,
                        admin,
                    ),
                ).roleId
        }

    private fun archive(roleId: UUID) {
        withRequestContext { roles.archiveRole(ArchiveRole(organisationId, roleId, admin)) }
    }

    private fun assign(roleId: UUID) {
        withRequestContext {
            roles.assignRoleToUser(
                AssignRoleToUser(
                    organisationId,
                    member,
                    roleId,
                    RoleScopeType.TENANT,
                    null,
                    admin,
                ),
            )
        }
    }

    private fun invite(roleId: UUID) {
        val handle = uuidV7()
        withRequestContext {
            users.inviteUser(
                InviteUserCommand(
                    organisationId = organisationId,
                    email = "race-$handle@invite.test",
                    username = "race-$handle",
                    displayName = "Race Invitee",
                    membershipType = MembershipType.AUDITOR,
                    branchAssignments = emptyList(),
                    roleAssignments =
                        listOf(RoleAssignmentRequest(roleId, RoleAssignmentScopeType.TENANT)),
                    invitedBy = admin,
                    sendKeycloakInvite = false,
                    sendApplicationInvite = false,
                ),
            )
        }
    }

    private fun statusOf(roleId: UUID): String =
        dsl.fetchValue("SELECT status FROM role WHERE id = ?", roleId).toString()

    private fun activeAssignments(roleId: UUID): Int =
        dsl
            .fetchValue(
                "SELECT count(*) FROM user_role_assignment WHERE role_id = ? AND status = 'ACTIVE'",
                roleId,
            ).toString()
            .toInt()

    private fun seedUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "role-race-$id")
            .set(USER_ACCOUNT.EMAIL, "role-race-$id@seed.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "race")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        return id
    }

    /** A pid written by the holder's thread and read by the test thread after a latch. */
    private class AtomicPid {
        @Volatile
        var value: Int = 0
    }

    private companion object {
        const val ITERATIONS = 12
        const val LATCH_SECONDS = 20L
        const val FUTURE_SECONDS = 60L
    }
}
