package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.adapter.outbound.persistence.FiscalCalendarFixture
import com.finaxis.platform.accounting.application.FiscalPeriodLifecycleService
import com.finaxis.platform.accounting.application.FiscalPeriodStateChangeCommand
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.FISCAL_PERIOD_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Open, close, reopen and lock against PostgreSQL, including the controls that make a reopen
 * exceptional rather than routine.
 *
 * The concurrency half — that a close waits for in-flight postings and a posting never lands in a
 * period it observed as closed — is proved by `FiscalPeriodConcurrencyIntegrationTests` against the
 * same lock this service takes. What is proved here is the lifecycle on top of it.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FiscalPeriodLifecycleIntegrationTests(
    private val lifecycle: FiscalPeriodLifecycleService,
    private val periods: FiscalPeriodStateStore,
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val calendar = FiscalCalendarFixture(dsl)

    @Test
    fun `a period opens, closes and locks, and each transition leaves a log row`() {
        // The positive control, and the assertion that matters most beside it: a state change with
        // no history is the audit gap the FSM exists to close, and until this branch the shared
        // executor could not write an accounting log row at all.
        val key = provisionPeriod("open-close", FiscalPeriodStatus.FUTURE)

        withRequestContext { lifecycle.open(command(key, MAKER)) }
        withRequestContext { lifecycle.close(command(key, MAKER)) }
        withRequestContext { lifecycle.lock(checkerCommand(key, reason = "Year-end signed off")) }

        assertEquals(FiscalPeriodStatus.LOCKED, statusOf(key))
        assertEquals(
            listOf("OPEN", "CLOSE", "LOCK"),
            transitionNames(key),
            "every transition must be recorded, in order",
        )
    }

    @Test
    fun `every transition leaves an audit row, not only reopen`() {
        // An earlier revision audited reopen alone, so closing or permanently locking a period -
        // the operations that decide what can still be posted and what has been reported - left
        // `audit_event` with nothing. The audit-coverage ratchet reported them as wired because it
        // matched `AccountingPermissions.FISCAL_PERIOD_CLOSE`, which holds the same string as the
        // audit action, in a file that mentioned `auditService` for a different reason.
        val key = provisionPeriod("audit", FiscalPeriodStatus.FUTURE)

        withRequestContext { lifecycle.open(command(key, MAKER, reason = "Period begins")) }
        withRequestContext { lifecycle.close(command(key, MAKER, reason = "Month end")) }
        withRequestContext { checkerCommandFor(key, "Late accrual").let(lifecycle::reopen) }
        withRequestContext { lifecycle.close(command(key, MAKER, reason = "Re-closed")) }
        withRequestContext { checkerCommandFor(key, "Filed").let(lifecycle::lock) }

        assertEquals(
            listOf(
                "fiscal_period.close",
                "fiscal_period.close",
                "fiscal_period.lock",
                "fiscal_period.open",
                "fiscal_period.reopen",
            ),
            auditActionsFor(key).sorted(),
            "each transition audits, and lock is distinguishable from close",
        )
    }

    @Test
    fun `the actor who closed a period cannot also lock it permanently`() {
        // The control the reversible operation had and the irreversible one did not. Nothing
        // transitions out of LOCKED, so one actor closing and then locking would freeze a tenant's
        // books permanently with no second pair of eyes and no recovery short of hand-written SQL.
        val key = provisionPeriod("lock-self", FiscalPeriodStatus.OPEN)
        withRequestContext { lifecycle.close(command(key, MAKER, reason = "Month end")) }

        val failure =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { lifecycle.lock(command(key, MAKER, reason = "Filed")) }
            }

        assertEquals(PostingErrorCodes.PERIOD_SELF_APPROVAL, failure.code)
        assertEquals(FiscalPeriodStatus.CLOSED, statusOf(key), "the period must not be locked")
    }

    @Test
    fun `the reason is written to the period row, not only to the transition log`() {
        // status_reason is the column the ERD documents as "why the period is in its current
        // state"; an earlier revision never wrote it, so it was permanently null while the reason
        // lived only in the log.
        val key = provisionPeriod("reason-column", FiscalPeriodStatus.OPEN)

        withRequestContext { lifecycle.close(command(key, MAKER, reason = "December close")) }

        assertEquals("December close", statusReasonOf(key))
    }

    @Test
    fun `the actor who closed a period cannot reopen it`() {
        // INV-10 with the four codes V5 seeded: no fiscal_period.submit exists, so the checker is
        // established by actor identity against the CLOSE row rather than by a second permission.
        val key = provisionPeriod("self-approval", FiscalPeriodStatus.OPEN)
        withRequestContext { lifecycle.close(command(key, MAKER)) }

        val failure =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext {
                    lifecycle.reopen(command(key, MAKER, reason = "Correction needed"))
                }
            }

        assertEquals(PostingErrorCodes.PERIOD_SELF_APPROVAL, failure.code)
        assertEquals(FiscalPeriodStatus.CLOSED, statusOf(key), "the period must not have moved")
    }

    @Test
    fun `a different actor may reopen, and the exercise of that authority is audited`() {
        val key = provisionPeriod("reopen", FiscalPeriodStatus.OPEN)
        withRequestContext { lifecycle.close(command(key, MAKER)) }

        withRequestContext { lifecycle.reopen(checkerCommand(key, reason = "Late accrual")) }

        assertEquals(FiscalPeriodStatus.OPEN, statusOf(key))
        assertEquals(
            1,
            dsl.fetchCount(
                AUDIT_EVENT,
                AUDIT_EVENT.ORGANISATION_ID
                    .eq(key.organisationId)
                    .and(AUDIT_EVENT.ACTION.eq("fiscal_period.reopen")),
            ),
            "a reopen with no audit row is the finding a bank auditor leads with",
        )
    }

    @Test
    fun `a reopen without a reason is refused`() {
        val key = provisionPeriod("reason", FiscalPeriodStatus.OPEN)
        withRequestContext { lifecycle.close(command(key, MAKER)) }

        listOf(null, "", "   ").forEach { reason ->
            val failure =
                assertFailsWith<ApplicationException> {
                    withRequestContext { lifecycle.reopen(checkerCommand(key, reason)) }
                }
            assertEquals(PostingErrorCodes.PERIOD_REASON_REQUIRED, failure.code)
        }
        assertEquals(FiscalPeriodStatus.CLOSED, statusOf(key))
    }

    @Test
    fun `a locked period can never be reopened`() {
        // The distinction the whole four-value status set exists for, and the reason the Fineract
        // closure-date model was rejected: a closed period may come back, a locked one may not.
        val key = provisionPeriod("locked", FiscalPeriodStatus.OPEN)
        withRequestContext { lifecycle.close(command(key, MAKER)) }
        withRequestContext { lifecycle.lock(checkerCommand(key, reason = "Filed")) }

        // Reopened by the actor who locked rather than the one who closed, so the self-approval
        // control cannot be what refuses this and the assertion is about LOCKED alone.
        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext { lifecycle.reopen(checkerCommand(key, reason = "Please")) }
            }

        assertEquals(PostingErrorCodes.PERIOD_LOCKED, failure.code)
        assertEquals(FiscalPeriodStatus.LOCKED, statusOf(key))
    }

    @Test
    fun `an illegal transition is refused before anything is written`() {
        // Closing a period that was never opened. The graph refuses it, and the assertion that
        // nothing was logged is what distinguishes "refused" from "half-applied".
        val key = provisionPeriod("illegal", FiscalPeriodStatus.FUTURE)

        val failure =
            assertFailsWith<ApplicationException> {
                withRequestContext { lifecycle.close(command(key, MAKER)) }
            }

        assertEquals(PostingErrorCodes.PERIOD_TRANSITION_NOT_ALLOWED, failure.code)
        assertEquals(FiscalPeriodStatus.FUTURE, statusOf(key))
        assertTrue(transitionNames(key).isEmpty(), "a refused transition must leave no log row")
    }

    @Test
    fun `every operation is permission gated and tenant scoped`() {
        val key = provisionPeriod("permission", FiscalPeriodStatus.OPEN)

        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { lifecycle.close(command(key, STRANGER)) }
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { lifecycle.get(key, STRANGER) }
        }
        // A key naming another tenant resolves to nothing rather than to that tenant's period.
        val other = tenants.createActiveOrganisation("period-other", MAKER)
        assertFailsWith<ApplicationException> {
            withRequestContext {
                lifecycle.get(FiscalPeriodKey(other, key.fiscalPeriodId), MAKER)
            }
        }
        assertEquals(FiscalPeriodStatus.OPEN, statusOf(key))
    }

    @Test
    fun `the system actor cannot permanently lock a period`() {
        // close() goes through the ordinary tenant check, which authorises the system-actor
        // sentinel before consulting any grant - correct for a background path. lock() must not:
        // it is the one transition nothing reverses, so a batch job that reached it would
        // permanently finalise a tenant's books with no principal holding the CRITICAL authority.
        // The two calls below differ in nothing but which method they reach.
        val key = provisionPeriod("system-actor", FiscalPeriodStatus.OPEN)

        withRequestContext { lifecycle.close(command(key, SystemActor.ID, reason = "Batch close")) }
        assertEquals(FiscalPeriodStatus.CLOSED, statusOf(key))

        assertFailsWith<ForbiddenOperationException> {
            withRequestContext { lifecycle.lock(command(key, SystemActor.ID, reason = "Batch")) }
        }
        assertEquals(FiscalPeriodStatus.CLOSED, statusOf(key))
    }

    private fun provisionPeriod(
        label: String,
        status: FiscalPeriodStatus,
    ): FiscalPeriodKey {
        val unique = "$label-${UUID.randomUUID()}"
        val organisationId = tenants.createActiveOrganisation("period-$label", MAKER)
        val checker = newChecker(unique)
        checkers[organisationId] = checker
        tenants.grantTenantAdmin(organisationId, checker)
        // Deliberately a direct grant rather than a role. fiscal_period.reopen is absent from
        // every default bundle on purpose - AccountingSeparationOfDutiesPolicyTests asserts that
        // no default role carries a break-glass code - so a tenant that wants it grants it to a
        // named actor. Setting it up here the way a tenant would is what keeps this suite honest
        // about how the control is actually reached.
        grantDirectly(organisationId, checker, AccountingPermissions.FISCAL_PERIOD_REOPEN)
        grantDirectly(organisationId, MAKER, AccountingPermissions.FISCAL_PERIOD_REOPEN)
        return calendar.createPeriod(organisationId, status)
    }

    /**
     * A checker user created fresh for each organisation this suite provisions.
     *
     * Two things this deliberately is not. It is **not** `V4`'s seeded `local.checker`: granting a
     * seeded actor a role in every organisation a suite creates left `FoundationSeedDataTests`
     * seeing eight role assignments where it expected one. And it is **not** one shared user
     * looked up by a fixed username, which was the first fix — that still accumulates one role
     * assignment per organisation per run, so any later count over `user_role_assignment` or
     * `user_organisation_membership` fails depending on how often this suite has run against the
     * container. A user per organisation holds exactly one of each, and nothing accumulates.
     */
    private fun newChecker(label: String): UUID {
        val username = "$CHECKER_USERNAME_PREFIX$label"
        val now = java.time.OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, username)
            .set(USER_ACCOUNT.EMAIL, "$username@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Fiscal Period Checker")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .returning(USER_ACCOUNT.ID)
            .fetchOne()!!
            .id!!
    }

    /** Grants one permission straight to an actor's membership, as a tenant administrator would. */
    private fun grantDirectly(
        organisationId: UUID,
        actorId: UUID,
        permissionCode: String,
    ) {
        val membershipId =
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.ID)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(actorId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID)
                ?: error("no membership for $actorId in $organisationId")
        val permissionId =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                .fetchOne(PERMISSION.ID)
                ?: error("permission $permissionCode is not seeded")
        val now = java.time.OffsetDateTime.now()
        dsl
            .insertInto(MEMBERSHIP_PERMISSION)
            .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
            .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
            .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
            .set(MEMBERSHIP_PERMISSION.EFFECT, "ALLOW")
            .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
            .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
            .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    private fun command(
        key: FiscalPeriodKey,
        actorId: UUID,
        reason: String? = null,
    ) = FiscalPeriodStateChangeCommand(key = key, actorId = actorId, reason = reason)

    private fun checkerCommand(
        key: FiscalPeriodKey,
        reason: String? = null,
    ) = command(key, checkers.getValue(key.organisationId), reason)

    /** The checker provisioned alongside each organisation, keyed by that organisation. */
    private val checkers = mutableMapOf<UUID, UUID>()

    private fun checkerCommandFor(
        key: FiscalPeriodKey,
        reason: String,
    ) = command(key, checkers.getValue(key.organisationId), reason)

    private fun auditActionsFor(key: FiscalPeriodKey): List<String> =
        dsl
            .select(AUDIT_EVENT.ACTION)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(key.organisationId))
            .and(AUDIT_EVENT.ACTION.like("fiscal_period.%"))
            .fetch(AUDIT_EVENT.ACTION)
            .filterNotNull()

    private fun statusReasonOf(key: FiscalPeriodKey): String? =
        dsl
            .select(ACCOUNTING_FISCAL_PERIOD.STATUS_REASON)
            .from(ACCOUNTING_FISCAL_PERIOD)
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(key.organisationId))
            .and(ACCOUNTING_FISCAL_PERIOD.ID.eq(key.fiscalPeriodId))
            .fetchOne(ACCOUNTING_FISCAL_PERIOD.STATUS_REASON)

    private fun statusOf(key: FiscalPeriodKey) = requireNotNull(periods.findById(key)).status

    private fun transitionNames(key: FiscalPeriodKey): List<String> =
        dsl
            .select(FISCAL_PERIOD_TRANSITION_LOG.TRANSITION_NAME)
            .from(FISCAL_PERIOD_TRANSITION_LOG)
            .where(FISCAL_PERIOD_TRANSITION_LOG.ORGANISATION_ID.eq(key.organisationId))
            .and(FISCAL_PERIOD_TRANSITION_LOG.ENTITY_ID.eq(key.fiscalPeriodId))
            .orderBy(FISCAL_PERIOD_TRANSITION_LOG.ID.asc())
            .fetch(FISCAL_PERIOD_TRANSITION_LOG.TRANSITION_NAME)
            .filterNotNull()

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /** Prefix for this suite's per-organisation checkers; see `newChecker` on why. */
        const val CHECKER_USERNAME_PREFIX = "fiscal.period.checker."

        /**
         * An actor with no membership and no role anywhere. It needs no `user_account` row,
         * because every path it is used on refuses it at the permission check before any write.
         */
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
