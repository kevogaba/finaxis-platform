# Branch provisioning and assignment

Branches are organisation-scoped operating locations. The schema enforces same-organisation
parent branches and same-organisation branch assignments. A branch can be closed or archived, but
the implementation does not physically delete it.

Read this with [organisation provisioning](tenant-provisioning.md),
[FSM transitions](../architecture/fsm-transitions.md), and
[ADR 0005](../adr/0005-organisation-lifecycle-no-hard-delete.md).

## Lifecycle

```mermaid
stateDiagram-v2
    [*] --> DRAFT: createDraft
    DRAFT --> PENDING_APPROVAL: SUBMIT
    PENDING_APPROVAL --> ACTIVE: ACTIVATE
    PENDING_APPROVAL --> DRAFT: RETURN_FOR_CHANGES
    ACTIVE --> SUSPENDED: SUSPEND
    DRAFT --> SUSPENDED: SUSPEND_DRAFT
    PENDING_APPROVAL --> SUSPENDED: SUSPEND_PENDING_APPROVAL
    SUSPENDED --> SUSPENDED: CONFIRM_SUSPENDED
    SUSPENDED --> ACTIVE: REACTIVATE
    ACTIVE --> CLOSED: CLOSE
    SUSPENDED --> CLOSED: CLOSE_SUSPENDED
    CLOSED --> ARCHIVED: ARCHIVE
```

Implemented transitions and effects:

- New to `DRAFT`: `createDraft` requires an `ACTIVE` or `PROVISIONING` organisation, branch code,
  branch name, unique code per organisation, and same-organisation parent branch.
- `DRAFT` to `PENDING_APPROVAL`: `SUBMIT` rechecks code uniqueness and parent boundary, then emits
  `finaxis.lifecycle.branch.approval-requested`.
- `PENDING_APPROVAL` to `ACTIVE`: `ACTIVATE` requires an `ACTIVE` or `PROVISIONING` organisation
  in the FSM guard and emits `finaxis.lifecycle.branch.activated`.
- `PENDING_APPROVAL` to `DRAFT`: `RETURN_FOR_CHANGES` (`POST /branches/{branch_id}/return`, reason
  required) is the checker's return or the maker's withdrawal, told apart by the actor; it needs an
  `ACTIVE` or `PROVISIONING` organisation, keeps the branch's code and creator, publishes an
  internal event only, and leaves a draft the maker amends with `PATCH` and resubmits.
- `ACTIVE` to `SUSPENDED`: `SUSPEND` blocks operational use and emits
  `finaxis.lifecycle.branch.suspended`.
- `SUSPENDED` to `ACTIVE`: `REACTIVATE` requires an `ACTIVE` or `PROVISIONING` organisation in
  the FSM guard and emits `finaxis.lifecycle.branch.reactivated`.
- `ACTIVE` or `SUSPENDED` to `CLOSED`: close rejects active assignments unless the caller marks
  assignments handled, rejects active child branches, and emits `finaxis.lifecycle.branch.closed`.
- `CLOSED` to `ARCHIVED`: `ARCHIVE` is declared in the FSM graph and has no configured external
  event.

During organisation deprovisioning, draft and pending branches can also be moved to
`SUSPENDED` through internal FSM transitions so cleanup remains logged instead of becoming an
unattributed bulk update.

A platform administrator can create a tenant's branch draft
(`POST /platform/tenants/{tenant_id}/branches`) with the platform permission alone. It can also
submit and activate a tenant's branch as the audited checker, but only **while the tenant has no
ACTIVE branch other than the one the bootstrap created** (409 `lifecycle.platform_checker_closed`
otherwise); the creator, and a platform administrator that submitted the branch, still cannot
activate it (approval takes `branch.approve`; `branch.activate` is deprecated). Submission and
activation by the platform also write a `branch.submit_as_platform_checker` and a
`branch.activate_as_platform_checker` audit row respectively. It can also return a pending branch as
the checker, under the same bound, and withdraw its own pending branch without one
(`branch.return_for_changes_as_platform_checker` and `branch.withdraw` rows; see
[foundation API](../api/foundation-api.md#return-or-withdraw-a-pending-branch)). See
[ADR 0028](../adr/0028-platform-checker-for-first-tenant-approvals.md).

A `DRAFT` or `ACTIVE` branch can be edited in place (`PATCH /branches/{branch_id}`, permission
`branch.update`, which `branch.create` alone does not give): name, parent, timezone and address,
never the code or type. It writes `branch.update` audit with the changed field
names only, and refuses a parent that would make the branch its own ancestor or that is `CLOSED`
or `ARCHIVED`. The last rule is not enforced by create, submit or activate (a known limitation, see
[lifecycle FSMs](../architecture/lifecycle-fsm.md)).

Draft creation writes `branch.create_draft` audit. Every branch FSM transition persists a
transition log, publishes its configured transition event, and records an audit event through
`FoundationLifecycleService`.

## Assignment model

User-to-branch assignments use `user_branch_assignment` and these assignment types:

- `HOME`;
- `OPERATE`;
- `APPROVE`;
- `VIEW`.

Assignment is organisation-boundary checked by both application code and foreign keys. The
implemented `assignUser` guard requires:

- the user account exists;
- the organisation is `ACTIVE`;
- the branch is `ACTIVE` in that organisation;
- the user has a membership in that organisation;
- the membership is not `REVOKED`.

Duplicate active assignments are idempotent. New assignments write `branch.assign_user` audit and
publish `finaxis.lifecycle.branch.user-assigned`.

Revocation is also idempotent for absent assignments. For normal memberships, revocation cannot
leave the user with zero active branch assignments. `SYSTEM` and `AUDITOR` memberships are exempt
from that retain-at-least-one guard. Successful revocation writes `branch.revoke_user` audit and
publishes `finaxis.lifecycle.branch.user-revoked`.

## Assignment flow

```mermaid
flowchart TD
    A[Assign user to branch] --> B{User exists?}
    B -- no --> X[Reject]
    B -- yes --> C{Organisation ACTIVE?}
    C -- no --> X
    C -- yes --> D{Branch ACTIVE in organisation?}
    D -- no --> X
    D -- yes --> E{Membership exists and not REVOKED?}
    E -- no --> X
    E -- yes --> F[Create or reuse active assignment]
    F --> G[Audit branch.assign_user]
    G --> H[Publish branch user-assigned event]
```

Before closing a branch, move or revoke active assignments under the membership guard and close or
re-parent active child branches. Closing retains the branch row and historical assignment records.
