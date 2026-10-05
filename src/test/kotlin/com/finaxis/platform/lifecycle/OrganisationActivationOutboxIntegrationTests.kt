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
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OrganisationActivationOutboxIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val dsl: org.jooq.DSLContext,
    private val outboxRecords: OutboxRecordRepository,
) {
    @Test
    fun `organisation activation is durably externalized through Namastack outbox`() {
        val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
        val maker = fixture.createPlatformOperator("outbox-maker")
        val checker = fixture.createPlatformOperator("outbox-checker")
        val organisationId =
            withRequestContext {
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
                            requestedBy = maker,
                        ),
                    ).organisationId
            }
        withRequestContext {
            organisationProvisioningService.submitForApproval(
                com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand(
                    organisationId,
                    actorId = maker,
                ),
            )
            organisationProvisioningService.approveProvisioning(
                ApproveOrganisationProvisioningCommand(organisationId, actorId = checker),
            )
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
                            it.target == ORGANISATION_ACTIVATED_TARGET &&
                                it.aggregateId == organisationId.toString()
                        },
                )
            }
    }

    private companion object {
        const val ORGANISATION_ACTIVATED_TARGET = "finaxis.lifecycle.organisation.activated"
    }
}
