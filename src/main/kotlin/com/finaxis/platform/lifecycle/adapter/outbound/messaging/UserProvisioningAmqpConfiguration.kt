package com.finaxis.platform.lifecycle.adapter.outbound.messaging

import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.Queue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declares durable exchanges and queue for local user provisioning events.
 * [IdentityProvisioningListener] consumes events from the declared queue and schedules
 * JobRunr workers for Keycloak and application-invite provisioning.
 */
@Configuration
class UserProvisioningAmqpConfiguration {
    /** Creates lifecycle user-provisioning exchanges and the durable shared queue binding. */
    @Bean
    fun userProvisioningLifecycleExchanges(): Declarables {
        val queue = Queue(USER_PROVISIONING_QUEUE, true)
        val exchanges = USER_PROVISIONING_EXCHANGES.map { FanoutExchange(it, true, false) }
        val bindings = exchanges.map { exchange -> BindingBuilder.bind(queue).to(exchange) }
        return Declarables(listOf(queue) + exchanges + bindings)
    }

    private companion object {
        val USER_PROVISIONING_EXCHANGES =
            listOf(
                "finaxis.lifecycle.user.keycloak-provisioning-requested",
                "finaxis.lifecycle.user.application-invite-requested",
                "finaxis.lifecycle.user.deactivation-assignment-revoked",
            )
        const val USER_PROVISIONING_QUEUE = "finaxis.lifecycle.user-provisioning-events"
    }
}
