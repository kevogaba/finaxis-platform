-- Issue #183: the partial index behind the audit search's actor_subject filter (V25-V28 add one
-- each).
--
-- Without it, counting a page filtered on actor_subject is a pass over the tenant's whole audit
-- log: on a 1,000,000-row tenant the planner chose a parallel sequential scan. With it, the count
-- is an index-only range scan of the matching rows. Partial: a row with no subject (a system actor)
-- can never match an actor_subject filter. The design and the filters left unindexed are in
-- docs/architecture/audit-logging.md ("Search filters").
--
-- Built CONCURRENTLY, by owner ruling: audit_event is written by every audited action, and a plain
-- CREATE INDEX would hold a SHARE lock blocking all of them for the whole build. A concurrent build
-- cannot run in a transaction, so this file is non-transactional (executeInTransaction=false in the
-- V26__audit_event_actor_subject_index.sql.conf next to it; each statement commits on its own),
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
    index_name CONSTANT TEXT := 'idx_audit_event_organisation_subject_time';
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

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_event_organisation_subject_time
    ON audit_event (organisation_id, actor_external_subject, event_time DESC)
    WHERE actor_external_subject IS NOT NULL;

COMMENT ON INDEX idx_audit_event_organisation_subject_time IS
    'Audit search by actor_subject (#183), tenant route only: the platform pages withhold it.';
