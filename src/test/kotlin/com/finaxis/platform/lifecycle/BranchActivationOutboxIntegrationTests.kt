package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Verifies that branch activation reaches Namastack's durable outbox after the local commit. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BranchActivationOutboxIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val branchProvisioningService: BranchProvisioningService,
    private val outboxRecords: OutboxRecordRepository,
    private val dsl: DSLContext,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `branch activation is durably externalized through Namastack outbox`() {
        insertUserIfMissing(LOCAL_CHECKER_ID, "branch-outbox-checker")
        val organisationId = fixture.createActiveOrganisation("branch-outbox", LOCAL_USER_ID)
        fixture.grantTenantAdmin(organisationId, LOCAL_CHECKER_ID)
        val branchId =
            withRequestContext {
                val createdBranchId =
                    branchProvisioningService
                        .createDraft(
                            CreateBranchCommand(
                                organisationId = organisationId,
                                branchCode = "outbox-${uuidV7()}",
                                branchName = "Outbox Branch",
                                branchType = "SERVICE",
                                timezone = "Africa/Nairobi",
                                requestedBy = LOCAL_USER_ID,
                            ),
                        ).branchId
                branchProvisioningService.submitForApproval(
                    SubmitBranchForApprovalCommand(
                        organisationId = organisationId,
                        branchId = createdBranchId,
                        actorId = LOCAL_USER_ID,
                        requestId = uuidV7(),
                    ),
                )

                branchProvisioningService.activate(
                    ActivateBranchCommand(
                        organisationId = organisationId,
                        branchId = createdBranchId,
                        actorId = LOCAL_CHECKER_ID,
                        requestId = uuidV7(),
                    ),
                )
                createdBranchId
            }

        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                assertTrue(
                    outboxRecords
                        .findCompletedRecords()
                        .mapNotNull { it.payload as? ExternalizedTransitionEvent }
                        .any {
                            it.target == BRANCH_ACTIVATED_TARGET &&
                                it.aggregateId == branchId.toString()
                        },
                )
            }
    }

    private fun insertUserIfMissing(
        userId: UUID,
        username: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, userId)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, "$username@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, username)
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .onConflict(USER_ACCOUNT.ID)
            .doNothing()
            .execute()
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LOCAL_CHECKER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111112")
        const val BRANCH_ACTIVATED_TARGET = "finaxis.lifecycle.branch.activated"
    }
}
