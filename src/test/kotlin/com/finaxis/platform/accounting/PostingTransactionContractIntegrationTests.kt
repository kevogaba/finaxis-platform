package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.support.PostingTenantFixture
import com.finaxis.platform.accounting.support.PostingTenantFixture.Tenant
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The transaction contract of the public posting service, and nothing else.
 *
 * Two halves, and the second is the one issue #108 added. `PostingService.post` is
 * `Propagation.MANDATORY`, so it refuses to run with no transaction at all — that half has always
 * held. What is new is that a transaction is no longer sufficient: the posting path declares
 * `SERIALIZABLE`, and Spring ships `validateExistingTransaction = false`, so an isolation declared
 * on a method that *joins* an already-open transaction is dropped with no log line and no
 * exception. A bare [TransactionTemplate] opens at the server default, `READ COMMITTED`, and the
 * only thing that catches it is
 * [com.finaxis.platform.accounting.application.ledger.PostingEngine] asking the database what is
 * actually in force.
 *
 * Kept apart from `PostingEngineIntegrationTests` deliberately. That suite proves what a posting
 * *does*, and every posting in it enters through the production boundary; this one proves what the
 * path refuses to do at all, and needs a transaction opened wrongly on purpose. Housing both in
 * one class meant the suite carried a `READ COMMITTED` template that nothing else was allowed to
 * touch — an attractive nuisance for the next author, and the reason detekt's `LargeClass`
 * eventually objected.
 *
 * Neither case resolves a leg or reaches an account, so both are refused long before the tenant
 * [PostingTenantFixture] provisions is fully used. It is used all the same rather than provisioning
 * a smaller tenant by hand: the copy that answered "far enough to be postable and no further" was
 * the duplication this suite's own KDoc was complaining about.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingTransactionContractIntegrationTests(
    private val postingService: PostingService,
    private val dsl: DSLContext,
    engine: PostingEngine,
    transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val posting =
        PostingTenantFixture(dsl, organisationProvisioningService, engine, ACTOR)

    /** At the server default, `READ COMMITTED`: the thing under test, never a way to post. */
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `the public service refuses to post with no transaction at all`() {
        val tenant = posting.provisionTenant("contract-mandatory")

        assertFailsWith<IllegalTransactionStateException> {
            posting.inContext(tenant) { postingService.post(command(tenant, "dep-outside")) }
        }

        assertEquals(0, postingRequestCount(tenant), "a refused posting claims nothing")
    }

    @Test
    fun `the public service refuses a transaction that is not actually serializable`() {
        val tenant = posting.provisionTenant("contract-isolation")

        val refusal =
            assertFailsWith<ConflictException> {
                posting.inContext(tenant) {
                    transactions.execute {
                        postingService.post(
                            command(tenant, "dep-read-committed"),
                        )
                    }
                }
            }

        assertEquals(PostingErrorCodes.SNAPSHOT_ISOLATION_UNAVAILABLE, refusal.code)

        // The ordering is the point, not merely the refusal. The guard is the engine's second
        // statement, before the idempotency claim, so a misconfigured caller is refused
        // deterministically. Were it after the claim, whether the caller was refused would depend
        // on whether this particular request happened to be a replay - a misconfiguration that
        // fails on the first attempt and succeeds on every retry is the worst possible signal.
        assertEquals(
            0,
            postingRequestCount(tenant),
            "the refusal must land before the claim, or a misconfigured caller would be refused " +
                "on its first attempt and accepted on every retry",
        )
    }

    private fun command(
        tenant: Tenant,
        reference: String,
    ) = PostFinancialFactsCommand(
        context = posting.context(tenant),
        source = posting.source(reference),
        intent = PostingIntent.Facts("SAVINGS_DEPOSIT", emptyList()),
    )

    private fun postingRequestCount(tenant: Tenant) =
        dsl.fetchCount(POSTING_REQUEST, POSTING_REQUEST.ORGANISATION_ID.eq(tenant.organisationId))

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
