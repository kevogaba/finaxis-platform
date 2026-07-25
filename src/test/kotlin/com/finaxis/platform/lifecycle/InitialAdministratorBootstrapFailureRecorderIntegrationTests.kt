package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureRecorder
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.InitialAdministratorDraft
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** Verifies failure recording survives the bootstrap transaction rolling back. */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class InitialAdministratorBootstrapFailureRecorderIntegrationTests(
    private val dsl: DSLContext,
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val failureRecorder: InitialAdministratorBootstrapFailureRecorder,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Test
    fun `failure remains FAILED after the enclosing bootstrap transaction rolls back`() {
        val organisationId = insertOrganisation()
        adminBootstrapStore.createDraft(
            organisationId,
            InitialAdministratorDraft(
                email = "admin-$organisationId@example.test",
                username = "admin-$organisationId",
                displayName = "Initial Admin",
                phoneE164 = null,
            ),
            uuidV7(),
        )

        assertFailsWith<IllegalStateException> {
            transaction.executeWithoutResult {
                adminBootstrapStore.updateStatus(
                    organisationId,
                    InitialAdministratorBootstrapStatus.PROVISIONING_IDENTITY,
                    incrementAttempts = true,
                )
                failureRecorder.recordFailure(
                    organisationId,
                    IllegalStateException("Keycloak unavailable"),
                )
                error("Simulated bootstrap failure")
            }
        }

        val record = assertNotNull(adminBootstrapStore.find(organisationId))
        assertEquals(InitialAdministratorBootstrapStatus.FAILED, record.status)
        assertEquals("Keycloak unavailable", record.lastFailureCode)
        assertEquals(0, record.attempts)
    }

    private fun insertOrganisation(): UUID {
        val organisationId = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, organisationId)
            .set(ORGANISATION.TENANT_CODE, "bootstrap-failure-$organisationId")
            .set(ORGANISATION.DISPLAY_NAME, "Bootstrap Failure Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.DRAFT.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return organisationId
    }
}
