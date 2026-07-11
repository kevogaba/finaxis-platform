# FSM Transition Infrastructure

Finaxis uses reusable finite state machine infrastructure for document and workflow lifecycles:
shipments, trips, inventory transfers, receiving, dispatch, invoices, payment runs, and approvals.
The common infrastructure lives in `com.finaxis.platform.common.transitions`.

The design is inspired by the Django transition mixins in `~/Ibuqa/platform/app/transitions` and
their use in logistics and inventory documents. The intent we keep is deterministic state graphs,
pre-transition validation, atomic state/log creation, actor and reason metadata, post-commit events,
and explicit side effects. The Spring implementation does not copy Django model mixins. It uses
Kotlin interfaces, application ports, Spring application events, and Spring Modulith boundaries.

## Concepts

- `Transitionable<S>` is implemented by aggregates with a stable `aggregateId`, `aggregateType`,
  current `state`, and a `transitionTo` mutation method.
- `TransitionDefinition<S, T, A>` defines one legal named transition from one state to one target
  state. It may include guards, policies, synchronous effects, event factories, and metadata.
- `TransitionGraph<S, T, A>` indexes definitions, lists legal transitions from a state, rejects
  illegal direction changes, and exports Mermaid graph text for docs/tests.
- `TransitionCommand` carries request metadata: reason, comment, metadata, occurred time,
  correlation id, and request id.
- `TransitionActor` records who or what requested the change.
- `TransitionGuard` and `TransitionPolicy` reject transitions before mutation.
- `TransitionEffect` is only for synchronous work that must be atomic with the transition.
- `TransitionLog` captures audit data for accepted transitions.
- `TransitionLogRepository` is an outbound port. Domain modules provide persistence adapters.
- `TransitionEventPublisher` is an outbound port. The Spring adapter publishes application events.
- `TransitionEvent`, `InternalTransitionEvent`, and `ExternalizedTransitionEvent` provide generic
  infrastructure events. Domain modules should still publish explicit business events where useful,
  such as `ShipmentApprovedEvent` or `InventoryTransferConfirmedEvent`.

## Defining States And Transitions

Domain modules define enums for their states and transition names. A transition is legal only if a
matching `TransitionDefinition` exists for the current aggregate state.

```kotlin
enum class ShipmentState { DRAFT, READY, APPROVED, LOADED, IN_PROGRESS, COMPLETE, CANCELLED }
enum class ShipmentTransition { SUBMIT, APPROVE, LOAD, START, COMPLETE, CANCEL }
```

```kotlin
val graph =
    TransitionGraph(
        listOf(
            TransitionDefinition(
                transition = ShipmentTransition.APPROVE,
                from = ShipmentState.READY,
                to = ShipmentState.APPROVED,
                guards = listOf(shipmentHasLinesGuard),
                eventFactories = listOf(TransitionEventFactory(::shipmentApprovedEvent)),
            ),
        ),
    )
```

Direction is deterministic. Do not infer target states from enum ordering, role names, UI buttons,
or old status strings. If a transition is allowed, it must be declared.

## Executing Transitions

Application services load the aggregate, build a command and actor, and call `TransitionExecutor`.
The service owns authorization and persistence. The executor owns FSM sequencing.

```kotlin
transitionExecutor.execute(
    TransitionExecution(
        aggregate = shipment,
        transition = ShipmentTransition.APPROVE,
        graph = shipmentGraph,
        command = command,
        actor = actor,
        persist = shipmentRepository::save,
    ),
)
```

Execution order:

1. Resolve the transition definition for current state and requested transition.
2. Reject undefined or wrong-direction transitions.
3. Run guards and policies.
4. Mutate aggregate state.
5. Run synchronous effects.
6. Persist the aggregate through the caller-supplied port.
7. Create and store a `TransitionLog`.
8. Publish configured Spring application events.
9. Return `TransitionResult`.

The transactional boundary belongs to the application service. Wrap the call in `@Transactional`
when aggregate persistence, transition logs, and event publication must commit atomically.

## Logging

Every important domain transition should create a log. `TransitionLog` records:

- aggregate type and id
- transition name
- old state and new state
- actor type and id
- reason and comment
- metadata JSON-compatible map
- occurred time and created time
- correlation id and request id

The common module only defines the log port. Persistence adapters should map the log to a
Flyway-managed table using the module's audit conventions.

## Events And Side Effects

A transition may publish no event, one event, or many events. Do not make RabbitMQ or JobRunr a
default behavior of every transition.

Use event factories for events that should be published after the aggregate and log are accepted.
Prefer explicit domain events when the event has business meaning:

- `ShipmentApprovedEvent`
- `TripCompletedEvent`
- `InventoryTransferConfirmedEvent`
- `InvoicePostedEvent`

Generic transition events are acceptable for infrastructure tests, low-value lifecycle
notifications, or common integrations.

## Spring Modulith

Spring Modulith is used for module boundaries, application events, observability, and module
verification. Cross-module reactions should listen to Spring application events instead of calling
another module's internals. `ApplicationModules.of(PlatformApplication::class.java).verify()` is
part of the quality gate.

Use `@ApplicationModuleListener` for post-commit module listeners when work should run only after
the transition transaction commits.

## Namastack Outbox And RabbitMQ

Selected integration events are externalized through Spring Modulith event externalization with
Namastack Outbox:

```yaml
spring:
  modulith:
    events:
      externalization:
        mode: outbox
```

Namastack is the transactional outbox engine. It stores selected events transactionally and
publishes them reliably after commit. RabbitMQ is the external broker path.

Use strongly named external routing targets, for example:

- `tradestack.shipment.changed`
- `tradestack.trip.changed`
- `tradestack.inventory.adjusted`
- `tradestack.mobile-sync.hint`

Do not call `RabbitTemplate` from inside a transition transaction. The preferred path is:

```text
transition -> Spring application event -> Spring Modulith externalization
  -> Namastack outbox -> RabbitMQ
```

RabbitMQ listeners should stay thin:

1. Deserialize.
2. Validate.
3. Delegate to an application service.
4. Enforce idempotency where needed.
5. Ack or nack based on outcome.

## JobRunr

JobRunr is for durable background work, not outbox/event externalization. Use it for work shaped
like a job:

- send email or SMS
- generate PDFs
- recalculate reports
- imports and exports
- delayed retries
- slow third-party calls
- recurring reconciliation

A transition may publish an event, and a post-commit listener may enqueue a JobRunr job. JobRunr is
not the primary outbox and should not replace Namastack for reliable event externalization.

## Testing

Every domain FSM should include tests for:

- valid transitions
- invalid transitions
- guard and policy failures
- log creation
- no-event transitions
- one-event and multiple-event transitions
- graph introspection and Mermaid output
- domain-specific event publication
- externalization routing for integration events
- Spring Modulith boundary verification

For persistence adapters, use Spring integration tests with Testcontainers and Flyway-managed
schema. For pure transition graphs, use focused unit tests with in-memory aggregates and fake ports.
