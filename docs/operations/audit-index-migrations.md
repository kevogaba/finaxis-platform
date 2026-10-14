# Audit index migrations (V25-V28, V30)

`V25`-`V28` add the four partial indexes behind the audit search filters of #183
(`branch_id`, `actor_subject`, `outcome`, `severity`/`min_severity`). The design is in
[audit architecture](../architecture/audit-logging.md#search-filters-183). `V30` adds the fifth,
the subject user index of #186, and `V29` adds the column it covers. This page covers how they are
built, what an operator does when a build fails, and the cut-over for the subject user.

## How they are built

`audit_event` is written by every audited action, so a plain `CREATE INDEX` would hold a `SHARE`
lock and block all of those writes until the build finished. By owner ruling each index is built
`CONCURRENTLY` instead, which lets inserts continue. It is the repository's convention for an
index on a big, hot table:

- **One index per migration**, `CREATE INDEX CONCURRENTLY IF NOT EXISTS`.
- **Non-transactional.** A concurrent build cannot run inside a transaction, so each migration has
  a `V<N>__<name>.sql.conf` next to it holding `executeInTransaction=false`. Each statement then
  commits on its own. Without that file Flyway refuses the migration, because it mixes a
  transactional `DO` block with a non-transactional build.
- **Session advisory lock.** Flyway runs with `spring.flyway.postgresql.transactional-lock:
  false` (`application.yaml`, and the jOOQ codegen Flyway in `build.gradle.kts`). A concurrent
  build waits for every open transaction to finish, including the transaction that holds
  Flyway's transactional lock, so under that lock it would never finish. The session lock still
  stops two instances migrating at once.
- **Invalid-index guard.** A failed concurrent build leaves an `INVALID` index behind, and
  `IF NOT EXISTS` would then skip it for good. Each migration therefore opens with a `DO` block
  that raises when an `INVALID` index of its name exists. The block cannot drop the index
  concurrently (that statement cannot run inside a block), and a plain `DROP INDEX` would take
  the table lock these migrations exist to avoid. A valid index of the same name is kept.

The build still waits for transactions already running on `audit_event` when it starts, and it
scans the table twice, so on a large log it takes a while. Inserts are not blocked meanwhile.

`AuditIndexMigrationTests` proves all of this on PostgreSQL:
- V25-V28 and V30 succeed with the application's Flyway settings, which also shows that the
  `.sql.conf` files and the session lock work;
- the valid-index case is kept;
- the `INVALID`-index case stops the migration;
- for `V30`, the same two index cases on a scratch database migrated to `V29`.

`V30` is built by the same convention, so its statements are the same: a `DO` guard, then one
`CREATE INDEX CONCURRENTLY IF NOT EXISTS`. Its name is
`idx_audit_event_organisation_subject_user_time`, which is not the `actor_subject` index of `V26`.

None of this is a trigger, so ADR 0024 does not apply.

## When a concurrent build fails

The deploy fails with an error naming the index, for example:

```text
idx_audit_event_organisation_branch_time exists but is INVALID, left by a failed concurrent build
HINT: Run DROP INDEX CONCURRENTLY idx_audit_event_organisation_branch_time; then flyway repair
and restart.
```

The same state can also appear if the instance stopped during the build. Recover like this:

1. Find what is invalid:

   ```sql
   SELECT c.relname
   FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
   WHERE i.indrelid = 'audit_event'::regclass AND NOT i.indisvalid;
   ```

2. Drop each one without locking writes. Run it outside a transaction (psql autocommit):

   ```sql
   DROP INDEX CONCURRENTLY IF EXISTS idx_audit_event_organisation_branch_time;
   ```

3. A non-transactional migration that failed stays recorded as failed in
   `flyway_schema_history`. Run `flyway repair` with the application's datasource, or delete that
   version's failed row, so Flyway retries it.
4. Restart the application. The migration rebuilds the index concurrently.

Search results are never wrong in this state, only slower: an `INVALID` index is never used for
reads, and the queries fall back to `idx_audit_event_organisation_time`.

For `V30`, substitute `idx_audit_event_organisation_subject_user_time` in the steps above. Until
the index is valid again, a subject query falls back to the tenant's time index.

## Subject user cut-over (V29, V30)

`V29` adds `audit_event.subject_user_id`, the user an event is about, and `V30` indexes it. Neither
is backfilled, by owner decision:

- A row written before `V29` keeps a `NULL` `subject_user_id`, and so does any event about no user.
  Recovering a subject for those rows from `metadata_jsonb` would be a guess, not evidence.
- A subject search on `subject_user_id` finds only events whose writer set the column, so it never
  finds a pre-`V29` row. For a user's history before the release, use the actor filter (who did
  it), the entity filters (`entity_type`, `entity_id`) or the lifecycle tables.
- The writers set the column at write time from a typed field on the audit command, as a contract:
  it is never parsed from `metadata_jsonb`.

`V29` takes a short `ACCESS EXCLUSIVE` lock on `audit_event` and gives up after five seconds
(`lock_timeout`), so the deploy never stalls audit writes behind it for longer than that. If the
deploy fails with SQLSTATE `55P03` on `V29`, the migration rolled back and Flyway recorded nothing
(verified by `AuditIndexMigrationTests`): retry the deploy, and do not run `flyway repair`.

Each migration runs in its own transaction. `V30` runs after `V29` has committed, so its concurrent
build starts only once the column exists. Apart from the invalid-index recovery above, nothing in
the cut-over needs an operator step.
