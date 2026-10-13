-- Issue #186: the typed subject user of an audit event, the column behind the "everything about
-- this user" query. V29 adds the column and V30 builds its partial index (the index is built
-- CONCURRENTLY, so it is kept apart from this transactional change; see V30's header).
--
-- What the column holds: the user an event is ABOUT. The writers in this same change set it at
-- write time from a typed field (AuditCommand.subjectUserId), never parsed out of metadata; an
-- event about no user leaves it NULL. The actor (who did it) stays in actor_user_id; the two differ
-- when an administrator acts on someone else.
--
-- Nullable, with no default and no backfill, by owner decision: a row written before V29 keeps a
-- NULL subject_user_id, and the cut-over is documented in
-- docs/operations/audit-index-migrations.md.
-- Recovering a subject for historic rows from metadata would be a guess, not evidence.
--
-- No foreign key. The neighbouring actor_user_id carries one (V1, fk_audit_event_actor), and this
-- column deliberately does not. An FK here would make an audit write FAIL whenever the subject id
-- does not exist yet, or was created by a transaction that has not committed: a DENIED or FAILURE
-- event about an unknown id could then not be recorded, which is the evidence audit exists for.
-- It would also add a referential check to every audit insert, and, because PostgreSQL does not
-- index referencing columns, an FK would make every delete from user_account scan audit_event. No
-- user-deletion path exists in src/main today, so the actor's FK is not a reason to add one here.
-- Owner ruling: the column holds user_account.id, and the user it names is an identity the
-- identity provider (Keycloak) owns and the application only mirrors, so it is a plain reference
-- column, not a foreign key; V30 indexes it only where it is not NULL.
--
-- Transactional and metadata-only: ADD COLUMN of a nullable column without a default rewrites no
-- rows and changes no data. No trigger (ADR 0024 does not apply).
--
-- Lock wait, bounded. ADD COLUMN takes ACCESS EXCLUSIVE on audit_event. That request queues behind
-- any open transaction that touches audit_event, and every statement that arrives while it waits
-- queues behind it: an unbounded wait would stall every audit write of the running instances (a
-- rolling deploy runs Flyway before the new instances take traffic, while old instances still
-- write audit rows). SET LOCAL lock_timeout bounds the wait to five seconds. If the lock is not
-- granted in time, the ALTER fails with SQLSTATE 55P03, the transaction rolls back (this file
-- included), and the statements that were stalled behind it resume at most five seconds later. The
-- operator then retries the deploy; see docs/operations/audit-index-migrations.md
-- ("Subject user cut-over").

SET LOCAL lock_timeout = '5s';

ALTER TABLE audit_event ADD COLUMN subject_user_id UUID NULL;

COMMENT ON COLUMN audit_event.subject_user_id IS
    'The user this event is about, set at write time (#186). NULL on rows written before V29 '
    'and on events about no user. Not a foreign key: audit rows outlive users.';
