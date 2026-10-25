package com.finaxis.platform.iam.application.role

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.support.AtomicityProbe
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Commit-or-roll-back-together proof for archiving a role (#297). It is not a financial write path
 * (ADR 0029), but it reuses the harness: the status change, its `role_transition_log` row and its
 * audit row become visible together after commit, or not at all.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RoleArchiveAtomicityIntegrationTests(
    private val dsl: DSLContext,
    private val roles: RoleManagementService,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val actorId = uuidV7().also { seedUser(it) }
    private val organisationId = fixture.createActiveOrganisation("role-archive-atomic", actorId)

    private fun newRole(): UUID =
        withRequestContext {
            roles
                .createTenantRole(
                    CreateTenantRole(
                        organisationId,
                        "ATOMIC_${uuidV7().toString().takeLast(8).uppercase()}",
                        "Atomic",
                        null,
                        actorId,
                    ),
                ).roleId
        }

    private fun harness(roleId: UUID) =
        FinancialTransactionAtomicityFixture(
            dsl,
            transactionManager,
            listOf(
                AtomicityProbe("role.row_version[$roleId]") { dsl ->
                    (dsl.fetchValue("SELECT row_version FROM role WHERE id = ?", roleId) as Number)
                        .toLong()
                },
                AtomicityProbe("role_transition_log[$roleId]") { dsl ->
                    (
                        dsl.fetchValue(
                            "SELECT count(*) FROM role_transition_log WHERE entity_id = ?",
                            roleId,
                        ) as Number
                    ).toLong()
                },
                FoundationAtomicityProbes.auditEventRows(
                    organisationId,
                    "role.archive",
                    "SUCCESS",
                ),
            ),
        )

    private fun archive(roleId: UUID) =
        withRequestContext { roles.archiveRole(ArchiveRole(organisationId, roleId, actorId)) }

    private fun status(roleId: UUID): String =
        dsl.fetchValue("SELECT status FROM role WHERE id = ?", roleId).toString()

    @Test
    fun `a failure after an archive rolls back the status, the log row and the audit row`() {
        val roleId = newRole()

        harness(roleId).assertRollsBackAtomically(IllegalStateException::class) {
            archive(roleId)
            assertEquals("ARCHIVED", status(roleId), "the archive must have taken effect")
            error("simulated failure after a successful archive")
        }

        assertEquals("ACTIVE", status(roleId))
    }

    @Test
    fun `an archive becomes visible together with its log row and audit row after commit`() {
        val roleId = newRole()

        harness(roleId).assertVisibleOnlyAfterCommit(
            mapOf(
                "role.row_version[$roleId]" to 1L,
                "role_transition_log[$roleId]" to 1L,
                "audit_event[role.archive, SUCCESS]" to 1L,
            ),
        ) {
            archive(roleId)
        }

        assertEquals("ARCHIVED", status(roleId))
    }

    private fun seedUser(id: UUID) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "role-atomic-$id")
            .set(USER_ACCOUNT.EMAIL, "role-atomic-$id@role-archive.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "atomic")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.CREATED_BY, SystemActor.ID)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_BY, SystemActor.ID)
            .execute()
    }
}
