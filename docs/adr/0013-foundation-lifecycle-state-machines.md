# ADR 0013: Explicit State Machines For Tenant, Branch, User, And Membership

## Status

Accepted

Date: 2026-08-02

## Context

ADR 0002 established reusable transition infrastructure in
`com.finaxis.platform.common.transitions`: a `TransitionDefinition` declaring source state,
transition name, and target state; a `TransitionGraph` that rejects anything undeclared; guards;
transition logs; and event factories. That ADR describes the *engine*.

This ADR records the four concrete graphs the foundation actually runs, and why each aggregate has
its own state and transition enums rather than sharing a generic status vocabulary.

The four aggregates fail differently. An organisation can be rejected before it ever exists
operationally. A branch can be closed while its parent organisation stays active. A user is global
and can be locked by security independently of any organisation. A membership is the join between
a global user and one organisation, and revoking it must not touch the user. A single shared
`status` enum spanning all four would have to be the union of every state, which means every
aggregate would accept states that are meaningless for it, and the graph could not reject them.

## Decision

Four aggregates, each with its own state enum, transition enum, and graph, all built on the shared
engine from ADR 0002.

**Organisation** — `DRAFT`, `PENDING_APPROVAL`, `PROVISIONING`, `ACTIVE`, `SUSPENDED`,
`DEPROVISIONING`, `DEPROVISIONED`, `REJECTED`, `ARCHIVED`. Transitions: `SUBMIT`,
`START_PROVISIONING`, `ACTIVATE`, `REJECT`, `SUSPEND`, `REACTIVATE`, `START_DEPROVISIONING`,
`START_SUSPENDED_DEPROVISIONING`, `COMPLETE_DEPROVISIONING`, `ARCHIVE`.

**Branch** — `DRAFT`, `PENDING_APPROVAL`, `ACTIVE`, `SUSPENDED`, `CLOSED`, `ARCHIVED`.
Transitions: `SUBMIT`, `ACTIVATE`, `SUSPEND`, `SUSPEND_DRAFT`, `SUSPEND_PENDING_APPROVAL`,
`CONFIRM_SUSPENDED`, `REACTIVATE`, `CLOSE`, `CLOSE_SUSPENDED`, `ARCHIVE`.

**User** (global) — `DRAFT`, `PENDING_APPROVAL`, `PROVISIONING_IDP`, `INVITED`, `ACTIVE`,
`SUSPENDED`, `LOCKED`, `DEACTIVATING`, `DEACTIVATED`, `ARCHIVED`. Transitions: `SUBMIT`,
`START_IDP_PROVISIONING`, `INVITE`, `ACTIVATE`, `SUSPEND`, `REACTIVATE`, `LOCK`, `UNLOCK`,
`START_DEACTIVATION`, `COMPLETE_DEACTIVATION`, `ARCHIVE`.

**Membership** — `PENDING_APPROVAL`, `ACTIVE`, `SUSPENDED`, `REVOKED`. Transitions: `ACTIVATE`,
`SUSPEND`, `REACTIVATE`, `REVOKE`, `REVOKE_PENDING`, `REVOKE_SUSPENDED`.

Four design rules apply to all of them.

**Direction is deterministic.** Where one conceptual action can start from several states, each
gets its own named transition rather than one transition with several sources —
`SUSPEND_DRAFT` / `SUSPEND_PENDING_APPROVAL` / `SUSPEND`, and `CLOSE` / `CLOSE_SUSPENDED`. This
keeps `(state, transition)` a function rather than a relation, so a transition log entry is
unambiguous about what happened.

**`SUSPENDED` is distinct from `LOCKED`.** Suspension is an administrative decision recorded
against the aggregate; locking is a security response. They have different actors, different
reversal permissions, and must be distinguishable in an audit.

**Deprovisioning and deactivation are two-phase.** `DEPROVISIONING` → `DEPROVISIONED` and
`DEACTIVATING` → `DEACTIVATED` exist because the work between them is asynchronous — revoking
assignments, coordinating with Keycloak — and the intermediate state must be observable and
restartable.

**Nothing is hard-deleted.** `ARCHIVED`, `DEPROVISIONED`, `REVOKED`, and `CLOSED` are terminal
states, not deletions, because these records are authorization and audit evidence. ADR 0005 covers
this in full.

Guards enforce cross-aggregate invariants that the graph alone cannot express: a branch cannot
activate unless its organisation is `ACTIVE` or `PROVISIONING`; a branch cannot close while an
active child branch exists; an organisation cannot reactivate with unmet setup requirements; the
maker of a draft cannot approve it.

Externalized transitions attach an `ExternalizedTransitionEvent` through `eventFactories`, which
reaches RabbitMQ through the Modulith/Namastack outbox (ADR 0004, ADR 0008). Transitions never
publish directly.

## Consequences

An illegal transition fails before any mutation, log write, or event publication, so a rejected
attempt cannot leave partial state. It is still audited — `FoundationLifecycleService` records the
rejection independently — so a caller repeatedly attempting an illegal transition is visible.

Each aggregate has its own transition-log table (`organisation_transition_log`,
`branch_transition_log`, `user_account_transition_log`,
`user_organisation_membership_transition_log`) rather than one polymorphic table, so each can
carry its own foreign keys and its own tenant scoping.

Adding a state or transition means editing the enum and the graph together. Because the graph is
declarative, a `(state, transition)` pair that is not declared is rejected by construction — the
failure mode is a blocked operation, not silent corruption.

The cost is verbosity: four enums per aggregate pair and explicit per-source transition names. That
is accepted deliberately over a generic status column, which would move the correctness burden from
the type system into scattered `if` statements.

## Alternatives Considered

One shared lifecycle enum across all four aggregates:

- Rejected. Every aggregate would accept states meaningless to it, and the graph would lose its
  ability to reject them.

A single transition with multiple legal source states (one `SUSPEND` from `DRAFT`,
`PENDING_APPROVAL`, and `ACTIVE`):

- Rejected. Direction stops being deterministic and the transition log becomes ambiguous about
  which path was taken.

Hard-delete on deprovision:

- Rejected by ADR 0005. These rows are audit and authorization evidence.

## Verification

- `FoundationLifecycleTransitionTests` — the full cartesian product of (state, transition) for all
  four graphs; exactly the declared pairs succeed and every other pair is rejected
- `FoundationLifecycleServiceTests` — a transition from the wrong source state is audited and
  rejected as a conflict; branch activation blocked on organisation state; branch close blocked by
  an active child
- `OrganisationBranchProvisioningServiceTests` — maker cannot approve their own draft; reactivation
  rejects each unmet setup prerequisite
- `TransitionExecutorTests` — invalid transition fails before mutation, logging, or publication
- `TransitionLogPersistenceIntegrationTests`, `JooqFoundationLifecyclePersistenceTests` — logs are
  durably written
- ADR 0002, ADR 0004, ADR 0005, ADR 0008
