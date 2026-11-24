# ADR 0046: Publish The Outbox Over RabbitMQ's Native AMQP 1.0, Through One Namastack Handler

## Status

Accepted, **implemented** in the release containing this change.

Date: 2026-11-24

Completes the move to AMQP 1.0 that [ADR 0045](0045-consume-integration-events-over-amqp-1-0.md)
began with the consumers. Amends [ADR 0008](0008-transactional-outbox-over-direct-amqp.md): the
Namastack outbox stays the only outbox, but its records are published by one handler of this
repository rather than by Namastack's AMQP 0.9.1 module. Amends the publisher half of
[ADR 0004](0004-membership-activation-notification-pipeline.md).

## Context

Namastack's RabbitMQ module (`namastack-outbox-rabbit`, `RabbitOutboxRouting` and
`RabbitOutboxHandler`) publishes over AMQP 0.9.1 only, through Spring AMQP's `RabbitTemplate`.
Namastack 2.0 has no AMQP 1.0 module. The owner chose to adopt AMQP 1.0 from publisher to
consumers, which leaves the publishing step for this repository to provide.

ADR 0008 forbids any custom poller, publisher or claim logic, because hand-written outbox code had
been the source of lost and duplicated events. What it protects is the outbox itself: the record
written in the business transaction, the polling, claiming, partitioning, retry, backoff and
dead-lettering. The step that turns a claimed record into a broker message is a Namastack
extension point (`OutboxHandler`), which its own RabbitMQ module implements the same way.

## Decision

1. **One handler, `AmqpOutboxHandler` (`common.transitions`), implements Namastack's
   `OutboxHandler`** and is the outbox's only handler. Namastack still writes, polls, claims,
   retries and fails every record; the handler only publishes what it is handed. ADR 0008's rule
   stands for everything else: no other poller, publisher, claim logic or outbox table.
2. **It publishes through Spring AMQP's `RabbitAmqpTemplate`**, over the AMQP 1.0 connection the
   listeners and the topology use: to the exchange named by the event's `target`, with no routing
   key (the exchanges are fanout), as JSON written by Spring Boot's Jackson 3 mapper (the payload
   Namastack's module sent), with the outbox record key as the `X-Outbox-Record-Key` application
   property.
3. **It returns only once RabbitMQ has accepted the message.** A rejected or released message (a
   message no queue is bound to receive is released, the AMQP 1.0 counterpart of
   `fail-on-unroutable`), no outcome within `finaxis.outbox.amqp.publish-timeout` (default `10s`;
   `FINAXIS_OUTBOX_PUBLISH_TIMEOUT`, else the former `FINAXIS_OUTBOX_RABBIT_CONFIRM_TIMEOUT`), a
   connection failure or AMQP switched off (`finaxis.amqp.enabled=false`) all throw, so the outbox
   keeps the record and retries it on its backoff, as it did after a missed publisher confirm.
4. **Records written before the change keep flowing.** Namastack stores the handler id on each
   record; the handler declares Namastack's AMQP 0.9.1 handler id as an identity alias, so a record
   scheduled for it is delivered by the new handler.
5. **The AMQP 0.9.1 stack is removed**: `spring-boot-starter-rabbitmq`, its test starter,
   `namastack-outbox-rabbit`, `RabbitOutboxRouting`, `spring.rabbitmq.*` and
   `namastack.outbox.rabbit.*`. `RabbitAmqpAdmin` alone declares the topology; it starts in the
   earliest lifecycle phase, before the outbox polls.
6. **Only the handler publishes.** `ModuleDependencyRuleTests` refuses `RabbitTemplate` and
   `RabbitAmqpTemplate` anywhere else in production code.

## Consequences

- One protocol, one connection and one set of settings (`spring.amqp.rabbitmq.*`). The
  `/actuator/health` aggregate loses the `rabbit` component; `amqprabbit` remains.
- Delivery stays at least once: an outcome arriving after the publish timeout leaves a duplicate on
  retry, as a late publisher confirm did, and consumers stay idempotent.
- About eighty lines of this repository now sit between the outbox and the broker, covered by
  `AmqpOutboxHandlerTests` (the message and every outcome) and
  `AmqpOutboxHandlerIntegrationTests` (accepted, released, missing exchange, the legacy alias),
  and by the end-to-end pipeline tests.
- A future Namastack AMQP 1.0 module would replace the handler; the alias rule (declare the old id)
  makes that swap as safe as this one.
