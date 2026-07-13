# ADR 0004: Membership Activation Notification Pipeline

## Status

Accepted

Date: 2026-07-13

## Context

Selected lifecycle transitions need reliable integration delivery without coupling transition
services to RabbitMQ or using JobRunr as an event bus. Membership activation is the first concrete
notification flow and establishes the reference pattern for future domain events.

## Decision

- Transition definitions use `eventFactories` to publish Spring application events after the
  transition executor persists the aggregate and transition log.
- `InternalTransitionEvent` is for in-process handling. `ExternalizedTransitionEvent` is selected
  for Modulith externalization and carries a `target` for RabbitMQ delivery.
- Spring Modulith runs in `outbox` externalization mode, with Namastack Outbox as the transactional
  externalization mechanism.
- `RabbitOutboxRouting` selects the RabbitMQ exchange from the externalized event's `target`.
  This is independent of Modulith's `RoutingTarget`: the Modulith-to-Namastack bridge drops the
  exchange, so Namastack routing must be configured explicitly.
- The notifications adapter uses a thin `@RabbitListener`: deserialize and validate the message,
  delegate to the notification application service, and let failures propagate for AMQP handling.
- The application service schedules a JobRunr `JobRequest`; the handler is the welcome-email stub.
  Its job identifier is deterministic from membership ID, transition, and occurrence time so
  redelivery does not enqueue a duplicate job.
- `TransitionExecutor` is a `ROLE_INFRASTRUCTURE` bean. This avoids Spring Modulith 2.1.0
  observability-proxy recursion while rendering its F-bounded generic signature.

The membership-activation flow is:

`membership activation → ExternalizedTransitionEvent → Namastack outbox → RabbitMQ →`
`notifications listener → notification application service → JobRunr JobRequest handler`.

Future domain events should use this separation: a transition factory creates the event, Modulith
and Namastack externalize selected events, RabbitMQ listeners remain adapters, and JobRunr handles
durable background work.

## Consequences

- Lifecycle services do not publish with `RabbitTemplate` and do not enqueue JobRunr jobs directly.
- Externalized events need an explicit target and a matching Namastack Rabbit routing rule.
- Consumers must validate payloads, delegate to application services, and make their downstream
  work idempotent.
- The notification handler intentionally does not integrate with an email provider yet.

## Alternatives Considered

Publish directly from lifecycle services with `RabbitTemplate`:

- Rejected. It couples the business transition to broker availability and bypasses the selected
  transactional externalization path.

Use JobRunr as the outbox or event bus:

- Rejected. JobRunr is for durable background jobs; Namastack Outbox is the selected
  externalization mechanism.

Rely on Modulith's `RoutingTarget` for the RabbitMQ exchange:

- Rejected. The bridge does not retain that exchange for Namastack publishing.

## Verification

- `TransitionExecutorTests`
- `NotificationServiceTests`
- `MembershipActivatedNotificationListenerTests`
- `SendWelcomeEmailJobRequestHandlerTests`
- Membership activation pipeline Spring integration coverage
