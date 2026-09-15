package com.finaxis.platform.accounting.config

import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.SubledgerProofProvider
import com.finaxis.platform.accounting.adapter.outbound.context.RequestContextAccountingLookup
import com.finaxis.platform.accounting.application.FiscalPeriodStateChangeGuard
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.FunctionalCurrencyLock
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.accounting.application.ledger.DefaultPostingService
import com.finaxis.platform.accounting.application.ledger.JournalNumberAllocator
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.JournalReversalService
import com.finaxis.platform.accounting.application.ledger.JournalStore
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingLegResolver
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.port.outbound.PostingMetadataLookup
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.reconciliation.SubledgerProofProviderRegistry
import com.finaxis.platform.accounting.application.rules.PostingRuleStore
import com.finaxis.platform.accounting.application.rules.RuleBackedPostingLegResolver
import com.finaxis.platform.common.audit.AuditService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Single wiring point for the accounting module.
 *
 * Adapters that a unit test needs to construct directly - the context lookup - stay free of
 * Spring stereotypes and are declared here. Adapters that only ever exist as beans carry
 * `@Component` instead; `PostgresProofSnapshot` is one.
 *
 * State which of the two an adapter is when adding it. An earlier revision of this KDoc claimed
 * *all* accounting adapters were stereotype-free while `PostgresProofSnapshot` already carried
 * `@Component`, and a follower of that rule would ship a port with no bean at all - which broke
 * context startup platform-wide once already.
 *
 * The two transaction beans are not declared here on purpose.
 * [com.finaxis.platform.accounting.application.ledger.SerializablePostingTransaction] and
 * [PostingTransactionBoundary] carry `@Service`, because the Kotlin Spring plugin opens annotated
 * classes for subclass proxying and a `@Bean`-constructed class with no stereotype stays `final` -
 * which would leave `@Transactional` on the first of them as advice that never runs.
 */
@Configuration(proxyBeanMethods = false)
class AccountingModuleConfiguration {
    /**
     * The one adapter allowed to read the ambient request context, serving both ports over it:
     * [AccountingContextLookup] for the tenant, branch and actor, and [PostingMetadataLookup] for
     * the request id a posting records as lineage.
     *
     * Declared by its concrete type rather than as two beans, because two bean methods returning
     * one stateless instance are still two bean *definitions*, and by-type injection of
     * [PostingMetadataLookup] then fails as ambiguous even though both would resolve to the same
     * object. One bean is also the truthful shape: there is one thread-local reader here, exactly
     * as `RequestContextAccountingLookup` claims.
     */
    @Bean
    fun accountingContextLookup(): RequestContextAccountingLookup = RequestContextAccountingLookup()

    /**
     * Resolves and locks the fiscal period a posting commits into.
     *
     * Declared here rather than annotated `@Service` because it is constructed directly by unit
     * tests with fakes, and because it and [fiscalPeriodStateChangeGuard] had to become beans in
     * the same change as their [FiscalPeriodStateStore] adapter — a bean without one broke
     * context startup platform-wide once already.
     */
    @Bean
    fun postingPeriodResolver(
        periods: FiscalPeriodStateStore,
        businessDates: AccountingBusinessDateLookup,
        permissions: AccountingPermissionGuard,
        auditService: AuditService,
        clock: Clock,
    ) = PostingPeriodResolver(periods, businessDates, permissions, auditService, clock)

    /** Serialization point issue #39's period close and reopen must go through. */
    @Bean
    fun fiscalPeriodStateChangeGuard(periods: FiscalPeriodStateStore) =
        FiscalPeriodStateChangeGuard(periods)

    /**
     * The one place a journal is created. Declared here rather than annotated `@Service` because
     * unit tests construct it directly with fakes, and because the engine and its stores became
     * beans in the same change as the `V7` tables they write.
     */
    @Suppress("LongParameterList")
    @Bean
    fun postingEngine(
        contextLookup: AccountingContextLookup,
        metadata: PostingMetadataLookup,
        tenants: AccountingTenantLookup,
        currency: FunctionalCurrencyLock,
        periods: PostingPeriodResolver,
        accounts: GlAccountStore,
        journals: JournalStore,
        journalReads: JournalReadStore,
        numbers: JournalNumberAllocator,
        clock: Clock,
        snapshots: SnapshotIsolationGuard,
    ) = PostingEngine(
        contextLookup,
        metadata,
        tenants,
        currency,
        periods,
        accounts,
        journals,
        journalReads,
        numbers,
        clock,
        snapshots,
    )

    /**
     * Resolves a product module's intent against the rule version effective on the posting date.
     * Declared here rather than annotated, like the engine, so the resolver the engine and the
     * dry run share is visibly one bean.
     */
    @Bean
    fun postingLegResolver(
        rules: PostingRuleStore,
        tenants: AccountingTenantLookup,
    ): PostingLegResolver = RuleBackedPostingLegResolver(rules, tenants)

    /**
     * The sub-ledger proof providers, validated once at startup.
     *
     * Declared here rather than annotated because its whole purpose is to be constructed with the
     * full set of providers and refuse a malformed or contested registration before anything runs;
     * a bean method is where Spring hands that set over. An empty list is legitimate - no product
     * module exists yet - and a control class with no provider is reported per run.
     */
    @Bean
    fun subledgerProofProviderRegistry(providers: List<SubledgerProofProvider>) =
        SubledgerProofProviderRegistry(providers)

    /** The public posting API product modules consume. */
    @Bean
    fun postingService(
        engine: PostingEngine,
        resolver: PostingLegResolver,
        reversals: JournalReversalService,
        boundary: PostingTransactionBoundary,
    ): PostingService = DefaultPostingService(engine, resolver, reversals, boundary)
}
