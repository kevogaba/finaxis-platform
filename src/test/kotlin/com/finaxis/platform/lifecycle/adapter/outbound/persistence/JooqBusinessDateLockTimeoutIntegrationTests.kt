package com.finaxis.platform.lifecycle.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.support.PostingTenantFixture
import com.finaxis.platform.common.persistence.TransactionLockTimeout
import com.finaxis.platform.lifecycle.application.BusinessDateStore
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bound on a business-date mutation, proved against a real PostgreSQL rather than asserted.
 *
 * `BusinessDateService` applies `SET LOCAL lock_timeout` and translates the expiry into
 * `lifecycle.business_date_lock_timeout`, which `docs/api/foundation-api.md` advertises to clients
 * as a retryable `409`. That translation rests on one claim the service cannot check for itself:
 * **PostgreSQL's `55P03` reaches the caller as Spring's `CannotAcquireLockException`**, and not as
 * `QueryTimeoutException` or an untranslated `DataAccessException`. `FiscalPeriodLifecycleService`
 * got that wrong once and shipped a published code nothing could raise, which is recorded in a
 * comment there; this is the business-date twin of the test that stops it happening again.
 *
 * `BusinessDateServiceTests` owns the other half - that the service turns that exception into that
 * code - with a fake store, so the two together cover the path end to end without this suite
 * needing to reconstruct the service and its eight collaborators.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JooqBusinessDateLockTimeoutIntegrationTests(
    private val postingTransactions: PostingTransactionBoundary,
    private val businessDateStore: BusinessDateStore,
    private val lockTimeout: TransactionLockTimeout,
    private val transactionManager: PlatformTransactionManager,
    private val dsl: DSLContext,
    engine: PostingEngine,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val posting =
        PostingTenantFixture(dsl, organisationProvisioningService, engine, ACTOR)

    /**
     * A posting is held open past the point where it holds `business_date` `FOR SHARE`; a
     * `startCob` then runs with a deliberately tiny bound and must give up rather than wait.
     *
     * The bound is 250ms rather than the shipped ten seconds only so the test does not hold a
     * connection for ten; nothing else about the path is altered, and the statement, the table and
     * the lock are the production ones.
     */
    @Test
    fun `a startCob that cannot take the row within its bound fails as a lock-acquisition error`() {
        val tenant = posting.provisionTenant("cob-lock-timeout")
        val postingApplied = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)

        val failure =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val holder =
                    executor.submit {
                        posting.inContext(tenant) {
                            postingTransactions.execute("A posting holding the business date") {
                                posting.post(tenant, reference = "dep-cob-lock-timeout")
                                postingApplied.countDown()
                                assertTrue(releasePosting.await(LATCH_TIMEOUT, TimeUnit.SECONDS))
                            }
                        }
                    }
                assertTrue(postingApplied.await(LATCH_TIMEOUT, TimeUnit.SECONDS))

                val expiry =
                    executor
                        .submit<Throwable?> {
                            runCatching { boundedStartCob(tenant) }.exceptionOrNull()
                        }.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)

                releasePosting.countDown()
                holder.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                expiry
            }

        assertTrue(
            failure is CannotAcquireLockException,
            "an expired lock_timeout on business_date must reach the caller as " +
                "CannotAcquireLockException, which is what BusinessDateService catches - got " +
                "${failure?.let { it::class.qualifiedName }}: ${failure?.message}",
        )
        assertEquals(
            "OPEN",
            currentStatus(tenant.organisationId),
            "the close must not have taken effect after failing to acquire the row",
        )
    }

    /** The production statement, under the production bound applier, at a test-sized bound. */
    private fun boundedStartCob(tenant: PostingTenantFixture.Tenant) {
        TransactionTemplate(transactionManager).execute {
            lockTimeout.applyToCurrentTransaction(BOUND)
            val current =
                requireNotNull(businessDateStore.current(tenant.organisationId)) {
                    "the tenant must have a business date"
                }
            businessDateStore.startCob(
                tenant.organisationId,
                current.currentBusinessDate,
                current.rowVersion,
                ACTOR,
            )
        }
    }

    private fun currentStatus(organisationId: UUID): String? =
        businessDateStore.current(organisationId)?.status

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /** Short enough to keep the held connection brief, long enough not to be flaky. */
        val BOUND: Duration = Duration.ofMillis(250)

        const val LATCH_TIMEOUT = 10L
        const val FUTURE_TIMEOUT = 60L
    }
}
