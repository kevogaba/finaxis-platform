package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.support.BreakGlassGrantFixture
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.accounting.support.PostingTenantFixture
import com.finaxis.platform.accounting.support.PostingTenantFixture.Tenant
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.authorization.PermissionCacheInvalidator
import com.finaxis.platform.iam.application.port.outbound.PermissionResolutionQueries
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Break-glass authority against a revocation that commits while the posting is running.
 *
 * `journal.post_prior_period` is the code a backdated posting is gated on, and ADR 0022 keeps it
 * out of every default bundle precisely so a tenant can grant it to a named actor and take it back.
 * Taking it back has to mean something immediately, and until issue #123 it did not:
 *
 * - the gate resolved through `EffectivePermissionResolver`, which is **cache-first**, and
 * - a cache *miss* - which is exactly what a revocation produces, because revoking evicts - fell
 *   through to a database read taken from the posting's pinned, pre-revocation snapshot.
 *
 * So the eviction that should have denied the posting was what routed the check onto the path where
 * the revocation was invisible. `SERIALIZABLE` supplies nothing here: the revoker writes rows the
 * posting only reads, which is one rw-dependency edge and no cycle, and *"posting, then
 * revocation"* is a perfectly good serial order. *"A revocation takes effect immediately"* is a
 * real-time ordering claim, and only a lock supplies one.
 *
 * `AuthorizationService.requireBreakGlassPermission` now answers from
 * `PermissionResolutionQueries.lockedBreakGlassGrant`: no cache at all, and every row the answer
 * rests on held `FOR SHARE`. Both acquisition orders are asserted below, because each passes
 * without the other's guarantee.
 *
 * See `docs/adr/0026-real-time-gates-on-the-serializable-posting-path.md`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BreakGlassRevocationRaceIntegrationTests(
    private val postingTransactions: PostingTransactionBoundary,
    private val dsl: DSLContext,
    private val permissions: EffectivePermissionResolver,
    private val cacheInvalidator: PermissionCacheInvalidator,
    private val queries: PermissionResolutionQueries,
    private val transactionManager: PlatformTransactionManager,
    engine: PostingEngine,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val posting =
        PostingTenantFixture(dsl, organisationProvisioningService, engine, ACTOR)

    private val grants = BreakGlassGrantFixture(dsl)

    private val probe = LockOverlapProbe(dsl)

    /**
     * The regression this change exists for.
     *
     * The posting's transaction is opened at `SERIALIZABLE` through the production boundary and its
     * snapshot is pinned by a read of the tenant's `organisation` row, which is the production
     * shape - the caller's own source mutation runs in this transaction before the posting. Only
     * then, from a second thread and therefore a second connection, does the revocation commit.
     *
     * The revocation is the production shape, not a contrivance: `RoleManagementService` revokes by
     * setting `user_role_assignment.status` to `REVOKED`, which is what
     * [BreakGlassGrantFixture.revoke] does, followed by the same cache eviction the service
     * performs. The eviction is the point rather than a detail - issue #123's whole mechanism is
     * that it turns the check into a cache *miss*, and a test that left the entry cached would pass
     * against a snapshot-bound read.
     *
     * The ledger is asserted first. Without the locking read the backdated posting is admitted and
     * commits, and `committedJournals` is what catches that; the exception assertion alone would
     * not, because without the read there is no exception.
     */
    @Test
    fun `a backdated posting is denied by a revocation committed after its snapshot`() {
        val tenant = provisionBackdatableTenant("break-glass-window")
        val assignment = grants.grantBreakGlass(tenant.organisationId, ACTOR)
        val revoked = AtomicBoolean()

        val outcome =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                runCatching {
                    posting.inContext(tenant) {
                        postingTransactions.execute("A backdated correction") {
                            pinSnapshot(tenant)
                            if (revoked.compareAndSet(false, true)) {
                                executor
                                    .submit<Unit> { revokeAndEvict(tenant, assignment) }
                                    .get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                            }
                            postBackdated(tenant, "dep-break-glass-window")
                        }
                    }
                }
            }

        assertEquals(REVOKED, assignmentStatus(assignment), "the revocation must have committed")
        assertEquals(
            0,
            committedJournals(tenant),
            "a backdated journal committed under an authority that had already been revoked: " +
                "the break-glass gate was decided from the posting's pinned snapshot",
        )
        val failure = outcome.exceptionOrNull()
        assertNotNull(failure, "the posting neither aborted nor was denied")
        assertTrue(
            isAbortedOrDenied(failure),
            "the posting failed, but not as a serialization failure or an authorization denial: " +
                "${failure::class.qualifiedName}: ${failure.message}",
        )
    }

    /**
     * The other acquisition order, and the one that proves the read takes a lock rather than merely
     * reading fresh.
     *
     * A backdated posting is held open past its break-glass gate, so it holds the grant rows
     * `FOR SHARE`. A revocation is then attempted and proved - out of PostgreSQL's own catalogues,
     * not from thread timing - to be parked on `user_role_assignment` behind this exact backend.
     * Only when the posting commits does the revocation proceed.
     *
     * A non-locking fresh read - a second connection, or a `REQUIRES_NEW` transaction - would pass
     * the first test and fail this one: it would see the revocation when it happened to commit
     * first, and silently let it commit underneath a posting that had already been admitted. That
     * is the difference between "fresh at read time" and linearizable, and it is why this test
     * exists alongside the first rather than instead of it.
     */
    @Test
    fun `a revocation waits for a backdated posting already past its gate`() {
        val tenant = provisionBackdatableTenant("break-glass-wait")
        val assignment = grants.grantBreakGlass(tenant.organisationId, ACTOR)
        val postingApplied = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val holder = AtomicInteger()
        val revocationReturned = AtomicBoolean()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val first =
                executor.submit {
                    posting.inContext(tenant) {
                        postingTransactions.execute("A backdated correction") {
                            holder.set(probe.currentBackendPid())
                            postBackdated(tenant, "dep-break-glass-wait")
                            postingApplied.countDown()
                            assertTrue(releasePosting.await(LATCH_TIMEOUT, TimeUnit.SECONDS))
                        }
                    }
                }
            assertTrue(postingApplied.await(LATCH_TIMEOUT, TimeUnit.SECONDS))

            val revocation =
                executor.submit {
                    revokeAndEvict(tenant, assignment).also { revocationReturned.set(true) }
                }

            probe.awaitBlockedOnRelationBehind(holder.get(), "user_role_assignment")
            assertTrue(
                !revocationReturned.get(),
                "the revocation resolved while the posting was still uncommitted, so it never " +
                    "waited and the grant rows the gate rested on were never held",
            )

            releasePosting.countDown()
            first.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
            revocation.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
        }

        assertEquals(REVOKED, assignmentStatus(assignment))
        assertEquals(1, committedJournals(tenant), "the posting that held the lock must commit")
    }

    /**
     * The cache is no longer consulted at all, which is stronger than evicting it correctly.
     *
     * Issue #123's mechanism runs through the cache in both directions: a *hit* answers from a set
     * resolved before the revocation, and a *miss* - which evicting produces - falls through to a
     * snapshot-bound read. The two tests above exercise the miss, as the issue asks, by evicting
     * exactly as `RoleManagementService` does. This one closes the other half by doing the
     * opposite: the entry is warmed with the grant in place, the revocation commits **without**
     * evicting, and the posting must still be denied.
     *
     * A cache-first gate passes every other test in this class and fails this one. It is also the
     * assertion that would fail first if someone routed break-glass back through
     * `EffectivePermissionResolver` for symmetry with the ordinary check.
     */
    @Test
    fun `a stale cached grant does not admit a backdated posting`() {
        val tenant = provisionBackdatableTenant("break-glass-cached")
        val assignment = grants.grantBreakGlass(tenant.organisationId, ACTOR)
        val membershipId = grants.membershipId(tenant.organisationId, ACTOR)

        val warmed = permissions.effectivePermissions(membershipId, null)
        assertTrue(
            BREAK_GLASS_CODE in warmed,
            "the cache must be warmed with the grant in place, or this proves nothing",
        )
        grants.revoke(assignment)
        assertTrue(
            BREAK_GLASS_CODE in permissions.effectivePermissions(membershipId, null),
            "the cached entry must still claim the grant, or the revocation evicted it and this " +
                "test has become the same one as the first",
        )

        val failure =
            runCatching {
                posting.inContext(tenant) {
                    postingTransactions.execute("A backdated correction") {
                        postBackdated(tenant, "dep-break-glass-cached")
                    }
                }
            }.exceptionOrNull()

        assertTrue(
            failure is ForbiddenOperationException,
            "expected an authorization denial, got ${failure?.let { it::class.qualifiedName }}",
        )
        assertEquals(0, committedJournals(tenant))
    }

    /**
     * The defect and its repair, measured side by side in one transaction.
     *
     * The tests above assert the *outcome*. This one asserts the **mechanism** issue #123
     * describes, in the order it describes it: a revocation commits and evicts, the in-flight
     * transaction therefore **misses** the cache, the resolver falls through to a database read
     * taken from the pinned snapshot, and that read still reports the grant. The locking read
     * against the same rows in the same transaction raises `40001` instead of answering.
     *
     * So the eviction really is what routes the check onto the path where the revocation is
     * invisible, and the first assertion below is exactly what the gate would do if it were routed
     * back through `EffectivePermissionResolver`.
     *
     * Driven from a bare `TransactionTemplate` rather than through `PostingTransactionBoundary`,
     * because the boundary retries and a fresh snapshot is no longer stale. Both reads are recorded
     * and asserted after the transaction has unwound, since a transaction that has taken `40001`
     * cannot issue another statement.
     */
    @Test
    fun `the cached resolver reads a revoked grant exactly where the locking read refuses`() {
        val tenant = provisionBackdatableTenant("break-glass-mechanism")
        val assignment = grants.grantBreakGlass(tenant.organisationId, ACTOR)
        val membershipId = grants.membershipId(tenant.organisationId, ACTOR)
        val template =
            TransactionTemplate(transactionManager).apply {
                isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE
            }
        var resolvedAfterRevocation: Set<String>? = null
        var lockingFailure: Throwable? = null

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            runCatching {
                template.execute {
                    pinSnapshot(tenant)
                    executor
                        .submit<Unit> { revokeAndEvict(tenant, assignment) }
                        .get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                    resolvedAfterRevocation = permissions.effectivePermissions(membershipId, null)
                    lockingFailure =
                        runCatching {
                            queries.lockedBreakGlassGrant(membershipId, BREAK_GLASS_CODE)
                        }.exceptionOrNull()
                    null
                }
            }
        }

        assertTrue(
            BREAK_GLASS_CODE in resolvedAfterRevocation.orEmpty(),
            "the cache-first resolver stopped returning the revoked grant, so this scenario no " +
                "longer reproduces the condition the locking read exists for",
        )
        val failure = assertNotNull(lockingFailure, "the locking read answered from stale rows")
        assertTrue(
            failure is ConcurrencyFailureException ||
                generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
                    .any { it is SQLException && it.sqlState == SERIALIZATION_FAILURE },
            "the locking read failed, but not as a serialization failure: " +
                "${failure::class.qualifiedName}: ${failure.message}",
        )
    }

    /**
     * The positive control, without which both tests above pass for a gate that denies everything.
     *
     * A backdated posting by an actor who still holds the grant commits, and the authority is
     * audited where it is enforced.
     */
    @Test
    fun `a backdated posting still commits while the grant stands`() {
        val tenant = provisionBackdatableTenant("break-glass-granted")
        grants.grantBreakGlass(tenant.organisationId, ACTOR)

        posting.inContext(tenant) {
            postingTransactions.execute("A backdated correction") {
                postBackdated(tenant, "dep-break-glass-granted")
            }
        }

        assertEquals(1, committedJournals(tenant))
    }

    /**
     * The negative control: without the grant, the same posting is denied outright.
     *
     * It also pins the shape of the denial. A `ForbiddenOperationException` is an
     * `ApplicationException`, outside `ConcurrencyFailureException` entirely, so the retry boundary
     * propagates it un-retried rather than spending the budget on a decision that cannot change.
     */
    @Test
    fun `a backdated posting without the grant is denied`() {
        val tenant = provisionBackdatableTenant("break-glass-absent")

        val failure =
            runCatching {
                posting.inContext(tenant) {
                    postingTransactions.execute("A backdated correction") {
                        postBackdated(tenant, "dep-break-glass-absent")
                    }
                }
            }.exceptionOrNull()

        assertTrue(
            failure is ForbiddenOperationException,
            "expected an authorization denial, got ${failure?.let { it::class.qualifiedName }}",
        )
        assertEquals(0, committedJournals(tenant))
    }

    /** The revocation as `RoleManagementService` performs it: the row, then the cache. */
    private fun revokeAndEvict(
        tenant: Tenant,
        assignmentId: UUID,
    ) {
        grants.revoke(assignmentId)
        cacheInvalidator.evictMembership(grants.membershipId(tenant.organisationId, ACTOR))
    }

    /**
     * A tenant whose business date sits at its month end, so a one-day backdate stays inside the
     * fiscal period the fixture opened whatever today's date is.
     */
    private fun provisionBackdatableTenant(label: String): Tenant {
        val provisioned = posting.provisionTenant(label)
        return provisioned.copy(businessDate = posting.moveBusinessDateToMonthEnd(provisioned))
    }

    private fun postBackdated(
        tenant: Tenant,
        reference: String,
    ) = posting.post(
        tenant,
        reference = reference,
        dates = PostingDateRequest(postingDate = tenant.businessDate.minusDays(1)),
    )

    /**
     * Fixes the transaction's snapshot before the concurrent revocation commits.
     *
     * The `organisation` row is read because nothing in this test writes it, so pinning cannot
     * itself create the conflict under test.
     */
    private fun pinSnapshot(tenant: Tenant) {
        dsl
            .select(ORGANISATION.BASE_CURRENCY_CODE)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(tenant.organisationId))
            .fetchOne()
    }

    private fun assignmentStatus(assignmentId: UUID): String? =
        dsl
            .select(USER_ROLE_ASSIGNMENT.STATUS)
            .from(USER_ROLE_ASSIGNMENT)
            .where(USER_ROLE_ASSIGNMENT.ID.eq(assignmentId))
            .fetchOne(USER_ROLE_ASSIGNMENT.STATUS)

    private fun committedJournals(tenant: Tenant): Int =
        dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))

    /**
     * Both outcomes the design admits, and nothing else.
     *
     * The denial is what the retry produces: the aborted attempt rolls back, the next one opens a
     * new transaction with a fresh snapshot, reads the revoked assignment, and the gate refuses it.
     * The raw serialization failure is what a caller sees when the budget is spent instead.
     */
    private fun isAbortedOrDenied(failure: Throwable): Boolean =
        failure is ForbiddenOperationException ||
            failure is ConcurrencyFailureException ||
            (
                failure is ConflictException &&
                    failure.code == PostingErrorCodes.POSTING_RETRIES_EXHAUSTED
            ) ||
            generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
                .any { it is SQLException && it.sqlState == SERIALIZATION_FAILURE }

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /** Named once so the fixture and the assertions cannot disagree about the code. */
        val BREAK_GLASS_CODE: String = AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD

        const val LATCH_TIMEOUT = 10L
        const val FUTURE_TIMEOUT = 60L
        const val REVOKED = "REVOKED"

        /** `serialization_failure`; both of its messages map to `ConcurrencyFailureException`. */
        const val SERIALIZATION_FAILURE = "40001"
    }
}
