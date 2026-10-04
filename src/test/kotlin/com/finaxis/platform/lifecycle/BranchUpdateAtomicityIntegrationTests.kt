package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CloseBranchCommand
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.UpdateBranchCommand
import org.jooq.DSLContext
import org.jooq.Field
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Atomicity proof for the branch update (issue #165), hosted on the same
 * [FinancialTransactionAtomicityFixture] the ledger paths use: the in-place branch change and its
 * audit row commit together or not at all, and neither is visible to another connection before
 * commit. The idempotency record is not probed here: `BranchUpdateIntegrationTests` shows only that
 * a replayed key applies the update once.
 *
 * The last two tests are the concurrency half: two moves that are each acyclic alone must not both
 * commit, or the hierarchy would hold a cycle, and a close racing a move under the branch being
 * closed must not leave a closed branch with an active child.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BranchUpdateAtomicityIntegrationTests(
    private val dsl: DSLContext,
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val branchProvisioningService: BranchProvisioningService,
    private val transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val actorId = uuidV7().also { seedUser(it) }
    private val organisationId = fixture.createActiveOrganisation("branch-atomic", actorId)

    private fun seedUser(id: UUID) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "atomic-$id")
            .set(USER_ACCOUNT.EMAIL, "atomic-$id@branch-atomic.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "atomic")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
    }

    private fun draftBranch(): UUID =
        withRequestContext {
            branchProvisioningService
                .createDraft(
                    CreateBranchCommand(
                        organisationId = organisationId,
                        branchCode = "AT-${uuidV7().toString().takeLast(8).uppercase()}",
                        branchName = "Atomic Branch",
                        branchType = "OPERATIONAL",
                        timezone = "Africa/Nairobi",
                        requestedBy = actorId,
                    ),
                ).branchId
        }

    private fun harness(branchId: UUID) =
        FinancialTransactionAtomicityFixture(
            dsl,
            transactionManager,
            listOf(
                FoundationAtomicityProbes.branchRowVersion(branchId),
                FoundationAtomicityProbes.auditEventRows(
                    organisationId,
                    "branch.update",
                    "SUCCESS",
                ),
            ),
        )

    private fun update(
        branchId: UUID,
        name: String,
    ) = withRequestContext {
        branchProvisioningService.update(
            UpdateBranchCommand(organisationId, branchId, actorId, branchName = name),
        )
    }

    @Test
    fun `a failure after the update rolls back the branch change and its audit row`() {
        val branchId = draftBranch()

        harness(branchId).assertRollsBackAtomically(IllegalStateException::class) {
            update(branchId, "Renamed Then Rolled Back")
            assertEquals(
                1L,
                dsl
                    .select(BRANCH.ROW_VERSION)
                    .from(BRANCH)
                    .where(BRANCH.ID.eq(branchId))
                    .fetchOne(BRANCH.ROW_VERSION),
                "the update must have taken effect inside the transaction",
            )
            error("simulated failure after a successful branch update")
        }

        assertEquals(
            "Atomic Branch",
            dsl
                .select(BRANCH.BRANCH_NAME)
                .from(BRANCH)
                .where(BRANCH.ID.eq(branchId))
                .fetchOne(BRANCH.BRANCH_NAME),
        )
    }

    @Test
    fun `the branch change and its audit row become visible together after commit`() {
        val branchId = draftBranch()
        val probes = harness(branchId)

        probes.assertVisibleOnlyAfterCommit(
            mapOf(
                "branch.row_version[$branchId]" to 1L,
                "audit_event[branch.update, SUCCESS]" to 1L,
            ),
        ) {
            update(branchId, "Renamed And Committed")
        }

        assertEquals(
            "Renamed And Committed",
            dsl
                .select(BRANCH.BRANCH_NAME)
                .from(BRANCH)
                .where(BRANCH.ID.eq(branchId))
                .fetchOne(BRANCH.BRANCH_NAME),
        )
    }

    @Test
    fun `two moves that would close a cycle cannot both commit`() {
        repeat(ROUNDS) {
            val first = draftBranch()
            val second = draftBranch()
            val start = CountDownLatch(1)

            Executors.newFixedThreadPool(2).use { pool ->
                val moves =
                    listOf(first to second, second to first).map { (branch, parent) ->
                        pool.submit<Result<Unit>> {
                            start.await()
                            runCatching {
                                withRequestContext {
                                    branchProvisioningService.update(
                                        UpdateBranchCommand(
                                            organisationId,
                                            branch,
                                            actorId,
                                            changesParent = true,
                                            parentBranchId = parent,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                start.countDown()
                val outcomes = moves.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

                assertEquals(1, outcomes.count { it.isSuccess }, "outcomes: $outcomes")
                assertEquals(
                    1,
                    outcomes.count { it.exceptionOrNull() is InvalidOperationException },
                    "outcomes: $outcomes",
                )
            }
            val parents =
                listOf(first, second).map { id ->
                    dsl
                        .select(BRANCH.PARENT_BRANCH_ID)
                        .from(BRANCH)
                        .where(BRANCH.ID.eq(id))
                        .fetchOne(BRANCH.PARENT_BRANCH_ID)
                }
            assertEquals(1, parents.count { it != null }, "exactly one parent link must exist")
        }
    }

    @Test
    fun `a close racing a move under the same branch cannot leave a closed parent with a child`() {
        val checker =
            uuidV7()
                .also {
                    seedUser(
                        it,
                    )
                }.also { fixture.grantTenantAdmin(organisationId, it) }
        val parent = activeBranch(checker)
        val child = activeBranch(checker)
        val moved = CountDownLatch(1)
        val release = CountDownLatch(1)

        Executors.newFixedThreadPool(2).use { pool ->
            // The move writes and then holds its transaction open, as a slow request would.
            val move = pool.submit { moveAndHold(child, parent, moved, release) }
            assertTrue(moved.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            // The close cannot see the uncommitted child, so its guard passes; it then has to wait
            // for the parent row the move holds. Releasing the move either way must end in a
            // refused close: by the optimistic-lock check, or by the guard seeing the child.
            val close = pool.submit<Result<Unit>> { runCatching { close(parent, checker) } }
            Thread.sleep(CLOSE_WAIT_MILLIS)
            release.countDown()
            move.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            assertTrue(close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isFailure, "close must lose")
        }

        assertEquals("ACTIVE", branchColumn(parent, BRANCH.STATUS))
        assertEquals(parent, branchColumn(child, BRANCH.PARENT_BRANCH_ID))
    }

    private fun moveAndHold(
        child: UUID,
        parent: UUID,
        moved: CountDownLatch,
        release: CountDownLatch,
    ) {
        TransactionTemplate(transactionManager).execute {
            withRequestContext {
                branchProvisioningService.update(
                    UpdateBranchCommand(
                        organisationId,
                        child,
                        actorId,
                        changesParent = true,
                        parentBranchId = parent,
                    ),
                )
            }
            moved.countDown()
            release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun close(
        branchId: UUID,
        checker: UUID,
    ) = withRequestContext {
        branchProvisioningService.close(
            CloseBranchCommand(organisationId, branchId, "Consolidated", checker),
        )
    }

    private fun <T> branchColumn(
        branchId: UUID,
        field: Field<T>,
    ): T? =
        dsl
            .select(field)
            .from(BRANCH)
            .where(BRANCH.ID.eq(branchId))
            .fetchOne(field)

    private fun activeBranch(checker: UUID): UUID {
        val branchId = draftBranch()
        withRequestContext {
            branchProvisioningService.submitForApproval(
                SubmitBranchForApprovalCommand(organisationId, branchId, null, actorId, uuidV7()),
            )
            branchProvisioningService.activate(
                ActivateBranchCommand(organisationId, branchId, null, checker, uuidV7()),
            )
        }
        return branchId
    }

    private companion object {
        const val ROUNDS = 5
        const val TIMEOUT_SECONDS = 30L
        const val CLOSE_WAIT_MILLIS = 1_000L
    }
}
