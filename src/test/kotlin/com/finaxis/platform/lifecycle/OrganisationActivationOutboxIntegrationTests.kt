package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OrganisationActivationOutboxIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val outboxRecords: OutboxRecordRepository,
) {
    @Test
    fun `organisation activation is durably externalized through Namastack outbox`() {
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "outbox-${uuidV7()}",
                        displayName = "Outbox Organisation",
                        legalName = "Outbox Organisation Limited",
                        registrationNumber = "OUTBOX-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = LOCAL_USER_ID,
                    ),
                ).organisationId
        organisationProvisioningService.submitForApproval(
            com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand(
                organisationId,
            ),
        )

        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
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
                            it.target == ORGANISATION_ACTIVATED_TARGET &&
                                it.aggregateId == organisationId.toString()
                        },
                )
            }
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val ORGANISATION_ACTIVATED_TARGET = "finaxis.lifecycle.organisation.activated"
    }
}
