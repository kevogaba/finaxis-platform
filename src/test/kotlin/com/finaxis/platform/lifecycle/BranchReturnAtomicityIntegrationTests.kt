package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.AtomicityProbe
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReturnBranchCommand
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Commit-or-roll-back-together proof for returning or withdrawing a pending branch (issue #180).
 * It is not a financial write path (ADR 0029), but it reuses the branch row-version probe the
 * update registered, plus the transition-log row and the audit rows it writes: the status change,
 * its log row and its audit rows become visible together, or not at all.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BranchReturnAtomicityIntegrationTests(
    private val dsl: DSLContext,
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val branchProvisioningService: BranchProvisioningService,
    private val transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val actorId = uuidV7().also { seedUser(it) }
    private val checkerId = uuidV7().also { seedUser(it) }
    private val organisationId = fixture.createActiveOrganisation("branch-return-atomic", actorId)

    init {
        fixture.grantTenantAdmin(organisationId, checkerId)
    }

    private fun pendingBranch(): UUID =
        withRequestContext {
            val branchId =
                branchProvisioningService
                    .createDraft(
                        CreateBranchCommand(
                            organisationId = organisationId,
                            branchCode = "RA-${uuidV7().toString().takeLast(8).uppercase()}",
                            branchName = "Return Atomic Branch",
                            branchType = "OPERATIONAL",
                            timezone = "Africa/Nairobi",
                            requestedBy = actorId,
                        ),
                    ).branchId
            branchProvisioningService.submitForApproval(
                SubmitBranchForApprovalCommand(organisationId, branchId, null, actorId, uuidV7()),
            )
            branchId
        }

    private fun harness(branchId: UUID) =
        FinancialTransactionAtomicityFixture(
            dsl,
            transactionManager,
            listOf(
                FoundationAtomicityProbes.branchRowVersion(branchId),
                AtomicityProbe("branch_transition_log[RETURN_FOR_CHANGES, $branchId]") { dsl ->
                    dsl
                        .fetchCount(
                            BRANCH_TRANSITION_LOG,
                            BRANCH_TRANSITION_LOG.BRANCH_ID
                                .eq(branchId)
                                .and(BRANCH_TRANSITION_LOG.TRANSITION_NAME.eq(RETURN)),
                        ).toLong()
                },
                FoundationAtomicityProbes.auditEventRows(
                    organisationId,
                    "branch.return_for_changes",
                    "SUCCESS",
                ),
                FoundationAtomicityProbes.auditEventRows(
                    organisationId,
                    "branch.withdraw",
                    "SUCCESS",
                ),
            ),
        )

    private fun returnBranch(
        branchId: UUID,
        actor: UUID,
    ) = withRequestContext {
        branchProvisioningService.returnForChanges(
            ReturnBranchCommand(organisationId, branchId, "Typo in the branch name.", actor),
        )
    }

    private fun status(branchId: UUID): String? =
        dsl
            .select(BRANCH.STATUS)
            .from(BRANCH)
            .where(BRANCH.ID.eq(branchId))
            .fetchOne(BRANCH.STATUS)

    @Test
    fun `a failure after a return rolls back the status the log row and the audit rows`() {
        listOf(checkerId, actorId).forEach { actor ->
            val branchId = pendingBranch()

            harness(branchId).assertRollsBackAtomically(IllegalStateException::class) {
                returnBranch(branchId, actor)
                assertEquals("DRAFT", status(branchId), "the return must have taken effect")
                error("simulated failure after a successful return")
            }

            assertEquals("PENDING_APPROVAL", status(branchId))
        }
    }

    @Test
    fun `a return becomes visible together with its log row and audit rows after commit`() {
        val returned = pendingBranch()
        harness(returned).assertVisibleOnlyAfterCommit(
            mapOf(
                "branch.row_version[$returned]" to 1L,
                "branch_transition_log[RETURN_FOR_CHANGES, $returned]" to 1L,
                "audit_event[branch.return_for_changes, SUCCESS]" to 1L,
                "audit_event[branch.withdraw, SUCCESS]" to 0L,
            ),
        ) {
            returnBranch(returned, checkerId)
        }
        assertEquals("DRAFT", status(returned))

        val withdrawn = pendingBranch()
        harness(withdrawn).assertVisibleOnlyAfterCommit(
            mapOf(
                "branch.row_version[$withdrawn]" to 1L,
                "branch_transition_log[RETURN_FOR_CHANGES, $withdrawn]" to 1L,
                "audit_event[branch.return_for_changes, SUCCESS]" to 1L,
                "audit_event[branch.withdraw, SUCCESS]" to 1L,
            ),
        ) {
            returnBranch(withdrawn, actorId)
        }
        assertEquals("DRAFT", status(withdrawn))
    }

    private fun seedUser(id: UUID) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "ret-atomic-$id")
            .set(USER_ACCOUNT.EMAIL, "ret-atomic-$id@branch-return.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "atomic")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
    }

    private companion object {
        const val RETURN = "RETURN_FOR_CHANGES"
    }
}
