package com.finaxis.platform.lifecycle

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.lifecycle.application.FoundationLifecycleService
import com.finaxis.platform.lifecycle.application.OrganisationTransitionCommand
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleTransition
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Layer 2 of the platform-organisation protection (issue #205), on PostgreSQL and with the
 * application guard bypassed: `FoundationLifecycleService.transition` is called directly, as a
 * future caller that skipped `OrganisationProvisioningService` would. The transition graph's guard
 * refuses it with a 409 `conflict` (never a 500 from `V20`'s CHECK, which the guard fires before),
 * the `DENIED` audit row survives the rollback because it is recorded independently, and the
 * platform row is untouched. The only state the test adds to the shared container is that audit
 * row.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PlatformOrganisationTransitionGuardIntegrationTests(
    private val lifecycleService: FoundationLifecycleService,
    private val dsl: DSLContext,
) {
    private val platformId = PlatformOrganisation.ID

    @Test
    fun `the engine refuses to suspend the platform organisation and audits the denial`() {
        val before = platformRow()
        val deniedBefore = deniedAudits()

        val failure =
            assertFailsWith<ConflictException> {
                lifecycleService.transition(
                    OrganisationTransitionCommand(
                        platformId,
                        OrganisationLifecycleTransition.SUSPEND,
                    ),
                )
            }

        assertEquals("conflict", failure.code)
        assertEquals(
            "The platform organisation cannot be suspended, deprovisioned or otherwise changed " +
                "through the tenant lifecycle.",
            failure.safeDetail,
        )
        // The refusal rolled back with the transaction; the audit row did not.
        assertEquals(deniedBefore + 1, deniedAudits())
        assertEquals(before, platformRow())
        assertEquals("ACTIVE", before.first)
        assertEquals(0, platformTransitionRows("SUSPEND"))
    }

    @Test
    fun `the engine refuses to start deprovisioning the platform organisation`() {
        val before = platformRow()

        assertFailsWith<ConflictException> {
            lifecycleService.transition(
                OrganisationTransitionCommand(
                    platformId,
                    OrganisationLifecycleTransition.START_DEPROVISIONING,
                ),
            )
        }

        assertEquals(before, platformRow())
        assertEquals(0, platformTransitionRows("START_DEPROVISIONING"))
    }

    /** Status and row version: neither may move. */
    private fun platformRow(): Pair<String, Long> {
        val record =
            dsl.fetchOne("SELECT status, row_version FROM organisation WHERE id = ?", platformId)!!
        return record.get(0, String::class.java) to record.get(1, Long::class.java)
    }

    private fun deniedAudits(): Int =
        dsl
            .fetchOne(
                "SELECT COUNT(*) FROM audit_event WHERE entity_id = ? AND outcome = 'DENIED' " +
                    "AND action IN ('organisation.suspend', 'organisation.start_deprovisioning')",
                platformId,
            )!!
            .get(0, Int::class.java)

    private fun platformTransitionRows(transition: String): Int =
        dsl
            .fetchOne(
                "SELECT COUNT(*) FROM organisation_transition_log " +
                    "WHERE entity_id = ? AND transition_name = ?",
                platformId,
                transition,
            )!!
            .get(0, Int::class.java)
}
