package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingRetryPolicy
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.MutableContextLookup
import com.finaxis.platform.accounting.support.PostingEngineHarness
import com.finaxis.platform.accounting.support.PostingLockJournal
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a retry does to the lock chain and to the audit trail - the two effects of re-running a
 * posting that PR 2's own boundary suite cannot see.
 *
 * [PostingRetryBoundaryIntegrationTests] proves the retry *happens*: that spring-retry advises the
 * bean, that the budget is spent exactly once over, and that the named code comes back. It says
 * nothing about what the re-run does, and the two things it does are the two this suite is about.
 *
 * **The lock chain is re-acquired whole, from the top.** ADR 0023 fixes one order for a posting -
 * the tenant functional-currency advisory lock taken shared, then the fiscal period `FOR SHARE`,
 * then every distinct `gl_account` `FOR SHARE` in ascending id order, and last the
 * `reference_sequence` row - and the deadlock argument is that this chain is a *prefix* no other
 * flow ever acquires out of order. A retry is the first mechanism in the repository that runs it
 * twice on one thread for one logical operation, so a lock hoisted out of the replayed region by
 * some later optimisation - memoised in the engine, moved up into the boundary, guarded by a
 * `ThreadLocal` - would leave attempt 2 running with locks it no longer holds, and ADR 0023 would
 * be false for exactly the contended case the retry exists to serve.
 *
 * **The audit trail deliberately does not roll back.** `PostingPeriodResolver` records
 * `journal.post_prior_period` through `AuditService.recordIndependently`, which is `REQUIRES_NEW`,
 * so the row survives the rollback of the attempt that wrote it. That is not a leak: each row means
 * *authority was exercised on a posting the ledger accepted as admissible*, which every attempt
 * independently did. It is, however, a permanent multiplication of a break-glass record, and the
 * design accepts it only because the count is bounded and the rows collapse under one grouping key.
 * Both halves of that trade are asserted here, so a later change that raises the budget, moves the
 * write, or makes the rows distinguishable states its new truth in a failing test.
 *
 * **The grouping key has to discriminate, and a third test proves it does.** Collapsing a retry's
 * rows to one authority is only half of what ADR 0025 promises; the other half is that rows which
 * are *not* one authority stay apart. The superseded key,
 * `(actor, tenant, resource, metadata->>'postingDate')`, failed that half silently - a batch of
 * corrections backdated by one operator into one period on one date merged into a single apparent
 * exercise of break-glass authority - so
 * [two distinct backdated postings by one actor stay two authorities] asserts both that the two
 * rows are indistinguishable under the old key and that they separate under the new one. Without
 * the first of those assertions the second would pass under a key that discriminates nothing.
 *
 * **Connections.** No test here is concurrent. The lock-chain test holds exactly one - the
 * boundary's own transaction, which the in-memory fakes never touch. The two audit tests hold two
 * at their peak: the boundary's `SERIALIZABLE` transaction plus the `REQUIRES_NEW` audit write it
 * suspends for. HikariCP is at its unconfigured default of ten.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRetryEffectsIntegrationTests(
    private val dsl: DSLContext,
    private val boundary: PostingTransactionBoundary,
    private val engine: PostingEngine,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fx =
        JournalReversalFixture(
            dsl,
            engine,
            TenantAdminOrganisationFixture(organisationProvisioningService, dsl),
            JournalSchemaFixture(dsl),
            boundary,
        )

    @Test
    fun `a retry re-acquires the whole lock chain and holds nothing across attempts`() {
        // The real proxied boundary supplies the retry advice, because a hand-rolled two-attempt
        // loop would prove only that calling `post` twice records twice - true of any method
        // without memoisation, and nothing at all about the annotation that ships. What the lambda
        // drives is the in-memory harness: no database is needed to state an ordering, and the
        // harness's four lock-taking fakes append to one journal, which is the only way a *prefix*
        // is a recorded fact rather than an inference from four unrelated counters.
        val scenario = LockChainScenario()
        val attempts = AtomicInteger()

        val receipt =
            boundary.execute("A posting whose first attempt loses a serialization race") {
                val posted = scenario.post()
                if (attempts.incrementAndGet() == 1) {
                    // Thrown after the engine returned, so attempt 1 is a complete posting that
                    // then loses its transaction - which is what a commit-time 40001 does.
                    throw CannotAcquireLockException("forced, attempt 1")
                }
                posted
            }

        assertEquals(2, attempts.get(), "one forced failure, then one clean attempt")
        assertEquals(
            LOCK_CHAIN + LOCK_CHAIN,
            scenario.harness.lockJournal.acquisitions,
            "ADR 0023's chain must appear twice, whole and in order. A short second run means a " +
                "lock was hoisted out of the replayed region and attempt 2 proceeded without it; " +
                "an interleaved one means the order itself moved, and the deadlock argument - " +
                "that a posting's chain is a prefix no other flow acquires out of order - is no " +
                "longer true of the contended case the retry exists to serve",
        )
        assertEquals(
            listOf(scenario.lowerAccount, scenario.higherAccount).let { it + it },
            scenario.harness.accounts.locks,
            "ascending id order is re-established from scratch on the retry, not inherited - the " +
                "legs name the accounts the other way round, so this is the engine's ordering",
        )
        assertEquals(
            List(2) { scenario.organisationId to scenario.businessDate },
            scenario.harness.periods.lockedFor,
            "the period is located and locked again on the same tenant and posting date; an " +
                "attempt 2 that reused attempt 1's resolved period would show one entry",
        )
        assertEquals("2", receipt.journalReference, "the retry allocated its own journal number")
    }

    @Test
    fun `an exhausted backdated posting leaves one audit row per attempt and they group as one`() {
        val tenant = backdatingTenant()
        val postingDate = tenant.businessDate.minusDays(1)
        val attempts = AtomicInteger()

        val failure =
            assertFailsWith<ConflictException> {
                fx.inContext(tenant, tenant.checker) {
                    boundary.execute<Unit>("A backdated posting that never wins the race") {
                        engine.post(backdatedRequest(tenant, postingDate)) { balancedLegs(tenant) }
                        attempts.incrementAndGet()
                        // A real database will not exhaust a five-attempt budget on demand - a
                        // losing posting blocks once and aborts once - so the failure is forced
                        // after a genuine posting, including its genuine independent audit write.
                        throw CannotAcquireLockException("forced, every attempt")
                    }
                }
            }

        assertEquals(PostingErrorCodes.POSTING_RETRIES_EXHAUSTED, failure.code)
        assertEquals(PostingRetryPolicy.MAX_ATTEMPTS, attempts.get(), "every attempt ran whole")
        assertEquals(0, ledgerRows(tenant.organisationId), "no journal, no lines, no request")
        assertAuthoritiesBoundAndGrouped(tenant, postingDate)
    }

    /**
     * Asserts the two halves of the trade ADR 0025 accepts: the rows are bounded by the retry
     * budget, and they collapse to one logical authority under the documented grouping key.
     */
    private fun assertAuthoritiesBoundAndGrouped(
        tenant: JournalReversalFixture.Tenant,
        postingDate: LocalDate,
    ) {
        val authorities = priorPeriodAuthorities(tenant.organisationId)
        assertEquals(
            PostingRetryPolicy.MAX_ATTEMPTS,
            authorities.size,
            "the bound, asserted as equality against the constant rather than as 'at least one'. " +
                "recordIndependently is REQUIRES_NEW, so each attempt's row survives that " +
                "attempt's rollback, deliberately: every attempt did locate, lock and validate " +
                "the period before authority was exercised. Fewer rows than attempts would mean " +
                "the write had been hoisted out of the replayed region, leaving the survivors " +
                "under-stating the authority actually exercised; more would mean it now runs " +
                "somewhere other than once per attempt",
        )
        assertEquals(
            setOf(
                listOf(
                    tenant.checker,
                    tenant.organisationId,
                    "savings",
                    RETRIED_REFERENCE,
                ),
            ),
            authorities.map { it.groupingKey() }.toSet(),
            "and they collapse to ONE logical authority under the key ADR 0025 tells auditors to " +
                "group by - (actor, tenant, metadata->>'sourceModule', " +
                "metadata->>'sourceReference'). This is the other half of the accepted trade: " +
                "the rows are permitted to multiply precisely because grouping recovers the " +
                "single exercise of break-glass authority behind them. The source reference is " +
                "the INV-7 idempotency identity and is therefore identical across attempts; a " +
                "row keyed on posting_request.id would not be, since a retry rolls its claim " +
                "back and re-inserts under a fresh uuidv7",
        )
        assertEquals(
            setOf(postingDate.toString()),
            authorities.map { it.postingDate }.toSet(),
            "the posting date still does not drift across attempts - it is resolved once, before " +
                "the claim, precisely so a retry cannot move it. It is no longer the grouping " +
                "key, but a drifting one would mean the retry re-derived dates it must not",
        )
    }

    @Test
    fun `two distinct backdated postings by one actor stay two authorities`() {
        // The case the superseded key could not see. One operator running a batch of corrections
        // backdates several postings into one period on one date, which is a normal shape rather
        // than a contrived one, and under (actor, tenant, resource, postingDate) every one of them
        // wears the same tuple. Grouping there merges separate exercises of break-glass authority
        // - the opposite of what a break-glass record is for - and that is what this asserts is no
        // longer true. Both postings commit: no retry, no forced failure, so the only thing that
        // can multiply or merge the rows is the key itself.
        val tenant = backdatingTenant()
        val postingDate = tenant.businessDate.minusDays(1)

        fx.inContext(tenant, tenant.checker) {
            boundary.execute("The first of two distinct backdated corrections") {
                engine.post(backdatedRequest(tenant, postingDate, "correction-1")) {
                    balancedLegs(tenant)
                }
            }
            boundary.execute("The second of two distinct backdated corrections") {
                engine.post(backdatedRequest(tenant, postingDate, "correction-2")) {
                    balancedLegs(tenant)
                }
            }
        }

        val authorities = priorPeriodAuthorities(tenant.organisationId)
        assertEquals(2, authorities.size, "one row per posting, neither retried")
        assertEquals(
            1,
            authorities.map { it.supersededKey() }.toSet().size,
            "the two rows are INDISTINGUISHABLE under the superseded key: same actor, same " +
                "tenant, same fiscal period, same posting date. This assertion is the defect " +
                "itself, stated so that the one below is not vacuous - a grouping test that " +
                "passes under both keys proves nothing",
        )
        assertEquals(
            2,
            authorities.map { it.groupingKey() }.toSet().size,
            "and they remain TWO authorities under the key ADR 0025 now names, because " +
                "(organisation, source_module, source_reference) is the INV-7 idempotency " +
                "identity: one logical posting expressed as data, so it is shared by the " +
                "attempts of one posting and by nothing else",
        )
    }

    /** A tenant whose checker may backdate, into a period that reaches back past yesterday. */
    private fun backdatingTenant(): JournalReversalFixture.Tenant {
        val tenant = fx.provisionTenant("retry-audit")
        fx.grantDirectly(
            tenant.organisationId,
            tenant.checker,
            AccountingPermissions.JOURNAL_POST_PRIOR_PERIOD,
        )
        // The provisioned period covers the business date's own month, so the day before the
        // business date falls outside it on the first of a month and the posting would be refused
        // with `accounting.fiscal_period_not_found` rather than admitted as backdated. Widening the
        // one period this tenant has is unconditional on purpose: a test whose classification
        // depends on the day it runs is a test that goes green for the wrong reason.
        dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, tenant.businessDate.minusMonths(1))
            .where(ACCOUNTING_FISCAL_PERIOD.ID.eq(tenant.periodId))
            .execute()
        return tenant
    }

    private fun backdatedRequest(
        tenant: JournalReversalFixture.Tenant,
        postingDate: LocalDate,
        reference: String = RETRIED_REFERENCE,
    ) = LedgerPostingRequest(
        context = AccountingContext(tenant.organisationId, tenant.branchId, tenant.checker),
        source = AccountingSourceReference("savings", "SAVINGS_DEPOSIT", uuidV7(), reference),
        eventCode = "SAVINGS_DEPOSIT",
        entryType = JournalEntryType.STANDARD,
        dates = PostingDateRequest(postingDate = postingDate),
    )

    private fun balancedLegs(tenant: JournalReversalFixture.Tenant) =
        ResolvedLegs(
            listOf(
                PostingLeg(tenant.debitAccountId, PostingSide.DEBIT, kes("250.00")),
                PostingLeg(tenant.creditAccountId, PostingSide.CREDIT, kes("250.00")),
            ),
            null,
        )

    /**
     * One `journal.post_prior_period` row, projected onto both grouping keys.
     *
     * [actorId], [organisationId], [sourceModule] and [sourceReference] are the key ADR 0025 names.
     * [fiscalPeriodId] and [postingDate] are the rest of the row, and are kept here because the
     * discrimination test asserts on them directly: they are precisely the fields the superseded
     * key was built from, and the point of that test is that two different postings agree on every
     * one of them.
     */
    private data class PriorPeriodAuthority(
        val actorId: UUID?,
        val organisationId: UUID?,
        val fiscalPeriodId: UUID?,
        val postingDate: String?,
        val sourceModule: String?,
        val sourceReference: String?,
    )

    /** The superseded key: actor, tenant, fiscal period, posting date. */
    private fun PriorPeriodAuthority.supersededKey() =
        listOf(actorId, organisationId, fiscalPeriodId, postingDate)

    /** The key ADR 0025 now names: actor, tenant and the `INV-7` source reference. */
    private fun PriorPeriodAuthority.groupingKey() =
        listOf(actorId, organisationId, sourceModule, sourceReference)

    /**
     * Every `journal.post_prior_period` authority recorded for [organisationId], one per row.
     *
     * Read directly rather than through `FoundationAtomicityProbes.auditEventRows`, which counts
     * rows and cannot see whether they group: widening a probe shared by every atomicity suite to
     * carry a JSONB projection only this assertion wants would be the more expensive change.
     */
    private fun priorPeriodAuthorities(organisationId: UUID): List<PriorPeriodAuthority> =
        dsl
            .select(
                AUDIT_EVENT.ACTOR_USER_ID,
                AUDIT_EVENT.ORGANISATION_ID,
                AUDIT_EVENT.ENTITY_ID,
                metadataText("postingDate"),
                metadataText("sourceModule"),
                metadataText("sourceReference"),
            ).from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
            .and(AUDIT_EVENT.ACTION.eq(AccountingAuditActions.JOURNAL_POST_PRIOR_PERIOD))
            .and(AUDIT_EVENT.OUTCOME.eq("SUCCESS"))
            .fetch { row ->
                PriorPeriodAuthority(
                    row.value1(),
                    row.value2(),
                    row.value3(),
                    row.value4(),
                    row.value5(),
                    row.value6(),
                )
            }

    /** One top-level JSONB metadata key, read as text. */
    private fun metadataText(key: String) =
        DSL.field("{0} ->> {1}", String::class.java, AUDIT_EVENT.METADATA_JSONB, DSL.inline(key))

    /** Posting requests, journal headers and journal lines a tenant holds, summed. */
    private fun ledgerRows(organisationId: UUID): Int =
        dsl.fetchCount(POSTING_REQUEST, POSTING_REQUEST.ORGANISATION_ID.eq(organisationId)) +
            dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId)) +
            dsl.fetchCount(JOURNAL_LINE, JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))

    /**
     * One posting over the in-memory harness, with the ids the lock-chain assertions read back.
     *
     * A class rather than a few locals so the work the retried lambda does is a single call: the
     * lambda has to be short enough to read as "post, then lose the transaction", because
     * everything this test claims is about what happened *twice* rather than about the posting.
     */
    private class LockChainScenario {
        val organisationId: UUID = uuidV7()
        val businessDate: LocalDate = LocalDate.of(2026, 8, 20)

        private val accountIds = listOf(uuidV7(), uuidV7()).sorted()

        /** The lower of the two account ids, and therefore the one locked first. */
        val lowerAccount: UUID = accountIds.first()

        /** The higher of the two, named first on the legs so the ordering is the engine's. */
        val higherAccount: UUID = accountIds.last()

        private val context = AccountingContext(organisationId, uuidV7(), uuidV7())

        /** The engine and the fakes behind it, including the shared lock journal. */
        val harness =
            PostingEngineHarness(
                organisationId,
                uuidV7(),
                businessDate,
                "KES",
                Clock.fixed(
                    businessDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC,
                ),
                MutableContextLookup(context),
            )

        init {
            harness.accounts.put(lowerAccount)
            harness.accounts.put(higherAccount)
        }

        /** Posts one balanced two-line journal through the harness's real engine. */
        fun post(): PostingReceipt =
            harness.engine.post(
                LedgerPostingRequest(
                    context = context,
                    source =
                        AccountingSourceReference(
                            "savings",
                            "SAVINGS_DEPOSIT",
                            uuidV7(),
                            "dep-lock-chain",
                        ),
                    eventCode = "SAVINGS_DEPOSIT",
                    entryType = JournalEntryType.STANDARD,
                ),
            ) {
                ResolvedLegs(
                    listOf(
                        PostingLeg(higherAccount, PostingSide.DEBIT, kes("100.00")),
                        PostingLeg(lowerAccount, PostingSide.CREDIT, kes("100.00")),
                    ),
                    null,
                )
            }
    }

    private companion object {
        /** The source reference of the one posting the retry test drives, on every attempt. */
        const val RETRIED_REFERENCE = "dep-backdated"

        /** ADR 0023's chain for a two-account posting, as one attempt records it. */
        val LOCK_CHAIN =
            listOf(
                PostingLockJournal.CURRENCY_SHARED,
                PostingLockJournal.PERIOD,
                PostingLockJournal.ACCOUNT,
                PostingLockJournal.ACCOUNT,
                PostingLockJournal.SEQUENCE,
            )

        fun kes(amount: String) = MonetaryAmount(BigDecimal(amount), "KES")
    }
}
