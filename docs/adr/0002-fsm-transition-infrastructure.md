# ADR 0002: FSM Transition Infrastructure

## Status

Accepted

## Context

Finaxis needs reusable lifecycle infrastructure for domain workflows such as shipments, trips,
inventory transfers, receiving, dispatch, invoices, payment runs, and approvals. The existing
Django implementation in `~/Ibuqa/platform` shows the architectural need: deterministic transition
graphs, legal direction checks, pre-transition business validation, transition notes and logs,
actor metadata, post-commit events, metrics, mobile sync hints, and domain-specific side effects.

The Spring Boot platform is a modular monolith. It already uses Spring Modulith, Spring Data JDBC,
Flyway, RabbitMQ, Namastack Outbox, and JobRunr. The transition design must preserve module
boundaries and must not turn RabbitMQ, outbox, or background jobs into implicit behavior for every
state change.

## Decision

We will implement reusable FSM transition infrastructure in the common package.

We will use Spring Modulith for modular boundaries, application events, cross-module reactions,
observability, and module verification.

We will use Namastack Outbox as the transactional outbox mechanism.

We will externalize selected Modulith events to RabbitMQ.

We will use JobRunr for background processing, not as the primary outbox or event externalization
engine.

The common FSM package defines type-safe transition graphs, transition definitions, guards,
policies, synchronous effects, transition commands, actors, logs, event factories, and a transition
executor. Domain modules own their state enums, transition enums, authorization checks, persistence
adapters, domain events, externalized event routing names, and JobRunr jobs.

## Consequences

Legal transition direction is deterministic and testable. A transition cannot move an aggregate
from one state to another unless a `TransitionDefinition` declares that exact source state and
transition name.

Transition state mutation, guard evaluation, log creation, event publication, broker publishing,
and background jobs are separated. The executor publishes Spring application events but does not
publish directly to RabbitMQ and does not enqueue JobRunr jobs.

Namastack Outbox remains the reliability boundary for externalized integration messages. RabbitMQ
consumers remain adapters that delegate to application services. JobRunr remains reserved for
durable operational jobs.

Domain modules can define explicit business events instead of relying only on generic transition
events. Generic events remain available for low-level infrastructure use cases.

The first implementation provides the core port and executor foundation. Database-backed transition
log adapters and domain-specific FSMs will be added by the modules that need them.

## Tradeoffs

Pros:

- Reusable lifecycle rules across modules.
- Clear test surface for valid and invalid transitions.
- Keeps persistence, messaging, and jobs behind ports/listeners.
- Preserves Spring Modulith module boundaries.
- Avoids direct RabbitMQ publishing inside business transactions.
- Avoids using JobRunr as an outbox substitute.

Cons:

- Domain modules must explicitly define graphs and event factories.
- A persistence adapter is required before production transitions can store logs.
- Generic transition events are intentionally not a replacement for domain event modeling.

## Alternatives Considered

Use a heavy external FSM library:

- Rejected for now. The required behavior is explicit and small, and an internal abstraction fits
  the modular monolith without adding another lifecycle framework.

Copy the Django mixin model:

- Rejected. Django model mixins couple transition behavior to ORM lifecycle hooks. The Spring
  platform uses hexagonal boundaries, application services, ports, and Modulith events.

Publish directly to RabbitMQ from transition services:

- Rejected. This bypasses the transactional outbox and couples business transactions to broker
  availability.

Use JobRunr for event externalization:

- Rejected. JobRunr is for background jobs. Namastack Outbox is the chosen event externalization
  reliability mechanism.
