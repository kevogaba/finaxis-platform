# ADR 0032: Per-Tenant Maker-Checker Switches For Membership And Branch Approval

## Status

Accepted, **implemented** in the release containing this change (issue #272).

Date: 2026-10-18

Supersedes decision 4 of [ADR 0029](0029-approval-model-per-resource-extensions.md) (the two
`require_maker_checker_*` settings are removed, not wired). That decision said keeping either key
for a tenant opt-out "requires its own ADR"; this is that ADR. Builds on
[ADR 0028](0028-platform-checker-for-first-tenant-approvals.md) and on #156's per-resource
refusal codes.

## Context

`require_maker_checker_for_user_invites` and `require_maker_checker_for_branch_creation` were in
the settings catalogue with a default of `false`, but nothing read them: maker-checker was
unconditional. ADR 0029 decided to remove them rather than wire them, because making
maker-checker optional per tenant relaxes segregation of duties.

The owner's ruling reversed that: a setting in the catalogue must do what it says, and the
maker-checker switch must control every maker-checker path it names, when switched off and again
when switched on.

## Upgrade

Both keys were writable and displayed a default of `false` while nothing read them, so a tenant
can hold a stored `false` it never chose. `V31` closes those rows, so deploying the release keeps
segregation on for every tenant, as it was in effect; an opt-out is set again, deliberately, and
audited HIGH. A switch change also takes the organisation row lock that both approval paths take
before they read it, so a concurrent approval cannot act on a value a committed change replaced.

## Decision

1. **The two settings are wired.** `MakerCheckerPolicy` reads them through the settings port.
   Segregation is **required unless the tenant stored the literal `false`**: an unset setting, a
   stored `true` and any other value all mean required, so a tenant never loses a control by
   accident. The catalogue default is now `true` (it was `false` while nothing read it, which
   would have switched every tenant's control off the day it was wired).
2. **What "off" means.** Off lifts only the *segregation checks* of the tenant's own approvers.
   The approval step, its permission (`user.approve`, `branch.approve`) and the FSM are unchanged.
   - Membership approval: the inviter (maker) and the invited user (beneficiary) may approve.
   - Branch approval: the creator, and anyone who amended the branch, may approve.
3. **Tenant scope only.** The switch never loosens the platform checker. The platform checker is a
   bounded platform safeguard (ADR 0028) that a tenant setting must not be able to relax, so a
   platform-scope approval keeps every maker, beneficiary, submitter and amender refusal whatever
   the tenant stored. Tenant provisioning approval and the accounting maker-checker are separate
   controls and are not governed by these settings.
4. **Audited.** An approval that relied on a lifted check is a *self-approval*:
   - membership: the existing `user.approve` row is written `HIGH` with metadata
     `selfApproved = true`;
   - branch: one extra `branch.self_approve` row, `HIGH`, beside the FSM's `branch.activate` row
     (ADR 0031: a service row may add only a fact the FSM row cannot carry);
   - changing either setting is itself audited `HIGH` (it loosens a safeguard).
   An approval by a checker who made and amended nothing writes no such marker, so the rows are
   a clean query for "who approved their own work".
5. **Who may change it.** Anyone holding `settings.update` in the tenant (with its `settings.view`
   requirement), like any other tenant setting. Making it platform-only is a one-line catalogue
   change if the owner later wants tenants unable to relax their own controls.

## Consequences

- Behaviour is unchanged for every existing tenant: no rows exist for the two keys, so both
  controls read as required.
- A tenant with a single approver can switch the control off and approve its own work. That is the
  point of the opt-out, and it is visible: HIGH audit rows, and the setting change itself.
- ADR 0028's platform window is unaffected: it counts `ACTIVE` members and branches beyond the
  bootstrap ones, and a self-approved member or branch counts like any other.
- The `#164` retirement plan for these two keys no longer applies; no migration is needed.

## Not covered

- Per-flow switches beyond the two (return routes grant nothing and have no maker refusal).
- Retention of the new audit rows (see the audit retention follow-up, #278).
