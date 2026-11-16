# Role Update Validation (#340) — Design

Short design, approved 2026-11-16. Closes #340 (the frontend's BG-34).

## Intent

A role edit can't leave a role with a blank name, and removing a role's description is a deliberate
act, never a side effect of sending an empty string.

## Today

`PATCH /api/v1/tenant/roles/{role_id}` binds `UpdateRoleRequest(role_name: String?, description:
String?)` with no constraint, and `JooqIamAdministrationPersistence.updateRole` sets every non-null
field. So `"role_name": "   "` stores a blank name, `"description": ""` overwrites the description,
and `null` keeps either field: there is no way to remove a description.

## Approaches

1. **Absent keeps, `null` clears, blank refused** (chosen). The convention of the other edit
   endpoints: the tenant's `legal_name` and `registration_number`, the branch's
   `parent_branch_id` and the membership's `primary_branch_id` are `Optional<T>?` (absent is Kotlin
   `null`, an explicit JSON `null` is `Optional.empty()`). It changes what `null` means for
   `description`; the only caller, the admin frontend, sends `null` for a blank description field,
   where clearing is what the user meant.
2. Null keeps, blank clears. Backward compatible, but a different convention from every other edit
   endpoint.
3. Null keeps, blank refused. Smallest, but leaves no way to remove a description.

## Design

- `role_name`: absent or `null` keeps it; a value must contain a non-whitespace character, or the
  request is 400 `validation_failed` (the `[\s\S]*\S[\s\S]*` pattern `UpdateBranchRequest` already
  uses, moved to one shared constant). `RoleManagementService.updateTenantRole` refuses a blank name
  too (422 `invalid_operation`), as `createTenantRole` does, for any caller that bypasses the web
  layer.
- `description`: `Optional<String>?`. Absent keeps it; an explicit `null` clears it (stored `NULL`);
  a string sets it; a blank string is 400 `validation_failed`, checked in the controller because Bean
  Validation can't see inside the `Optional` (the tenant controller's `requireAtMost` precedent).
- The command and the persistence port carry `changesDescription: Boolean` next to `description`,
  as tenant amend carries `changesLegalName`.
- Unchanged: the `role.update` audit row, the 409 for a system or archived role, create.

## Non-goals

No length cap on either field (create has none, and the column is `TEXT`); no change to role
create; no frontend change (BG-34 closes when the frontend adopts it).
