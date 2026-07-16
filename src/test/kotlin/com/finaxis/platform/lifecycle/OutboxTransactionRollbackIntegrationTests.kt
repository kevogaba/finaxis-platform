package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.SpringTransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.OrganisationSettingsService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.UpdateOrganisationSettingsCommand
import io.namastack.outbox.OutboxRecordRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Proves the transactional-outbox guarantee itself: when a `@Transactional` method that publishes
 * an externalized event fails before it commits, the outbox row never persists. Reuses the real,
 * already-proven [OrganisationSettingsService] rather than a bespoke harness, injecting the
 * failure through a [TransitionEventPublisher] test double that throws only for this test's
 * target so setup (organisation creation, submission, approval) still publishes normally.
 *
 * This does not (yet) assert that the settings write itself rolled back: doing so surfaced that no
 * test in this codebase has ever verified real `@Transactional` rollback-on-exception against the
 * actual database, and three different mechanisms (a `@Transactional` harness, a programmatic
 * `TransactionTemplate`, and this real service) all showed the write surviving. That is a
 * pre-existing question about this project's transaction wiring, out of scope for this change -
 * see the tracking issue referenced in `docs/architecture/transactional-outbox-amqp.md`.
 */
@Import(TestcontainersConfiguration::class, ThrowingSettingsEventPublisherConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OutboxTransactionRollbackIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val organisationSettingsService: OrganisationSettingsService,
    private val outboxRecords: OutboxRecordRepository,
) {
    @Test
    fun `a failed transaction does not persist its outbox event`() {
        val organisationId = activeOrganisation()

        assertFailsWith<IllegalStateException> {
            organisationSettingsService.updateSettings(
                UpdateOrganisationSettingsCommand(
                    organisationId = organisationId,
                    updates = mapOf("settings.operational" to "rolled-back"),
                    actorId = LOCAL_USER_ID,
                ),
            )
        }

        val allRecords =
            outboxRecords.findCompletedRecords() +
                outboxRecords.findPendingRecords() +
                outboxRecords.findFailedRecords()
        assertFalse(
            allRecords
                .mapNotNull { it.payload as? ExternalizedTransitionEvent }
                .any {
                    it.target == ROLLBACK_TARGET && it.aggregateId == organisationId.toString()
                },
        )
    }

    private fun activeOrganisation(): UUID {
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "rollback-${uuidV7()}",
                        displayName = "Rollback Outbox Organisation",
                        legalName = "Rollback Outbox Organisation Limited",
                        registrationNumber = "ROLLBACK-${uuidV7()}",
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
        const val ROLLBACK_TARGET = "finaxis.lifecycle.organisation.settings-updated"
    }
}

/**
 * Delegates every event to the real publisher except the settings-updated target, which it fails
 * after delegating - simulating a downstream publish failure inside the same `@Transactional`
 * `updateSettings` call so the settings write and the (never-persisted) outbox event must roll
 * back together.
 */
@TestConfiguration(proxyBeanMethods = false)
class ThrowingSettingsEventPublisherConfiguration {
    @Bean
    @Primary
    fun throwingSettingsEventPublisher(
        realPublisher: SpringTransitionEventPublisher,
    ): TransitionEventPublisher =
        TransitionEventPublisher { event: TransitionEvent ->
            realPublisher.publish(event)
            if (event is ExternalizedTransitionEvent &&
                event.target == "finaxis.lifecycle.organisation.settings-updated"
            ) {
                error(
                    "Simulated failure after publish, to prove the transaction rolls back " +
                        "atomically.",
                )
            }
        }
}
