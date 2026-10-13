-- Issue #186: the partial index behind the audit search's subject_user_id filter (V29 adds the
-- column; V25-V28 add one index each for the #183 filters).
--
-- The query it serves: every event about one user, newest first, within one tenant:
--   WHERE organisation_id = ? AND subject_user_id = ? ORDER BY event_time DESC
-- Without it, that is a pass over the tenant's whole audit log. With it, the page is an index range
-- scan of the matching rows, and "everything about this user" is one query with no duplicates
-- (PR4 made it one row per transition).
--
-- Partial: a row with no subject can never match a subject_user_id filter, so it costs no index
-- entry. That covers every event about no user and every row written before V29, which keeps a NULL
-- subject_user_id (no backfill; the cut-over is in docs/operations/audit-index-migrations.md). The
-- design and the filters left unindexed are in docs/architecture/audit-logging.md
-- ("Search filters").
--
-- Built CONCURRENTLY, by owner ruling: audit_event is written by every audited action, and a plain
-- CREATE INDEX would hold a SHARE lock blocking all of them for the whole build. A concurrent build
-- cannot run in a transaction, so this file is non-transactional (executeInTransaction=false in the
-- V30__audit_event_subject_user_index.sql.conf next to it; each statement commits on its own),
-- builds one index only, and Flyway runs with spring.flyway.postgresql.transactional-lock=false (a
-- session advisory lock: a transactional one would make the concurrent build wait on Flyway's own
-- transaction forever). This is the repository's convention for an index on a big table; it adds no
-- trigger (ADR 0024 is about triggers and does not apply).
--
-- Invalid-index guard. A concurrent build that fails (cancelled, deadlocked, the instance stopped)
-- leaves an INVALID index behind, which IF NOT EXISTS would then skip for good. A DROP INDEX
-- CONCURRENTLY cannot run inside the DO block that detects it, and a plain DROP would take the
-- table lock this file exists to avoid, so the guard refuses instead: it raises, naming the index
-- and the operator step (drop it concurrently, then `flyway repair` and restart; see
-- docs/operations/audit-index-migrations.md). A valid index of this name is kept as it is.

DO $$
DECLARE
    index_name CONSTANT TEXT := 'idx_audit_event_organisation_subject_user_time';
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indexrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = current_schema()
          AND c.relname = index_name
          AND NOT i.indisvalid
    ) THEN
        RAISE EXCEPTION '% exists but is INVALID, left by a failed concurrent build', index_name
            USING HINT = format(
                'Run DROP INDEX CONCURRENTLY %I; then flyway repair and restart.', index_name
            );
    END IF;
END
$$;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_event_organisation_subject_user_time
    ON audit_event (organisation_id, subject_user_id, event_time DESC)
    WHERE subject_user_id IS NOT NULL;

COMMENT ON INDEX idx_audit_event_organisation_subject_user_time IS
    'Audit search by subject_user_id (#186): every event about one user, newest first.';
