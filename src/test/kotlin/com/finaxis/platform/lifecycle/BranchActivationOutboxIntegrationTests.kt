package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.lifecycle.application.ActivateBranchCommand
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.BranchProvisioningService
import com.finaxis.platform.lifecycle.application.CreateBranchCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitBranchForApprovalCommand
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
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
) {
    @Test
    fun `branch activation is durably externalized through Namastack outbox`() {
        val organisationId = activeOrganisation()
        val branchId =
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
                branchId = branchId,
                actorId = LOCAL_USER_ID,
                requestId = uuidV7(),
            ),
        )

        branchProvisioningService.activate(
            ActivateBranchCommand(
                organisationId = organisationId,
                branchId = branchId,
                actorId = LOCAL_CHECKER_ID,
                requestId = uuidV7(),
            ),
        )

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

    private fun activeOrganisation(): UUID {
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "branch-outbox-${uuidV7()}",
                        displayName = "Branch Outbox Organisation",
                        legalName = "Branch Outbox Organisation Limited",
                        registrationNumber = "BRANCH-OUTBOX-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = LOCAL_USER_ID,
                    ),
                ).organisationId
        organisationProvisioningService.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId),
        )
        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
        )
        return organisationId
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LOCAL_CHECKER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111112")
        const val BRANCH_ACTIVATED_TARGET = "finaxis.lifecycle.branch.activated"
    }
}
