package com.finaxis.platform.notifications.adapter.inbound.messaging

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.Queue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declares the durable RabbitMQ topology for membership activation notifications.
 */
@Configuration
class MembershipActivatedAmqpConfiguration {
    /**
     * Declares the fanout exchange used by the externalized membership activation event.
     */
    @Bean
    fun membershipActivatedExchange(): FanoutExchange =
        FanoutExchange(MEMBERSHIP_ACTIVATED_EXCHANGE, true, false)

    /**
     * Declares the durable queue consumed by this notifications module.
     */
    @Bean
    fun membershipActivatedNotificationQueue(): Queue = Queue(MEMBERSHIP_ACTIVATED_QUEUE, true)

    /**
     * Binds the notification queue to the membership activation fanout exchange.
     */
    @Bean
    fun membershipActivatedNotificationBinding(
        membershipActivatedNotificationQueue: Queue,
        membershipActivatedExchange: FanoutExchange,
    ): Binding =
        BindingBuilder.bind(membershipActivatedNotificationQueue).to(membershipActivatedExchange)

    private companion object {
        const val MEMBERSHIP_ACTIVATED_EXCHANGE = "finaxis.lifecycle.membership.activated"
        const val MEMBERSHIP_ACTIVATED_QUEUE = "finaxis.notifications.membership-activated"
    }
}
