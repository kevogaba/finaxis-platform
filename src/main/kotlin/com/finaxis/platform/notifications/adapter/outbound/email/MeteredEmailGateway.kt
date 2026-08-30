package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer

/**
 * Decorates an [EmailGateway] with delivery-attempt count and latency metrics. Tags are limited
 * to the fixed [EmailCategory] name and a fixed outcome value — never a recipient or body value.
 */
class MeteredEmailGateway(
    private val delegate: EmailGateway,
    private val meterRegistry: MeterRegistry,
) : EmailGateway {
    override fun send(message: EmailMessage): EmailDeliveryReceipt {
        val sample = Timer.start(meterRegistry)
        return try {
            val receipt = delegate.send(message)
            record(sample, message.category, OUTCOME_SUCCESS)
            receipt
        } catch (ex: PermanentEmailDeliveryException) {
            record(sample, message.category, OUTCOME_PERMANENT_FAILURE)
            throw ex
        } catch (ex: RetryableEmailDeliveryException) {
            record(sample, message.category, OUTCOME_RETRYABLE_FAILURE)
            throw ex
        }
    }

    private fun record(
        sample: Timer.Sample,
        category: EmailCategory,
        outcome: String,
    ) {
        val tags = Tags.of("category", category.name, "outcome", outcome)
        meterRegistry.counter(ATTEMPTS_METER, tags).increment()
        sample.stop(meterRegistry.timer(DURATION_METER, tags))
    }

    private companion object {
        const val ATTEMPTS_METER = "finaxis.email.delivery.attempts.total"
        const val DURATION_METER = "finaxis.email.delivery.duration"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_PERMANENT_FAILURE = "permanent_failure"
        const val OUTCOME_RETRYABLE_FAILURE = "retryable_failure"
    }
}
