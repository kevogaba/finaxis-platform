package com.finaxis.platform.accounting.config

import com.finaxis.platform.accounting.adapter.outbound.context.RequestContextAccountingLookup
import com.finaxis.platform.accounting.application.port.outbound.AccountingContextLookup
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
}
