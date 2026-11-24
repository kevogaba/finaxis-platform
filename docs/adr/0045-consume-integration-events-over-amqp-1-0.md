# ADR 0045: Consume Integration Events Over RabbitMQ's Native AMQP 1.0, Failing Fast At Startup

## Status

Accepted, **implemented** in the release containing this change.

Date: 2026-11-24

Changes the consumer half of the [ADR 0004](0004-membership-activation-notification-pipeline.md)
reference pipeline. The publisher half, [ADR 0008](0008-transactional-outbox-over-direct-amqp.md)'s
Namastack outbox, is unchanged: it publishes through Namastack's own RabbitMQ module, over AMQP
0.9.1, until Namastack supports AMQP 1.0 (decision 8).

## Context

Spring Boot 4.2 split its AMQP support: `spring-boot-starter-rabbitmq` is RabbitMQ's AMQP 0.9.1,
and `spring-boot-starter-amqp-rabbitmq` is RabbitMQ's native AMQP 1.0, the protocol RabbitMQ 4
treats as its own (Spring AMQP's `spring-rabbitmq-client`, on RabbitMQ's AMQP 1.0 Java client).
The owner chose to adopt AMQP 1.0 without changing the pipeline: every consumer now, and the
publisher once the Namastack outbox it publishes through supports AMQP 1.0.

Three things differ from the AMQP 0.9.1 containers:

- Both starters register a `@RabbitListener` container factory under the same bean name,
  `rabbitListenerContainerFactory`, so which protocol the listeners use would depend on
  auto-configuration order while both are on the classpath.
- RabbitMQ's AMQP 1.0 client makes one attempt at its first connection. Spring AMQP's
  `RabbitAmqpAdmin` and `RabbitAmqpListenerContainer` connect when the context starts, so an
  unreachable broker fails application startup. The AMQP 0.9.1 containers started anyway and
  retried in the background, so the HTTP API ran through a broker outage at startup.
- Settlement uses AMQP 1.0 outcomes: `accept`, `discard` (rejected) and `requeue` (released).

## Decision

1. **Every `@RabbitListener` consumes over AMQP 1.0.** `AmqpListenerConfiguration`
   (`common.adapter.inbound.messaging`) declares the `rabbitListenerContainerFactory` bean itself,
   a `RabbitAmqpListenerContainerFactory` configured by Spring Boot's configurer, so the protocol
   does not depend on auto-configuration order. A new listener needs no factory of its own.
2. **The topology is declared over AMQP 1.0** by `RabbitAmqpAdmin`, from the same `Exchange`,
   `Queue`, `Binding` and `Declarables` beans. The AMQP 0.9.1 `RabbitAdmin` still declares them too
   (idempotently, identical arguments), since the outbox publishes over AMQP 0.9.1 (decision 8).
3. **Settlement is unchanged in meaning.** A listener's `AmqpRejectAndDontRequeueException`, or a
   fatal conversion failure, settles the message as rejected (`discard`): it is dropped, or
   dead-lettered where a queue has a dead-letter exchange, and never delivered again. Any other
   exception releases it (`requeue`), so it is delivered again. A listener must keep throwing
   `AmqpRejectAndDontRequeueException` for a message it can never process.
   `AmqpListenerSettlementIntegrationTests` proves both on the broker.
4. **Fail fast at startup (owner decision).** The application refuses to start while RabbitMQ is
   unreachable, and the orchestrator restarts it. Rejected: starting anyway and retrying in the
   background (about 80 lines of start-up code that would have kept the API up through a broker
   outage at startup), and failing fast only under the `production` profile (two behaviours). After
   the first connection, the AMQP 1.0 client's own recovery reconnects, re-declares the topology
   and resumes the consumers, as the AMQP 0.9.1 containers did.
5. **`finaxis.amqp.enabled`** (default `true`) runs the admin and the listener containers. It is
   `false` only for a context that runs no broker: the test `application.yaml` sets it, and the
   two test configurations that run a broker set it back. `false` means they never start, not
   only that they do not start with the context: Spring's test context cache pauses an idle
   context and restarts it, and Spring AMQP's endpoint registry, whose containers never ran, then
   starts every container whatever its auto-startup. So the admin and each container refuse to
   start while it is `false`.
6. **Connection settings** are `spring.amqp.rabbitmq.*`, from the same `FINAXIS_RABBITMQ_*`
   variables as `spring.rabbitmq.*`: RabbitMQ serves both protocols on one port. RabbitMQ 4.0 or
   later is required.
7. **Native access.** Netty, under the AMQP 1.0 client, loads its native transport through a
   restricted method, which JDK 25 warns about unless native access is granted. The executable
   jar's manifest (`Enable-Native-Access: ALL-UNNAMED`), the JVM image's `JAVA_TOOL_OPTIONS`,
   `bootRun` and the test JVMs grant it.
8. **Every event is still published by the Namastack outbox, over AMQP 0.9.1 (owner decision).**
   The publish leg is unchanged: transition, Spring Modulith externalization, Namastack outbox,
   then Namastack's `RabbitOutboxHandler`, routed by the `RabbitOutboxRouting` bean. Namastack
   2.0 publishes to RabbitMQ only through Spring AMQP's `RabbitTemplate`, which is AMQP 0.9.1,
   and has no AMQP 1.0 module. Rejected: a platform-written `OutboxHandler` over
   `RabbitAmqpTemplate`, which would keep the outbox's records but replace Namastack's publishing
   with the platform's own. The publisher moves to AMQP 1.0 when a Namastack release supports
   it; until then `spring-boot-starter-rabbitmq` stays for it.

## Consequences

- A RabbitMQ outage during a deploy or restart now keeps the new instance from starting, where it
  used to start without its consumers. The outbox still buffers every event in PostgreSQL, so no
  event is lost either way.
- Test contexts that run no broker no longer log a connection failure per listener every few
  seconds: nothing connects.
- Two RabbitMQ clients run side by side on the same port and credentials: AMQP 0.9.1 for the
  Namastack outbox's publisher, AMQP 1.0 for the consumers and the topology.
- The `/actuator/health` aggregate gains the `amqprabbit` component beside `rabbit`.
- The native image has not been rebuilt with the AMQP 1.0 client (its build needs more memory than
  CI has, see `docs/operations/native-image-deployment.md`); the JVM image is what deploys.
