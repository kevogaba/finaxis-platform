package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.SpringTransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import io.namastack.outbox.OutboxRecordRepository
import org.jooq.DSLContext
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
 * an externalized event fails before it commits, the outbox row never persists. Reuses the real
 * tenant-settings service rather than a bespoke harness, injecting the failure through a
 * [TransitionEventPublisher] test double that throws only for this test's target so setup
 * (organisation creation, submission, approval) still publishes normally.
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
    private val tenantSettingsService: TenantSettingsService,
    private val outboxRecords: OutboxRecordRepository,
    private val dsl: DSLContext,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `a failed transaction does not persist its outbox event`() {
        val organisationId = fixture.createActiveOrganisation("rollback", LOCAL_USER_ID)

        withRequestContext {
            assertFailsWith<IllegalStateException> {
                tenantSettingsService.createOrUpdate(
                    CreateOrUpdateTenantSettingCommand(
                        organisationId = organisationId,
                        key = "base_currency",
                        value = "USD",
                        actorId = LOCAL_USER_ID,
                    ),
                )
            }
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

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val ROLLBACK_TARGET = "finaxis.lifecycle.organisation.settings-updated"
    }
}

/**
 * Delegates every event to the real publisher except the settings-updated target, which it fails
 * after delegating - simulating a downstream publish failure inside the same `@Transactional`
 * `createOrUpdate` call so the settings write and the (never-persisted) outbox event must roll
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
