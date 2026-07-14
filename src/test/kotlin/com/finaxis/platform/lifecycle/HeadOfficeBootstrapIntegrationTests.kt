package com.finaxis.platform.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BRANCH_TRANSITION_LOG
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies the generated head-office branch has a durable audit and FSM/outbox trail. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class HeadOfficeBootstrapIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val dsl: DSLContext,
    private val outboxRecords: OutboxRecordRepository,
    private val objectMapper: ObjectMapper,
) {
    @Test
    fun `head office bootstrap writes system audit transition and activation outbox trail`() {
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "head-office-${uuidV7()}",
                        displayName = "Head Office Audit Organisation",
                        legalName = "Head Office Audit Organisation Limited",
                        registrationNumber = "HO-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = SystemActor.ID,
                    ),
                ).organisationId
        organisationProvisioningService.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId),
        )
        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
        )

        val branchId = headOfficeId(organisationId)

        assertHeadOfficeAudit(organisationId, branchId)
        assertHeadOfficeTransitionTrail(organisationId, branchId)
        assertHeadOfficeActivationWasExternalized(branchId)
    }

    private fun headOfficeId(organisationId: UUID): UUID =
        requireNotNull(
            dsl
                .select(BRANCH.ID)
                .from(BRANCH)
                .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                .and(BRANCH.BRANCH_CODE.eq(HEAD_OFFICE_CODE))
                .fetchOne(BRANCH.ID),
        )

    private fun assertHeadOfficeAudit(
        organisationId: UUID,
        branchId: UUID,
    ) {
        val audit =
            requireNotNull(
                dsl
                    .select(
                        AUDIT_EVENT.ACTOR_TYPE,
                        AUDIT_EVENT.ACTOR_USER_ID,
                        AUDIT_EVENT.ENTITY_TYPE,
                        AUDIT_EVENT.ENTITY_ID,
                        AUDIT_EVENT.METADATA_JSONB,
                    ).from(AUDIT_EVENT)
                    .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                    .and(AUDIT_EVENT.ACTION.eq(BRANCH_DRAFT_ACTION))
                    .and(AUDIT_EVENT.ENTITY_ID.eq(branchId))
                    .fetchOne(),
            )

        assertEquals(SYSTEM, audit.get(AUDIT_EVENT.ACTOR_TYPE))
        assertNull(audit.get(AUDIT_EVENT.ACTOR_USER_ID))
        assertEquals(BRANCH_RESOURCE, audit.get(AUDIT_EVENT.ENTITY_TYPE))
        assertEquals(branchId, audit.get(AUDIT_EVENT.ENTITY_ID))
        val metadata = requireNotNull(audit.get(AUDIT_EVENT.METADATA_JSONB))
        val metadataJson = objectMapper.readTree(metadata.data())
        assertEquals("true", metadataJson.get("bootstrap").asText())
        assertEquals(
            ORGANISATION_PROVISIONING_SOURCE,
            metadataJson.get("bootstrapSource").asText(),
        )
    }

    private fun assertHeadOfficeTransitionTrail(
        organisationId: UUID,
        branchId: UUID,
    ) {
        val transitions =
            dsl
                .select(BRANCH_TRANSITION_LOG.TRANSITION_NAME)
                .from(BRANCH_TRANSITION_LOG)
                .where(BRANCH_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
                .and(BRANCH_TRANSITION_LOG.BRANCH_ID.eq(branchId))
                .orderBy(BRANCH_TRANSITION_LOG.CREATED_AT.asc())
                .fetch(BRANCH_TRANSITION_LOG.TRANSITION_NAME)

        assertEquals(
            listOf(CREATE_HEAD_OFFICE_DRAFT, SUBMIT, ACTIVATE),
            transitions,
        )
        val bootstrapTransition =
            requireNotNull(
                dsl
                    .select(
                        BRANCH_TRANSITION_LOG.CREATED_BY,
                        BRANCH_TRANSITION_LOG.METADATA_JSONB,
                    ).from(BRANCH_TRANSITION_LOG)
                    .where(BRANCH_TRANSITION_LOG.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH_TRANSITION_LOG.BRANCH_ID.eq(branchId))
                    .and(BRANCH_TRANSITION_LOG.TRANSITION_NAME.eq(CREATE_HEAD_OFFICE_DRAFT))
                    .fetchOne(),
            )
        assertEquals(SystemActor.ID, bootstrapTransition.get(BRANCH_TRANSITION_LOG.CREATED_BY))
        val metadata = requireNotNull(bootstrapTransition.get(BRANCH_TRANSITION_LOG.METADATA_JSONB))
        val metadataJson = objectMapper.readTree(metadata.data())
        assertTrue(metadataJson.get("bootstrap").asBoolean())
        assertEquals(
            BranchLifecycleState.ACTIVE.name,
            dsl
                .select(BRANCH.STATUS)
                .from(BRANCH)
                .where(BRANCH.ID.eq(branchId))
                .fetchOne(BRANCH.STATUS),
        )
    }

    private fun assertHeadOfficeActivationWasExternalized(branchId: UUID) {
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

    private companion object {
        const val HEAD_OFFICE_CODE = "HEAD_OFFICE"
        const val BRANCH_DRAFT_ACTION = "branch.create_draft"
        const val BRANCH_RESOURCE = "BRANCH"
        const val CREATE_HEAD_OFFICE_DRAFT = "CREATE_HEAD_OFFICE_DRAFT"
        const val SUBMIT = "SUBMIT"
        const val ACTIVATE = "ACTIVATE"
        const val SYSTEM = "SYSTEM"
        const val BRANCH_ACTIVATED_TARGET = "finaxis.lifecycle.branch.activated"
        const val ORGANISATION_PROVISIONING_SOURCE = "organisation_provisioning"
    }
}
