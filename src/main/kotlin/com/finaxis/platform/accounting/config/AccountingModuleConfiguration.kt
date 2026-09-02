package com.finaxis.platform.accounting.config

import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.adapter.outbound.context.RequestContextAccountingLookup
import com.finaxis.platform.accounting.application.FiscalPeriodStateChangeGuard
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.ledger.DefaultPostingService
import com.finaxis.platform.accounting.application.ledger.JournalNumberAllocator
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.JournalReversalService
import com.finaxis.platform.accounting.application.ledger.JournalStore
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingLegResolver
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import com.finaxis.platform.accounting.application.posting.PostingService
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
 * `@Component` instead; `PostgresRowLock` is one.
 *
 * State which of the two an adapter is when adding it. An earlier revision of this KDoc claimed
 * *all* accounting adapters were stereotype-free while `PostgresRowLock` already carried
 * `@Component`, and a follower of that rule would ship a port with no bean at all - which broke
 * context startup platform-wide once already.
 */
@Configuration(proxyBeanMethods = false)
class AccountingModuleConfiguration {
    /** Narrow accounting view of the ambient request context. */
    @Bean
    fun accountingContextLookup(): AccountingContextLookup = RequestContextAccountingLookup()

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
        tenants: AccountingTenantLookup,
        periods: PostingPeriodResolver,
        accounts: GlAccountStore,
        journals: JournalStore,
        journalReads: JournalReadStore,
        numbers: JournalNumberAllocator,
        clock: Clock,
    ) = PostingEngine(
        contextLookup,
        tenants,
        periods,
        accounts,
        journals,
        journalReads,
        numbers,
        clock,
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

    /** The public posting API product modules consume. */
    @Bean
    fun postingService(
        engine: PostingEngine,
        resolver: PostingLegResolver,
        reversals: JournalReversalService,
    ): PostingService = DefaultPostingService(engine, resolver, reversals)
}
