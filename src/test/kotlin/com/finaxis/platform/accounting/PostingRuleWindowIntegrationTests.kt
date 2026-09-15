package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Which approved windows a successor is compared against before it activates (issue #96).
 *
 * `ex_posting_rule_version_no_overlap` covers `ACTIVE`, `SUPERSEDED` and `RETIRED` alike, so all
 * three are windows an activating version can collide with. The service used to look for the head
 * as `status == ACTIVE` alone, which meant a rule whose head had been retired had no head to find
 * and skipped the comparison entirely: the overlap reached PostgreSQL and came back as a
 * `DataIntegrityViolationException` - a 500 - rather than the `POSTING_RULE_WINDOW_INVALID` the
 * service publishes. These tests pin the service, not the constraint, as the thing that answers.
 *
 * [PostingRuleLifecycleIntegrationTests] owns the rest of the lifecycle; both drive the same
 * [PostingRuleFixture].
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRuleWindowIntegrationTests(
    rules: PostingRuleService,
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fx =
        PostingRuleFixture(
            dsl,
            rules,
            TenantAdminOrganisationFixture(organisationProvisioningService, dsl),
            JournalSchemaFixture(dsl),
        )

    @Test
    fun `a successor overlapping a retired window is refused as a window conflict`() {
        val tenant = fx.provisionTenant("rule-retired-overlap")
        val first =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate.minusMonths(6)),
            )
        val lastGoverned = tenant.businessDate.minusMonths(1)
        val retired =
            withRequestContext {
                fx.retire(
                    tenant,
                    first.id,
                    fx.approver(tenant, "rule-retired-overlap"),
                    effectiveTo = lastGoverned,
                )
            }
        assertEquals(PostingRuleVersionStatus.RETIRED, retired.status)
        assertEquals(lastGoverned, retired.effectiveTo)

        // The successor's first day is the retired window's last day, so the two collide on it.
        val successor = fx.draftVersion(tenant, from = lastGoverned)
        val overlap = assertFailsWith<ConflictException> { fx.activate(tenant, successor) }

        assertEquals(
            PostingErrorCodes.POSTING_RULE_WINDOW_INVALID,
            overlap.code,
            "the service has to name the overlap itself; leaving the exclusion constraint to " +
                "find it turns a stated refusal into a DataIntegrityViolationException",
        )
        assertEquals(
            listOf(PostingRuleVersionStatus.RETIRED, PostingRuleVersionStatus.PENDING_APPROVAL),
            listOf(first.id, successor.id).map { fx.version(tenant, it).status },
            "a refused approval settles nothing: the retired version is not reopened and the " +
                "successor is left where the checker found it, not half-activated",
        )
        assertEquals(
            lastGoverned,
            fx.version(tenant, first.id).effectiveTo,
            "and the retirement date the tenant chose is not quietly moved by the attempt",
        )

        // What the old code left to the database, stated against the database: activating the
        // successor open-ended - the status move the service would have made with no window to
        // close - is refused by ex_posting_rule_version_no_overlap. That refusal is a
        // DataIntegrityViolationException, which is why skipping the comparison produced a 500,
        // and why the assertion on `overlap.code` above is the one that has to hold.
        JournalSchemaFixture.assertViolates("ex_posting_rule_version_no_overlap") {
            dsl
                .update(POSTING_RULE_VERSION)
                .set(POSTING_RULE_VERSION.STATUS, PostingRuleVersionStatus.ACTIVE.name)
                .where(POSTING_RULE_VERSION.ID.eq(successor.id))
                .and(POSTING_RULE_VERSION.STATUS.eq(PostingRuleVersionStatus.PENDING_APPROVAL.name))
                .execute()
        }
    }

    @Test
    fun `a successor taking effect after a retired window activates and leaves it closed`() {
        val tenant = fx.provisionTenant("rule-retired-successor")
        val first =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate.minusMonths(6)),
            )
        val lastGoverned = tenant.businessDate.minusMonths(1)
        withRequestContext {
            fx.retire(
                tenant,
                first.id,
                fx.approver(tenant, "rule-retired-successor"),
                effectiveTo = lastGoverned,
            )
        }

        val successor =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = lastGoverned.plusDays(1), feeShare = "10"),
            )

        assertEquals(PostingRuleVersionStatus.ACTIVE, successor.status)
        assertNull(successor.effectiveTo, "a successor activates open-ended")
        val settled = fx.version(tenant, first.id)
        assertEquals(
            PostingRuleVersionStatus.RETIRED,
            settled.status,
            "a retired version is not superseded after the fact by one that starts later",
        )
        assertEquals(
            lastGoverned,
            settled.effectiveTo,
            "re-closing a settled window would rewrite which version governed dates already " +
                "posted against, so the retirement date has to stand untouched",
        )
        assertEquals(
            listOf(successor.id, first.id),
            listOf(lastGoverned.plusDays(1), lastGoverned).map { date ->
                withRequestContext { fx.dryRun(tenant, "100.00", date) }.postingRuleVersionId
            },
            "each side of the cutoff resolves to the version whose window covers it",
        )
    }

    @Test
    fun `a chain of successors closes only the open head and leaves settled windows alone`() {
        val tenant = fx.provisionTenant("rule-chain")
        val first =
            fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate.minusYears(1)))
        val second =
            fx.activate(
                tenant,
                fx.draftVersion(
                    tenant,
                    from = tenant.businessDate.minusMonths(1),
                    feeShare = "10",
                ),
            )
        val firstClosedAt = fx.version(tenant, first.id).effectiveTo

        val third = fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))

        assertEquals(PostingRuleVersionStatus.ACTIVE, third.status)
        assertNull(third.effectiveTo)
        val reread = listOf(first.id, second.id).map { fx.version(tenant, it) }
        assertEquals(
            listOf(PostingRuleVersionStatus.SUPERSEDED, PostingRuleVersionStatus.SUPERSEDED),
            reread.map { it.status },
            "the open-ended head is still superseded when a settled window sits behind it",
        )
        assertEquals(
            listOf(firstClosedAt, tenant.businessDate.minusDays(1)),
            reread.map { it.effectiveTo },
            "the already-superseded first window keeps the date its own successor gave it; only " +
                "the head moves, or the rule's history silently changes shape",
        )
    }

    @Test
    fun `a successor starting the day the open head started is refused in the head's own words`() {
        // The arm of the check that predates issue #96, kept verbatim: a single open head that did
        // not start before the successor cannot be closed the day before the successor without
        // ending before it began. Widening the search for overlapped windows must not have
        // swallowed the case the narrow search already answered.
        val tenant = fx.provisionTenant("rule-head-start")
        val head =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate.minusMonths(2)),
            )

        val sameDay =
            assertFailsWith<ConflictException> {
                fx.activate(tenant, fx.draftVersion(tenant, from = head.effectiveFrom))
            }

        assertEquals(PostingErrorCodes.POSTING_RULE_WINDOW_INVALID, sameDay.code)
        assertEquals(
            "A successor version must take effect after ${head.effectiveFrom}, when the " +
                "current version began.",
            sameDay.safeDetail,
            "a lone open head still answers in its own words, naming the date the successor has " +
                "to start after rather than describing the windows it collided with",
        )
        assertEquals(
            PostingRuleVersionStatus.ACTIVE,
            fx.version(tenant, head.id).status,
            "the head the successor could not displace is still the head",
        )
    }
}
