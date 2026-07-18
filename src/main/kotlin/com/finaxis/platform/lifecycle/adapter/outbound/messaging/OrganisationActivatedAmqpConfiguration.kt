package com.finaxis.platform.lifecycle.adapter.outbound.messaging

import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.Queue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declares durable exchanges for organisation lifecycle integration events.
 * Consumers can bind their own durable queues without changing the lifecycle transaction.
 */
@Configuration
class OrganisationActivatedAmqpConfiguration {
    /** Creates lifecycle exchanges before Namastack outbox workers publish accepted transitions. */
    @Bean
    fun organisationLifecycleExchanges(): Declarables {
        val queue = Queue(ORGANISATION_LIFECYCLE_QUEUE, true)
        val exchanges = ORGANISATION_LIFECYCLE_EXCHANGES.map { FanoutExchange(it, true, false) }
        val bindings = exchanges.map { exchange -> BindingBuilder.bind(queue).to(exchange) }
        return Declarables(listOf(queue) + exchanges + bindings)
    }

    private companion object {
        val ORGANISATION_LIFECYCLE_EXCHANGES =
            listOf(
                "finaxis.lifecycle.organisation.approval-requested",
                "finaxis.lifecycle.organisation.activated",
                "finaxis.lifecycle.organisation.rejected",
                "finaxis.lifecycle.organisation.suspended",
                "finaxis.lifecycle.organisation.reactivated",
                "finaxis.lifecycle.organisation.deprovisioned",
                "finaxis.lifecycle.branch.approval-requested",
                "finaxis.lifecycle.branch.activated",
                "finaxis.lifecycle.branch.suspended",
                "finaxis.lifecycle.branch.reactivated",
                "finaxis.lifecycle.branch.closed",
                "finaxis.lifecycle.branch.user-assigned",
                "finaxis.lifecycle.branch.user-revoked",
                "finaxis.lifecycle.organisation.settings-updated",
                "finaxis.lifecycle.organisation.business-date-initialized",
                "finaxis.lifecycle.organisation.business-date-advanced",
                "finaxis.lifecycle.organisation.cob-started",
                "finaxis.lifecycle.organisation.cob-completed",
                "finaxis.lifecycle.organisation.business-date-reopened",
            )
        const val ORGANISATION_LIFECYCLE_QUEUE = "finaxis.lifecycle.organisation-events"
    }
}
