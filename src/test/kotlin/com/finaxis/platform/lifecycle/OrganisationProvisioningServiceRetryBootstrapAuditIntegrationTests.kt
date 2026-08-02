package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapService
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStatus
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapStore
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.RetryInitialAdministratorBootstrapCommand
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Proves the guarantee behind `OrganisationProvisioningService.retryBootstrap`'s failure-path
 * audit: it must survive even though `retryBootstrap`'s own `@Transactional` rolls back around the
 * rethrown exception. Only [InitialAdministratorBootstrapService] is a test double here - the
 * audit repository, transaction manager, and permission guard are all real, so this exercises the
 * actual Spring `@Transactional(propagation = REQUIRES_NEW)` proxy the unit-test equivalent of
 * this scenario cannot reach.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OrganisationProvisioningServiceRetryBootstrapAuditIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val adminBootstrapStore: InitialAdministratorBootstrapStore,
    private val dsl: DSLContext,
) {
    @MockitoBean
    private lateinit var bootstrapService: InitialAdministratorBootstrapService

    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)

    @Test
    fun `a failed retry commits its audit event despite the enclosing transaction rolling back`() {
        val actorId = uuidV7()
        // Seed a real actor row: audit_event.actor_user_id has a FK to user_account, which
        // createDraft's own audit(...) call hits before retryBootstrap is ever reached.
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, actorId)
            .set(USER_ACCOUNT.USERNAME, "retry-audit-$actorId")
            .set(USER_ACCOUNT.EMAIL, "retry-audit-$actorId@test.com")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Retry Audit Actor")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
        val organisationId = fixture.createActiveOrganisation("retry-audit", actorId)
        adminBootstrapStore.updateStatus(
            organisationId,
            InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = "Keycloak unavailable",
        )
        doThrow(IllegalStateException("Keycloak unavailable"))
            .whenever(bootstrapService)
            .bootstrap(organisationId)

        withRequestContext {
            assertFailsWith<IllegalStateException> {
                organisationProvisioningService.retryBootstrap(
                    RetryInitialAdministratorBootstrapCommand(
                        organisationId,
                        TenantCaller(actorId, organisationId),
                    ),
                )
            }
        }

        val recorded =
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ACTION
                    .eq("tenant.bootstrap_retry")
                    .and(AUDIT_EVENT.ENTITY_ID.eq(organisationId))
                    .and(AUDIT_EVENT.OUTCOME.eq("FAILURE")),
            )
        assertEquals(1, recorded)
    }
}
