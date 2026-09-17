package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.accounting.support.PostingTenantFixture
import com.finaxis.platform.accounting.support.PostingTenantFixture.Tenant
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.test.context.TestConstructor
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A tenant's functional currency against a ledger that exists, and against one being created.
 *
 * Two properties, and they are not the same one. The *freeze* is settled: once a journal exists,
 * `base_currency` is refused outright, because immutable lines were written under a currency the
 * tenant would otherwise be re-declaring. The *race* is the window before that, where a tenant has
 * no journal yet and a first posting and a currency change are both in flight; it is closed by the
 * shared advisory lock the posting takes, which the change must wait behind.
 *
 * ## The window `SERIALIZABLE` opened, and the read that closes it
 *
 * Raising the posting path to `SERIALIZABLE` opened a third hole before it closed one.
 * [PostingEngine] reads the functional currency *before* `postNew` takes the shared advisory lock,
 * because the idempotency fingerprint is computed from it and a replay must lock nothing; it then
 * reads it again under the lock. While that second read was a plain one, both came from a single
 * snapshot, so they always agreed and a `base_currency` change committed in between was simply
 * invisible: measured, the tenant's first journal committed in the superseded currency while the
 * organisation declared another.
 *
 * Neither reordering nor the advisory lock closes that. A snapshot taken before a lock is still
 * the snapshot every later read is answered from, and `pg_advisory_xact_lock_shared` does not
 * participate in MVCC, so it produces no re-read and no `40001`. The only shape that closes it is
 * a **locking** read of the organisation row, which is what
 * `AccountingTenantLookup.functionalCurrencyForPosting` now performs and what the third test below
 * holds the engine to.
 *
 * The three tests are three different mechanisms, and the last is the reason to be careful about
 * which is which:
 * - the advisory lock, which makes a change *wait* for an in-flight first posting rather than
 *   interleave with it - it closes the **change's** read of `journal_entry`;
 * - the freeze, which refuses a change outright once a journal exists;
 * - the locking read, which closes the **posting's** read of the currency. It does so by failing
 *   rather than by reporting the new value: `FUNCTIONAL_CURRENCY_CHANGED` cannot fire at
 *   `SERIALIZABLE`, because a change committed after the snapshot aborts the transaction before
 *   the comparison is reached.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FunctionalCurrencyFreezeIntegrationTests(
    private val postingTransactions: PostingTransactionBoundary,
    private val tenantSettings: TenantSettingsService,
    private val dsl: DSLContext,
    engine: PostingEngine,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val posting =
        PostingTenantFixture(dsl, organisationProvisioningService, engine, ACTOR)

    private val probe = LockOverlapProbe(dsl)

    /**
     * The freeze is a check-then-write across two transactions, and this proves the lock closes it.
     *
     * A tenant's very first posting is held open, past the point where it has taken the shared
     * tenant-currency lock. A base-currency change is then started and proved - out of PostgreSQL's
     * own lock catalogue - to be parked on that exact key rather than sailing past a ledger it
     * cannot yet see. Only when the posting commits is the change allowed to proceed, and it now
     * finds the journal and is refused.
     *
     * Without the lock the change would read `journal_entry`, find nothing, and commit alongside a
     * posting it never saw, leaving the tenant declaring a currency its immutable lines were never
     * written under.
     */
    @Test
    fun `a base-currency change waits for a tenant's first posting rather than racing it`() {
        val tenant = posting.provisionTenant("engine-currency-race")
        val currencyKey = AdvisoryLockNamespace.objectId(tenant.organisationId.toString())
        val postingApplied = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val changeReturned = AtomicBoolean()

        val failure =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val first =
                    executor.submit {
                        posting.inContext(tenant) {
                            postingTransactions.execute("The tenant's first posting") {
                                posting.post(tenant, reference = "dep-currency-race")
                                postingApplied.countDown()
                                assertTrue(releasePosting.await(LATCH_TIMEOUT, TimeUnit.SECONDS))
                            }
                        }
                    }
                assertTrue(postingApplied.await(LATCH_TIMEOUT, TimeUnit.SECONDS))

                val change =
                    executor.submit<ConflictException> {
                        assertFailsWith<ConflictException> {
                            withRequestContext { setBaseCurrency(tenant, "USD") }
                        }.also { changeReturned.set(true) }
                    }

                probe.awaitBlockedOnAdvisoryKey(
                    AdvisoryLockNamespace.ACCOUNTING_TENANT_FUNCTIONAL_CURRENCY,
                    currencyKey,
                )
                assertFalse(
                    changeReturned.get(),
                    "the change resolved while the first posting was still uncommitted, so it " +
                        "never waited and the race is still open",
                )

                releasePosting.countDown()
                first.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                change.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
            }

        assertEquals(PostingErrorCodes.FUNCTIONAL_CURRENCY_FROZEN, failure.code)
    }

    @Test
    fun `the functional currency is frozen once a journal is posted`() {
        val tenant = posting.provisionTenant("engine-currency-freeze")

        // Before any journal, the setting may change.
        withRequestContext { setBaseCurrency(tenant, "KES") }

        posting.inContext(tenant) {
            postingTransactions.execute("A posting") { posting.post(tenant) }
        }

        val failure =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    setBaseCurrency(
                        tenant,
                        "USD",
                    )
                }
            }
        assertEquals(PostingErrorCodes.FUNCTIONAL_CURRENCY_FROZEN, failure.code)
    }

    /**
     * The regression this whole change exists for: a first posting that raced a committed
     * base-currency change must not end up as a journal in the currency that lost.
     *
     * The interleaving is forced rather than hoped for. The posting's transaction is opened at
     * `SERIALIZABLE` through the production boundary and its snapshot is pinned by a read of the
     * tenant's business date - which is exactly what happens in production, where the caller's own
     * source mutation runs in this same transaction before the posting (see
     * `PostingTransactionBoundary`). Only then, from a second thread and therefore a second
     * connection, does the change commit; the posting resumes afterwards and reads a currency its
     * snapshot still shows as current.
     *
     * The change is written straight to `organisation.base_currency_code`, and that is deliberate
     * rather than lazy. The engine reads *that column* - not the `base_currency` tenant setting,
     * which lives in `organisation_setting` and is what `TenantSettingsService` writes - so the
     * column is what the engine's guard has to defend, whichever flow moves it. A direct update is
     * the narrowest possible statement of "another transaction committed a different base
     * currency", with no settings machinery in between to explain a pass or a failure. It also
     * runs at `READ COMMITTED`, as every production writer of that column does, which matters:
     * a `SERIALIZABLE` writer would let SSI abort the posting on its own and the test would pass
     * with or without the locking read.
     *
     * What is asserted is the ledger, not the exception. With the locking read the posting aborts
     * with a serialization failure (`40001`, at the statement or at the commit - both are
     * accepted, because which one PostgreSQL raises depends on where the conflict is detected).
     * Without it the posting commits a `KES` journal for an organisation that now declares `USD`,
     * and the first assertion below is what catches that.
     */
    @Test
    fun `a first posting cannot commit in a currency a committed change has superseded`() {
        val tenant = posting.provisionTenant("engine-currency-window")

        val outcome =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                runCatching {
                    posting.inContext(tenant) {
                        postingTransactions.execute("The tenant's first posting") {
                            pinSnapshot(tenant)
                            executor
                                .submit<Unit> { supersedeBaseCurrency(tenant, SUPERSEDING) }
                                .get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                            posting.post(tenant, reference = "dep-currency-window")
                        }
                    }
                }
            }

        val declared = currentBaseCurrency(tenant)
        assertEquals(SUPERSEDING, declared, "the concurrent change must have committed")
        val committed = committedJournalCurrencies(tenant)
        assertTrue(
            committed.all { it == declared },
            "the ledger holds $committed while the organisation declares $declared: a journal " +
                "was committed in a currency a committed change had already superseded",
        )
        val failure = outcome.exceptionOrNull()
        assertNotNull(failure, "the posting neither aborted nor was refused")
        assertTrue(
            isAbortedOrRefused(failure),
            "the posting failed, but not as a serialization failure or a currency refusal: " +
                "${failure::class.qualifiedName}: ${failure.message}",
        )
    }

    /**
     * Fixes the transaction's snapshot before the concurrent change commits.
     *
     * A real posting's transaction has already read - the caller's own source mutation, and the
     * engine's isolation guard - by the time it reads the currency, so a snapshot pinned here is
     * the production shape rather than a contrivance. `business_date` is read because nothing in
     * this test writes it, so pinning cannot itself create the conflict under test.
     */
    private fun pinSnapshot(tenant: Tenant) {
        dsl
            .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
            .from(BUSINESS_DATE)
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(tenant.organisationId))
            .fetchOne()
    }

    /** Commits a new base currency from another connection, at the default isolation level. */
    private fun supersedeBaseCurrency(
        tenant: Tenant,
        code: String,
    ) {
        val updated =
            dsl
                .update(ORGANISATION)
                .set(ORGANISATION.BASE_CURRENCY_CODE, code)
                .set(ORGANISATION.ROW_VERSION, ORGANISATION.ROW_VERSION.plus(1))
                .where(ORGANISATION.ID.eq(tenant.organisationId))
                .execute()
        assertEquals(1, updated, "the change must have hit exactly one organisation row")
    }

    private fun currentBaseCurrency(tenant: Tenant): String? =
        dsl
            .select(ORGANISATION.BASE_CURRENCY_CODE)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(tenant.organisationId))
            .fetchOne(ORGANISATION.BASE_CURRENCY_CODE)

    private fun committedJournalCurrencies(tenant: Tenant): List<String?> =
        dsl
            .select(JOURNAL_ENTRY.FUNCTIONAL_CURRENCY_CODE)
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
            .fetch(JOURNAL_ENTRY.FUNCTIONAL_CURRENCY_CODE)

    /**
     * Both outcomes the design admits, and nothing else.
     *
     * The serialization failure is what the locking read produces at `SERIALIZABLE`; the refusal
     * is what the comparison would produce below it. The raw `SQLSTATE` is checked as well as
     * Spring's translated type, because a failure raised by `COMMIT` is thrown by the transaction
     * interceptor rather than by a statement and does not always arrive already translated.
     */
    private fun isAbortedOrRefused(failure: Throwable): Boolean =
        failure is ConcurrencyFailureException ||
            (
                failure is ConflictException &&
                    failure.code == PostingErrorCodes.FUNCTIONAL_CURRENCY_CHANGED
            ) ||
            generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
                .any { it is SQLException && it.sqlState == SERIALIZATION_FAILURE }

    private fun setBaseCurrency(
        tenant: Tenant,
        value: String,
    ) {
        tenantSettings.createOrUpdate(
            CreateOrUpdateTenantSettingCommand(
                organisationId = tenant.organisationId,
                key = "base_currency",
                value = value,
                actorId = ACTOR,
            ),
        )
    }

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val LATCH_TIMEOUT = 10L
        const val FUTURE_TIMEOUT = 60L

        /** The currency the concurrent change commits, distinct from the provisioned `KES`. */
        const val SUPERSEDING = "USD"

        /** `serialization_failure`; both of its messages map to `ConcurrencyFailureException`. */
        const val SERIALIZATION_FAILURE = "40001"
    }
}
