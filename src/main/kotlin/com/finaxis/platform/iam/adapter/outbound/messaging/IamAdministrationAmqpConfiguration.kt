package com.finaxis.platform.iam.adapter.outbound.messaging

import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.Queue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Declares durable routes for externally published IAM role administration events. */
@Configuration
class IamAdministrationAmqpConfiguration {
    /** Creates IAM administration exchanges before Namastack outbox workers publish events. */
    @Bean
    fun iamAdministrationExchanges(): Declarables {
        val queue = Queue(IAM_ADMINISTRATION_QUEUE, true)
        val exchanges = IAM_ADMINISTRATION_EXCHANGES.map { FanoutExchange(it, true, false) }
        val bindings = exchanges.map { exchange -> BindingBuilder.bind(queue).to(exchange) }
        return Declarables(listOf(queue) + exchanges + bindings)
    }

    private companion object {
        val IAM_ADMINISTRATION_EXCHANGES =
            listOf("finaxis.iam.user.role-assigned", "finaxis.iam.user.role-revoked")
        const val IAM_ADMINISTRATION_QUEUE = "finaxis.iam.administration-events"
    }
}
