package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.lifecycle.application.AdvanceBusinessDateCommand
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.BusinessDateStore
import com.finaxis.platform.lifecycle.application.CompleteCobCommand
import com.finaxis.platform.lifecycle.application.ListBusinessDateHistoryQuery
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.ReopenBusinessDateCommand
import com.finaxis.platform.lifecycle.application.StartCobCommand
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
class BusinessDateCobLifecycleIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val businessDateService: BusinessDateService,
    private val businessDateStore: BusinessDateStore,
    private val outboxRecords: OutboxRecordRepository,
    private val dsl: DSLContext,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `full COB lifecycle writes durable outbox audit and history records`() {
        val organisationId = exerciseCobLifecycle()

        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val targets = completedTargetsFor(organisationId)
                assertTrue(targets.containsAll(COB_LIFECYCLE_TARGETS))
            }

        COB_LIFECYCLE_AUDIT_ACTIONS.forEach { action ->
            assertEquals(1, auditCount(organisationId, action), "Audit count for $action")
        }

        val history =
            withRequestContext {
                businessDateService.listHistory(
                    ListBusinessDateHistoryQuery(
                        organisationId,
                        LOCAL_USER_ID,
                        page = 0,
                        size = 10,
                    ),
                )
            }
        assertTrue(history.totalItems >= 4)
        assertTrue(history.items.map { it.eventType }.containsAll(COB_LIFECYCLE_EVENT_TYPES))
    }

    @Test
    fun `business date history pagination respects requested page size`() {
        val organisationId = exerciseCobLifecycle()

        val history =
            withRequestContext {
                businessDateService.listHistory(
                    ListBusinessDateHistoryQuery(
                        organisationId,
                        LOCAL_USER_ID,
                        page = 0,
                        size = 2,
                    ),
                )
            }

        assertTrue(history.items.size <= 2)
        assertTrue(history.totalItems >= 4)
    }

    private fun exerciseCobLifecycle(): UUID {
        val organisationId = fixture.createActiveOrganisation("cob", LOCAL_USER_ID)
        val current = requireNotNull(businessDateStore.current(organisationId))

        withRequestContext {
            businessDateService.advance(
                AdvanceBusinessDateCommand(
                    organisationId,
                    current.currentBusinessDate.plusDays(1),
                    LOCAL_USER_ID,
                ),
            )
        }
        withRequestContext {
            businessDateService.startCob(StartCobCommand(organisationId, LOCAL_USER_ID))
        }
        withRequestContext {
            businessDateService.completeCob(CompleteCobCommand(organisationId, LOCAL_USER_ID))
        }
        withRequestContext {
            businessDateService.reopen(ReopenBusinessDateCommand(organisationId, LOCAL_USER_ID))
        }
        return organisationId
    }

    private fun completedTargetsFor(organisationId: UUID): Set<String> =
        outboxRecords
            .findCompletedRecords()
            .mapNotNull { it.payload as? ExternalizedTransitionEvent }
            .filter { it.aggregateId == organisationId.toString() }
            .map { it.target }
            .toSet()

    private fun auditCount(
        organisationId: UUID,
        action: String,
    ): Int =
        dsl
            .selectCount()
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
            .and(AUDIT_EVENT.ACTION.eq(action))
            .fetchOne(0, Int::class.java) ?: 0

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val COB_LIFECYCLE_TARGETS =
            setOf(
                "finaxis.lifecycle.organisation.business-date-advanced",
                "finaxis.lifecycle.organisation.cob-started",
                "finaxis.lifecycle.organisation.cob-completed",
                "finaxis.lifecycle.organisation.business-date-reopened",
            )

        val COB_LIFECYCLE_AUDIT_ACTIONS =
            listOf(
                "business_date.advance",
                "cob.start",
                "cob.complete",
                "business_date.reopen",
            )

        val COB_LIFECYCLE_EVENT_TYPES =
            listOf(
                "ADVANCED",
                "COB_STARTED",
                "COB_COMPLETED",
                "REOPENED",
            )
    }
}
