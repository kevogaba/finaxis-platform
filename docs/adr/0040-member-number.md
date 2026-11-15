# ADR 0040: The Member Number

## Status

Accepted, **implemented** in the release containing this change (issue #287).

Date: 2026-11-13

Supersedes [ADR 0033](0033-user-profile-edits-and-the-member-sequence.md) decision 6 ("no member
number is allocated, and the `MEMBER` sequence stays reserved"). Builds on
[ADR 0037](0037-member-reinvite-and-dispatch-generation.md) (a re-invite reuses the membership
row) and [ADR 0031](0031-audit-one-row-per-transition-and-explicit-branch.md) (the FSM row is the
audit record).

## Context

Every organisation has had a `MEMBER` counter in `reference_sequence` since `V7`, seeded again at
every approval, and nothing reads it: ADR 0033 deliberately left it reserved until a product needed
the number. The administration frontend now does (#287, a `frontend-gap`): a short, stable number to
show and search a member by, other than a UUID.

Facts that shape the answer:

- **A membership has no draft.** It is created `PENDING_APPROVAL` by an invitation, and an
  invitation can be withdrawn or revoked before it is ever approved.
- **One row per person per organisation, for life.** `uq_membership_organisation_user` makes the
  row and the (user, organisation) pair the same thing, and a re-invite (ADR 0037) reuses and
  resets that row rather than creating a second.
- **Activation has several routes**: the synchronous approval, the Keycloak activation job for a
  user who needed provisioning, the bootstrap administrator's system approval, and reactivation.
  All of them write the new status through one method,
  `JooqFoundationLifecyclePersistence.saveMembership`.
- **`joined_at` was never written on activation** (only the `V3`/`V4` seeds and the local seeder
  set it), and the re-invite reset clears it.

## Decision

1. **Per organisation.** The number is unique within one organisation
   (`uq_membership_organisation_member_number`); a person in two tenants has two numbers. The
   counter is the organisation's own `MEMBER` row, the scope the sequence already had.
2. **A plain positive integer.** `member_number BIGINT`, returned as a JSON number. No prefix or
   padding is stored: a display format is the client's to apply, and changing one later needs no
   migration.
3. **Taken at first activation, inside the activating transaction.** `saveMembership` takes the
   next value of the `MEMBER` counter when a membership becomes `ACTIVE` and holds no number yet
   (`ReferenceSequences.next`, an upsert that row-locks the counter). An invitation that is never
   approved burns nothing, and a rollback - including a write the version check refuses - hands
   the value back, so the numbers of one organisation are gapless across every membership that
   has ever been active. Concurrent activations in one organisation queue on the counter row.
4. **Kept for life, never edited.** Suspension, revocation and a re-invite leave the number alone,
   so a returning member keeps theirs; there is no route that sets it. `joined_at` is stamped by
   the same write when it is null, so it records the first activation, survives a reactivation,
   and records the new join after a re-invite (whose reset clears it).
5. **Read, and filtered by exact match.** `member_number` is on the membership detail and summary
   (tenant routes, and the platform detail), null until first activated, and
   `GET /api/v1/tenant/memberships?member_number=` narrows to it. It is under `membership.view` like
   the rest of the row; no permission code is added.
6. **Existing members are numbered by the migration.** `V35` adds the column and constraints and
   numbers every membership that has ever been active, in the order it first became active (its
   earliest transition-log row into `ACTIVE`, else `joined_at`, else `created_at`, then `id`), then
   moves each counter past the highest number taken. It locks the table against writes while it
   runs, as `V21` did, so nothing is activated half-way through.
7. **The database will require a number of an `ACTIVE` membership one release later.** A
   `CHECK (membership_status <> 'ACTIVE' OR member_number IS NOT NULL)` added with `V35` would fail
   every activation an instance of the previous release makes during a rolling deploy. Instead an
   old instance's activation is left unnumbered, takes its number the next time it becomes
   `ACTIVE`, and a later migration numbers what remains and adds the check.

## Consequences

- A tenant's bootstrap administrator is member 1, and the local `local.admin` and `local.checker`
  are 1 and 2 of the bootstrap tenant.
- The API change is additive: one nullable field on two responses and one query parameter.
- Numbers are not reused: a revoked member's number stays theirs, and the next new member gets the
  next value.
- The counter is a single row per organisation, so activations in one organisation serialise on it
  for the length of their transaction. At membership volumes this is not a bottleneck; the journal
  number makes the same trade (`JooqJournalNumberAllocator`).
- Not done: a `member_number` filter on the tenant user list and global search, and stamping
  `suspended_at` and `revoked_at`, which are likewise never written today.
