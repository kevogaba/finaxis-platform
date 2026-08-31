package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.SpringTransitionEventPublisher
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import io.namastack.outbox.OutboxRecordRepository
import org.jooq.DSLContext
import org.jooq.JSONB
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestConstructor
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Proves the transactional-outbox guarantee itself: when a `@Transactional` method that publishes
 * an externalized event fails before it commits, the outbox row never persists. Reuses the real
 * tenant-settings service rather than a bespoke harness, injecting the failure through a
 * [TransitionEventPublisher] test double that throws only for this test's target so setup
 * (organisation creation, submission, approval) still publishes normally.
 *
 * Verifies both sides of the atomicity contract: the business setting write and the outbox event
 * must roll back together when publication fails inside the transaction.
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
    fun `a failed settings update leaves the previously effective row untouched`() {
        val organisationId = fixture.createActiveOrganisation("rollback-effective", LOCAL_USER_ID)
        // Seeded with a direct insert rather than through the service: the @Primary publisher
        // below throws for this exact target, so the service cannot be used to establish a
        // committed starting row. Without a committed row there is nothing for the update to
        // close, which is why the closing UPDATE was never exercised before.
        seedEffectiveSetting(organisationId, "KES")

        withRequestContext {
            assertFailsWith<IllegalStateException> {
                tenantSettingsService.createOrUpdate(
                    CreateOrUpdateTenantSettingCommand(
                        organisationId = organisationId,
                        key = SETTING_KEY,
                        value = "USD",
                        actorId = LOCAL_USER_ID,
                    ),
                )
            }
        }

        assertEquals(1, countSettings(organisationId), "no replacement row may survive")
        assertEquals(
            1,
            countEffectiveSettings(organisationId),
            "the closing UPDATE must roll back too, leaving exactly one effective row",
        )
        assertEquals(
            "KES",
            effectiveSettingValue(organisationId),
            "the surviving effective row must still hold the seeded value",
        )
        assertEquals(
            0,
            countSettingsAudits(organisationId),
            "the audit row is written by AuditService.record in the caller's transaction, so it " +
                "must roll back with it",
        )
    }

    @Test
    fun `a failed transaction does not persist its outbox event`() {
        val organisationId = fixture.createActiveOrganisation("rollback", LOCAL_USER_ID)
        val settingsBefore = countSettings(organisationId)

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
        assertEquals(settingsBefore, countSettings(organisationId))
    }

    private fun countSettings(organisationId: UUID): Int =
        dsl
            .selectCount()
            .from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.eq(SETTING_KEY))
            .fetchOne(0, Int::class.java) ?: 0

    private fun countEffectiveSettings(organisationId: UUID): Int =
        dsl
            .selectCount()
            .from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.eq(SETTING_KEY))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .fetchOne(0, Int::class.java) ?: 0

    private fun effectiveSettingValue(organisationId: UUID): String =
        requireNotNull(
            dsl
                .select(ORGANISATION_SETTING.SETTING_VALUE)
                .from(ORGANISATION_SETTING)
                .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
                .and(ORGANISATION_SETTING.SETTING_KEY.eq(SETTING_KEY))
                .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
                .fetchOne(ORGANISATION_SETTING.SETTING_VALUE),
        ).data().trim('"')

    private fun countSettingsAudits(organisationId: UUID): Int =
        dsl
            .selectCount()
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
            .and(AUDIT_EVENT.ACTION.eq("settings.update"))
            .and(AUDIT_EVENT.OUTCOME.eq("SUCCESS"))
            .fetchOne(0, Int::class.java) ?: 0

    private fun seedEffectiveSetting(
        organisationId: UUID,
        value: String,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.ORGANISATION_ID, organisationId)
            .set(ORGANISATION_SETTING.SETTING_KEY, SETTING_KEY)
            .set(ORGANISATION_SETTING.SETTING_VALUE, JSONB.jsonb("\"$value\""))
            .set(ORGANISATION_SETTING.VALUE_TYPE, "CURRENCY")
            .set(ORGANISATION_SETTING.IS_SENSITIVE, false)
            .set(ORGANISATION_SETTING.EFFECTIVE_FROM, now)
            .set(ORGANISATION_SETTING.CREATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .execute()
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val ROLLBACK_TARGET = "finaxis.lifecycle.organisation.settings-updated"
        const val SETTING_KEY = "base_currency"
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
