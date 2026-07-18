package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OrganisationSettingsUpdatedOutboxIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val tenantSettingsService: TenantSettingsService,
    private val outboxRecords: OutboxRecordRepository,
    private val dsl: DSLContext,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `settings update is durably externalized through Namastack outbox`() {
        val organisationId = fixture.createActiveOrganisation("settings", LOCAL_USER_ID)

        withRequestContext {
            tenantSettingsService.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId = organisationId,
                    key = "base_currency",
                    value = "KES",
                    actorId = LOCAL_USER_ID,
                ),
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
                            it.target == SETTINGS_UPDATED_TARGET &&
                                it.aggregateId == organisationId.toString()
                        },
                )
            }

        // Exactly one durable record for this single mutation: Namastack's partition-based
        // claiming must not let this event get processed and published more than once.
        val matchingRecords =
            outboxRecords
                .findCompletedRecords()
                .mapNotNull { it.payload as? ExternalizedTransitionEvent }
                .count {
                    it.target == SETTINGS_UPDATED_TARGET &&
                        it.aggregateId == organisationId.toString()
                }
        assertEquals(1, matchingRecords)

        // AuditService persists the explicit service audit through the real Spring wiring.
        val auditedActions =
            dsl
                .selectCount()
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                .and(AUDIT_EVENT.ACTION.eq("settings.update"))
                .fetchOne(0, Int::class.java)
        assertEquals(1, auditedActions)
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val SETTINGS_UPDATED_TARGET = "finaxis.lifecycle.organisation.settings-updated"
    }
}
