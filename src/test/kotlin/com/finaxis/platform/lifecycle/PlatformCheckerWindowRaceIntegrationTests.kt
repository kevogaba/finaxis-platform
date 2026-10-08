package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.KEYCLOAK_IDENTITY_LINK
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.application.ActingScope
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.ApproveUserCommand
import com.finaxis.platform.lifecycle.application.BranchAssignmentRequest
import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.InviteUserCommand
import com.finaxis.platform.lifecycle.application.LifecycleErrorCodes
import com.finaxis.platform.lifecycle.application.MembershipType
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RoleAssignmentRequest
import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.UserProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Closes the platform-checker window race (#223, ADR 0028 point 8): the window ("no ACTIVE member
 * beyond the bootstrap administrator", "no ACTIVE branch beyond the bootstrap head office") is a
 * count, and two platform administrators approving two different items of one tenant in the same
 * instant could both pass it before either committed. The window check now locks the tenant's
 * organisation row (`FOR NO KEY UPDATE`) before it counts, so the second waits for the first and
 * then counts what the first committed.
 *
 * Deterministic and with no hook in production code, in the style of
 * `TenantApprovalReturnRaceIntegrationTests`. A "rival" transaction takes the organisation row
 * lock (as the window check itself now does) and also locks the row of the item under decision.
 * The decision starts on its own thread and is observed blocked behind the rival through
 * `pg_blocking_pids`. The rival then approves the *other* pending item through the real platform
 * route, which closes the window, and commits.
 *
 * With the lock, the decision is held at the window check, counts after the rival's commit and is
 * refused `lifecycle.platform_checker_closed`. Without it (the old shape) the decision counts
 * while the rival is still open, passes, and is held only at its own write on the item row, which
 * the rival's second lock stands in for; it then approves too, and the tenant ends up with two
 * platform-checked items. That is the failure these tests show on the old shape.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PlatformCheckerWindowRaceIntegrationTests(
    private val dsl: DSLContext,
    private val users: UserProvisioningService,
    private val branches: BranchProvisioningService,
    organisations: OrganisationProvisioningService,
    transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisations, dsl)
    private val transaction = TransactionTemplate(transactionManager)

    /** Two platform administrators: the one whose approval races, and the rival's. */
    private val lateChecker = fixture.createPlatformOperator("late-checker")
    private val rivalChecker = fixture.createPlatformOperator("rival-checker")

    @Test
    fun `two platform checkers racing two memberships cannot both pass the window`() {
        val tenant = Tenant()
        val first = tenant.pendingMembership()
        val second = tenant.pendingMembership()

        val outcome =
            raceDecision(
                tenant.id,
                itemLock = MEMBERSHIP_LOCK to second,
                decide = { approveMembership(tenant.id, second, lateChecker) },
            ) {
                approveMembership(tenant.id, first, rivalChecker)
            }

        assertEquals(
            LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED,
            assertIs<ConflictException>(outcome).code,
        )
        assertEquals("ACTIVE", membershipStatus(first), "the rival's approval committed")
        assertEquals("PENDING_APPROVAL", membershipStatus(second), "the late one changed nothing")
    }

    @Test
    fun `a platform checker held behind a rival that closed nothing still approves`() {
        val tenant = Tenant()
        val membership = tenant.pendingMembership()

        val outcome =
            raceDecision(
                tenant.id,
                itemLock = MEMBERSHIP_LOCK to membership,
                decide = { approveMembership(tenant.id, membership, lateChecker) },
            ) {
                // The rival holds the locks and commits without approving anything.
            }

        assertEquals(null, outcome, "the window is still open, so the approval goes through")
        assertEquals("ACTIVE", membershipStatus(membership))
    }

    @Test
    fun `two platform checkers racing two branches cannot both pass the window`() {
        val tenant = Tenant()
        val first = tenant.pendingBranch()
        val second = tenant.pendingBranch()

        val outcome =
            raceDecision(
                tenant.id,
                itemLock = BRANCH_LOCK to second,
                decide = { activateBranch(tenant.id, second, lateChecker) },
            ) {
                activateBranch(tenant.id, first, rivalChecker)
            }

        assertEquals(
            LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED,
            assertIs<ConflictException>(outcome).code,
        )
        assertEquals("ACTIVE", branchStatus(first), "the rival's activation committed")
        assertEquals("PENDING_APPROVAL", branchStatus(second), "the late one changed nothing")
    }

    /** An ACTIVE tenant whose only member is a bootstrap-style administrator (system-created). */
    private inner class Tenant {
        val admin = seedUser("tenant-admin")
        val id: UUID = fixture.createActiveOrganisation("window-race", admin)
        private val headOffice: UUID =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(id))
                    .and(BRANCH.CREATED_BY.eq(SystemActor.ID))
                    .fetchOne(BRANCH.ID),
            ) { "the provisioned tenant has no system-created head office" }

        /** A membership the tenant administrator invited, for a user with an identity. */
        fun pendingMembership(): UUID {
            val invitee = seedUser("invitee").also(::linkIdentity)
            return withRequestContext {
                users
                    .inviteUser(
                        InviteUserCommand(
                            organisationId = id,
                            email = emailOf(invitee),
                            username = "race-invitee-$invitee",
                            displayName = "Invitee",
                            membershipType = MembershipType.STAFF,
                            primaryBranchId = headOffice,
                            branchAssignments =
                                listOf(
                                    BranchAssignmentRequest(headOffice, BranchAssignmentType.HOME),
                                ),
                            roleAssignments =
                                listOf(
                                    RoleAssignmentRequest(
                                        tenantAdminRole(id),
                                        RoleAssignmentScopeType.TENANT,
                                    ),
                                ),
                            invitedBy = admin,
                            sendKeycloakInvite = false,
                            sendApplicationInvite = false,
                        ),
                    ).membershipId
            }
        }

        /** A branch the tenant administrator drafted and submitted. */
        fun pendingBranch(): UUID =
            withRequestContext {
                val branchId =
                    branches
                        .createDraft(
                            CreateBranchCommand(
                                organisationId = id,
                                branchCode = "WR-${uuidV7().toString().takeLast(8).uppercase()}",
                                branchName = "Window Race Branch",
                                branchType = "OPERATIONAL",
                                timezone = "Africa/Nairobi",
                                requestedBy = admin,
                            ),
                        ).branchId
                branches.submitForApproval(
                    SubmitBranchForApprovalCommand(
                        organisationId = id,
                        branchId = branchId,
                        actorId = admin,
                        requestId = uuidV7(),
                    ),
                )
                branchId
            }
    }

    private fun approveMembership(
        tenantId: UUID,
        membershipId: UUID,
        checker: UUID,
    ) {
        users.approveUser(
            ApproveUserCommand(tenantId, membershipId, checker, scope = ActingScope.PLATFORM),
        )
    }

    private fun activateBranch(
        tenantId: UUID,
        branchId: UUID,
        checker: UUID,
    ) = branches.activate(
        ActivateBranchCommand(
            organisationId = tenantId,
            branchId = branchId,
            actorId = checker,
            requestId = uuidV7(),
            scope = ActingScope.PLATFORM,
        ),
    )

    /**
     * Runs [decide] on its own thread while a rival transaction holds the organisation row lock
     * and the row lock named by [itemLock], waits until the decision is observed blocked behind
     * the rival, runs [rival] inside that transaction, commits, and returns what [decide] threw
     * (null when it succeeded).
     */
    private fun raceDecision(
        tenantId: UUID,
        itemLock: Pair<String, UUID>,
        decide: () -> Unit,
        rival: () -> Unit,
    ): Throwable? {
        var decision: CompletableFuture<Throwable?>? = null
        val decisionPid = CompletableFuture<String>()
        transaction.executeWithoutResult {
            dsl.fetch(ORGANISATION_LOCK, tenantId)
            dsl.fetch(itemLock.first, itemLock.second)
            val rivalPid = requireNotNull(dsl.fetchValue("SELECT pg_backend_pid()")).toString()
            decision =
                CompletableFuture.supplyAsync(
                    {
                        try {
                            // One outer transaction, so the service joins it and the backend
                            // pid read here is the pid of the connection that does the work.
                            transaction.executeWithoutResult {
                                decisionPid.complete(
                                    requireNotNull(dsl.fetchValue("SELECT pg_backend_pid()"))
                                        .toString(),
                                )
                                withRequestContext { decide() }
                            }
                            null
                        } catch (expected: Exception) {
                            expected
                        }
                    },
                    executor,
                )
            awaitBlocked(decisionPid.get(WAIT.toSeconds(), TimeUnit.SECONDS), rivalPid)
            withRequestContext { rival() }
        }
        return try {
            requireNotNull(decision).get(WAIT.toSeconds(), TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    /** Waits until the decision's own backend is blocked by the rival's, and by nothing else. */
    private fun awaitBlocked(
        decisionPid: String,
        rivalPid: String,
    ) {
        val deadline = System.nanoTime() + WAIT.toNanos()
        while (System.nanoTime() < deadline) {
            val blocked =
                dsl.fetchValue(
                    "SELECT count(*) FROM pg_stat_activity WHERE pid = ?::int " +
                        "AND ?::int = ANY(pg_blocking_pids(pid))",
                    decisionPid,
                    rivalPid,
                )
            if ((blocked as Number).toLong() > 0) return
            Thread.sleep(POLL_MILLIS)
        }
        error("the decision never blocked behind the rival transaction")
    }

    private fun tenantAdminRole(tenantId: UUID): UUID =
        requireNotNull(
            dsl
                .select(ROLE.ID)
                .from(ROLE)
                .where(ROLE.ORGANISATION_ID.eq(tenantId))
                .and(ROLE.ROLE_CODE.eq("TENANT_ADMIN"))
                .fetchOne(ROLE.ID),
        )

    private fun membershipStatus(membershipId: UUID): String? =
        dsl
            .select(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)
            .from(USER_ORGANISATION_MEMBERSHIP)
            .where(USER_ORGANISATION_MEMBERSHIP.ID.eq(membershipId))
            .fetchOne(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS)

    private fun branchStatus(branchId: UUID): String? =
        dsl
            .select(BRANCH.STATUS)
            .from(BRANCH)
            .where(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.STATUS)

    private fun emailOf(userId: UUID): String = "window-race-$userId@tenant-race.test"

    private fun seedUser(label: String): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "window-race-$label-$id")
            .set(USER_ACCOUNT.EMAIL, emailOf(id))
            .set(USER_ACCOUNT.DISPLAY_NAME, label)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        return id
    }

    /** A Keycloak identity, so an approval activates the membership at once. */
    private fun linkIdentity(userId: UUID) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(KEYCLOAK_IDENTITY_LINK)
            .set(KEYCLOAK_IDENTITY_LINK.USER_ID, userId)
            .set(KEYCLOAK_IDENTITY_LINK.SUBJECT, "kc-$userId")
            .set(KEYCLOAK_IDENTITY_LINK.LINKED_AT, now)
            .set(KEYCLOAK_IDENTITY_LINK.CREATED_AT, now)
            .set(KEYCLOAK_IDENTITY_LINK.UPDATED_AT, now)
            .execute()
    }

    private inline fun <reified T : Throwable> assertIs(value: Throwable?): T {
        assertNotNull(value, "the decision succeeded; it should have been refused")
        assertTrue(value is T, "expected ${T::class.simpleName} but was $value")
        return value
    }

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(30)
        const val POLL_MILLIS = 20L
        val executor = Executors.newCachedThreadPool()

        /** The lock the window check takes, held by the rival. */
        const val ORGANISATION_LOCK = "SELECT id FROM organisation WHERE id = ? FOR NO KEY UPDATE"

        /** Holds the decision at its own write on the item when it does not wait at the check. */
        const val MEMBERSHIP_LOCK =
            "SELECT id FROM user_organisation_membership WHERE id = ? FOR NO KEY UPDATE"
        const val BRANCH_LOCK = "SELECT id FROM branch WHERE id = ? FOR NO KEY UPDATE"

        @JvmStatic
        @AfterAll
        fun shutDown() {
            executor.shutdownNow()
        }
    }
}
