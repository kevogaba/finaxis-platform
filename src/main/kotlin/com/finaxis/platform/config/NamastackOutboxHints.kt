package com.finaxis.platform.config

import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.InternalTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * Registers what the Namastack outbox reaches reflectively.
 *
 * Every gap here fails the same quiet way: the application starts, `/actuator/health` reports `UP`,
 * and the outbox stops draining while nothing else looks wrong. That is why the native image is
 * verified against a write path that publishes an event rather than against startup alone.
 *
 * Three distinct reaches:
 *
 * - **The payload.** An externalized event is written to `outbox_record` as JSON when the
 *   transaction commits and read back by the polling publisher, through Namastack's own Jackson
 *   mapper rather than any Spring binding AOT would notice. Without the metadata the failure is
 *   worse than an exception: serialization silently produces `{}`, so the record is written empty
 *   and only the publisher fails, on a payload that can no longer be recovered.
 * - **The handler.** `OutboxHandlerInvoker` dispatches to the handler's method reflectively.
 * - **The background tasks.** Both outbox lifecycle beans hand their work to a `TaskScheduler` on
 *   start-up, and Spring runs each tick through a reflective `ScheduledMethodRunnable`. Spring AOT
 *   registers the scheduled work it can find by walking bean definitions, which needs the bean's
 *   concrete type, and both beans are declared by an interface instead.
 */
class NamastackOutboxHints : RuntimeHintsRegistrar {
    private val bindingRegistrar = BindingReflectionHintsRegistrar()

    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        bindingRegistrar.registerReflectionHints(
            hints.reflection(),
            ExternalizedTransitionEvent::class.java,
            InternalTransitionEvent::class.java,
            TransitionActor::class.java,
        )
        REFLECTIVELY_INVOKED_TYPES.keys.forEach { type ->
            hints.reflection().registerTypeIfPresent(
                classLoader,
                type,
                MemberCategory.INVOKE_DECLARED_METHODS,
            )
        }
    }

    internal companion object {
        /** Type to a method on it that the outbox invokes reflectively rather than through code. */
        val REFLECTIVELY_INVOKED_TYPES =
            mapOf(
                "io.namastack.outbox.OutboxProcessingScheduler" to "process",
                "io.namastack.outbox.instance.OutboxInstanceRegistry" to
                    "performHeartbeatAndCleanup",
                "io.namastack.outbox.rabbit.RabbitOutboxHandler" to "handle",
            )
    }
}
