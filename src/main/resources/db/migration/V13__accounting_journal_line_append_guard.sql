-- Issue #95: the journal-line append guard - a committed journal cannot grow an extra line.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging.
--
-- ---------------------------------------------------------------------------------------------
-- This migration supersedes a sentence in V7 that cannot be edited
-- ---------------------------------------------------------------------------------------------
--
-- V7__accounting_journal_schema.sql says, across lines 8 and 9, "No trigger: the balance invariant
-- across a journal's lines is a cross-row property that a CHECK cannot see". The reasoning holds -
-- a CHECK still cannot see across rows - but the conclusion no longer does: there is now exactly
-- one trigger on journal_line, created below. V7 is frozen and forward-only, so that sentence
-- cannot be corrected where it is written and a reader who stops there will be misled. Superseded
-- here. docs/database/accounting-erd.md, not V7's header, is the current authority on whether the
-- journal tables carry a trigger, and it describes this one.
--
-- ---------------------------------------------------------------------------------------------
-- What this closes, and what issue #54 closes
-- ---------------------------------------------------------------------------------------------
--
-- Issue #54 revokes UPDATE and DELETE on journal_entry and journal_line from the application role.
-- That stops a committed journal being altered or removed. It does not stop an INSERT. A later
-- transaction can append a third journal_line to a committed two-line journal: the journal's
-- balance and its line count both change, and not one protected row was touched.
--
-- Nothing in the schema saw that before this migration. PostingEngine.verifyHeaderAgainstLines
-- re-reads the lines and compares them to the header, but it runs inside the transaction that
-- creates the journal and returns before the append exists. The header-versus-lines proof query
-- finds such an append afterwards, which is detection, not prevention.
--
-- journal_entry.line_count already declares how many lines the journal has. An AFTER INSERT
-- statement-level trigger with a NEW TABLE hands the statement's own rows to one set-based query,
-- which counts what each touched journal now holds and refuses the statement when any journal
-- holds MORE lines than its header declares. Appending line 3 to a journal whose header says 2
-- makes 3 > 2 and is refused.
--
-- The rule is "no more than declared" rather than "exactly as declared" because a journal is
-- legitimately written a line at a time. PostingEngine.writeJournal inserts the header, inserts
-- every line in one multi-row statement, re-reads them and marks the request POSTED inside one
-- transaction, so for the engine the two forms coincide; but every schema fixture in this
-- repository - JournalSchemaFixture - writes the header and then each line as its own statement,
-- and an under-count is the normal intermediate state of a journal being built. Refusing it would
-- fail those fixtures while catching nothing the strict form catches later anyway: the extra line
-- still trips the guard when it arrives. (AccountingQueryPlanTests' 200,000-line seed is the
-- opposite shape - one statement for every line, against headers declaring four - and would pass
-- either form.)
--
-- ---------------------------------------------------------------------------------------------
-- Three limits, stated rather than glossed
-- ---------------------------------------------------------------------------------------------
--
-- (a) line_count is a plain mutable column on journal_entry. The same role that can INSERT a
--     journal_line can first UPDATE journal_entry SET line_count = 3 and then append the third
--     line, and the guard will pass it. So this is NOT an append guard that holds independently of
--     issue #54. What it closes is the append an actor holding only INSERT can perform - which is
--     nobody today, precisely because no REVOKE has landed and every actor still holds UPDATE too.
--     Issue #54 closes the rest.
--
--     And #54 needs more than a REVOKE to do it. PostgreSQL does not consult privileges for a
--     table's owner, and a superuser bypasses them outright, so revoking UPDATE from a role that
--     owns journal_entry changes nothing: #54 must also move the application off the owning role.
--     Until it does, the same connection can ALTER TABLE journal_line DISABLE TRIGGER and undo
--     this guard as easily as it could UPDATE the count - which is why nothing here is described
--     as making the committed journal immutable.
--
-- (b) The count is evaluated once per statement with no lock taken on the header, so under READ
--     COMMITTED two concurrent transactions that each append to the same UNDER-COUNT journal - one
--     committed holding fewer lines than its header declares - each see a count that satisfies
--     line_count and both commit, leaving the journal over its declared count. Production cannot
--     reach this: writeJournal's verification read and markPosted share a transaction, so a
--     journal whose committed line count is below its header is never produced in the first place.
--     Closing it properly would need SELECT ... FOR UPDATE on the header, which serialises every
--     posting in a tenant behind its own header row for no invariant production can violate. The
--     residual is recorded, not fixed.
--
-- (d) The guard fires on INSERT only. UPDATE journal_line SET journal_entry_id = <another journal>
--     moves a line between journals and trips nothing, and DELETE removes one silently. Both are
--     squarely what issue #54's REVOKE UPDATE, DELETE is for, and neither is an append, but a
--     reader taking "a committed journal cannot grow a line" as unqualified should know where the
--     sentence stops.
--
-- (c) The guard says nothing about a NEW journal_entry. A later transaction may insert a fresh
--     header and its lines, and must be able to - a reversal is exactly that (ADR 0020: correction
--     is a new REVERSAL entry, never a change to an existing one). So issue #95 item 1 makes an
--     existing journal unable to grow; it does not make the ledger physically immutable, and the
--     documentation must not claim that it does.
--
-- ---------------------------------------------------------------------------------------------
-- The four implementation choices, and why each went the way it did
-- ---------------------------------------------------------------------------------------------
--
-- Schema: public, alongside the tables. V6 installed btree_gist into a dedicated extensions schema
-- because jOOQ reads inputSchema = "public" with no excludes and would generate the extension's
-- ~160 functions into com.finaxis.platform.jooq on every build. A trigger function does not have
-- that problem: jOOQ's includeTriggerRoutines flag defaults to false (jooq-codegen-3.21.1.xsd,
-- "whether trigger implementation routines should be included ... (e.g. in PostgreSQL)"), and
-- PostgresDatabase applies it by excluding pg_proc rows whose prorettype is trigger. A function
-- that RETURNS trigger is therefore invisible to codegen wherever it lives, so it lives next to
-- the table it guards rather than in a schema that would have to be on the deploy role's path.
-- This is also why the guard is a trigger function and not the SECURITY DEFINER helper the issue
-- first proposed: a helper RETURNS void would be generated.
--
-- SECURITY INVOKER, written out rather than left to the default. The function only reads two
-- tables the caller has just written to, so DEFINER would confer nothing it does not already have.
-- It would also be inert today for a second reason: the application connects as the initdb
-- bootstrap superuser, which owns every table, so a DEFINER function's owner and its caller are
-- the same role. A privilege boundary that starts working only after issue #54 is not a privilege
-- boundary; INVOKER is the honest declaration, and the guard does not depend on privileges at all.
--
-- search_path pinned to pg_catalog, public, pg_temp. PostgreSQL searches the temporary schema
-- before everything else for relations unless pg_temp is named explicitly, so without this a
-- caller could CREATE TEMP TABLE journal_line and have the guard count an empty decoy while the
-- real append proceeded. Naming pg_temp last moves it out of that implicit first position, and
-- pg_catalog first fixes count() and format() as well. The NEW TABLE is resolved by the executor,
-- not through search_path, so pinning it does not affect the transition table.
--
-- ERRCODE check_violation (SQLSTATE 23514), not the plpgsql default raise_exception (P0001).
-- This is a check that a row-level CHECK cannot express, and it belongs in the same class as the
-- CHECK constraints it sits beside. Spring maps SQLSTATE class 23 to
-- DataIntegrityViolationException and P0001 to an UncategorizedSQLException, so the code puts an
-- engine defect in the category the rest of this schema's violations land in. Nothing translates it
-- into an API error on purpose: no endpoint inserts a journal line, so tripping the guard is an
-- engine or store defect rather than a caller mistake - the same category as
-- verifyHeaderAgainstLines' own check, which is an IllegalStateException and a 500.
--
-- ---------------------------------------------------------------------------------------------
-- The ledger is proven clean before the guard is installed over it
-- ---------------------------------------------------------------------------------------------
--
-- CREATE TRIGGER does not evaluate the rows already in the table. Measured on postgres:18.4: a
-- journal declaring line_count 2 while holding 3 lines accepts the trigger without complaint, and
-- the violation then sits underneath a guard that says it cannot happen. That is the failure this
-- migration exists to prevent, arriving one day early - and a guard installed over a ledger that
-- already breaks it is exactly the "claims more than it closes" problem the limits above are
-- written to avoid.
--
-- So the ledger is checked first, and the migration refuses to install the guard over a ledger
-- that already violates it. The predicate is the guard's own, present > line_count, so the two
-- cannot disagree about what a violation is: an under-count journal - the normal intermediate
-- state of a journal being built - is not one, and does not block the deploy.
--
-- This fails the deployment rather than warning, deliberately. The alternative is to install the
-- guard, report nothing, and let INV-5 assert about a ledger nobody verified; on a general ledger
-- that is the worse outcome. The remedy is not automated here on purpose: reconciling a journal
-- whose lines disagree with its header is an accounting decision - which row is wrong, and what a
-- correcting reversal should say - not something a migration may guess at. The message names the
-- count and the first offender so the investigation has somewhere to start.
--
-- Production cannot have produced such a journal: insertJournalLines has exactly one caller,
-- PostingEngine.writeJournal, which writes exactly line_count lines, re-reads them through
-- verifyHeaderAgainstLines and only then marks the request POSTED, all in one transaction. This
-- check is therefore expected to pass everywhere, every time. It is cheap insurance against the
-- one case that would make the guard a lie, and on a fresh database it reads an empty table.

DO $$
DECLARE
    offending_entry UUID;
    declared_count INTEGER;
    present_count BIGINT;
    offending_total BIGINT;
BEGIN
    -- count(*) OVER () is evaluated across every matching row before LIMIT applies, so the total
    -- is the whole answer while only one offender is carried into the message.
    SELECT count(*) OVER (), counted.journal_entry_id, entry.line_count, counted.present
      INTO offending_total, offending_entry, declared_count, present_count
    FROM (
        SELECT line.organisation_id, line.journal_entry_id, count(*) AS present
        FROM journal_line line
        GROUP BY line.organisation_id, line.journal_entry_id
    ) counted
    JOIN journal_entry entry
      ON entry.organisation_id = counted.organisation_id
     AND entry.id = counted.journal_entry_id
    WHERE counted.present > entry.line_count
    ORDER BY counted.journal_entry_id
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            '% journal(s) already hold more lines than their header declares; the first is '
            'journal_entry %, declaring line_count % while holding %'
            , offending_total, offending_entry, declared_count, present_count
        USING ERRCODE = 'check_violation',
              HINT = 'V13 does not install a guard over a ledger that already violates it. '
                     'Reconcile the named journals first; a correcting reversal is an accounting '
                     'decision, so this migration deliberately does not repair them for you.';
    END IF;
END;
$$;

CREATE FUNCTION fn_journal_line_append_guard() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY INVOKER
    SET search_path = pg_catalog, public, pg_temp
AS $$
DECLARE
    offending_entry UUID;
    declared_count INTEGER;
    present_count BIGINT;
BEGIN
    -- One set-based pass over the statement's own rows: count what each journal it touched now
    -- holds, and stop at the first journal holding more than its header declares. LIMIT 1 because
    -- the statement is refused whole - naming one offender is enough to diagnose it, and finding
    -- the rest costs a full aggregate over every journal the statement touched. No ORDER BY: when
    -- several journals overshoot at once, which one the message names is arbitrary, and that is
    -- accepted rather than paid for.
    SELECT counted.journal_entry_id, entry.line_count, counted.present
      INTO offending_entry, declared_count, present_count
    FROM (
        SELECT line.organisation_id, line.journal_entry_id, count(*) AS present
        FROM journal_line line
        WHERE (line.organisation_id, line.journal_entry_id) IN (
            SELECT touched.organisation_id, touched.journal_entry_id FROM inserted touched
        )
        GROUP BY line.organisation_id, line.journal_entry_id
    ) counted
    JOIN journal_entry entry
      ON entry.organisation_id = counted.organisation_id
     AND entry.id = counted.journal_entry_id
    WHERE counted.present > entry.line_count
    LIMIT 1;

    IF FOUND THEN
        RAISE EXCEPTION
            'journal_entry % declares line_count % but now holds % journal_line rows; a '
            'committed journal cannot grow a line'
            , offending_entry, declared_count, present_count
        USING ERRCODE = 'check_violation',
              HINT = 'Correction is a new REVERSAL entry, never an append to an existing journal.';
    END IF;

    -- AFTER STATEMENT: the return value is ignored, and NULL is the convention for saying so.
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_journal_line_append_guard
    AFTER INSERT ON journal_line
    REFERENCING NEW TABLE AS inserted
    FOR EACH STATEMENT
    EXECUTE FUNCTION fn_journal_line_append_guard();

COMMENT ON FUNCTION fn_journal_line_append_guard() IS
    'Refuses an INSERT that would leave any journal holding more journal_line rows than its '
    'header''s line_count declares. Statement-level and set-based: it reads the statement''s NEW '
    'TABLE once, never once per row. Raises check_violation (23514) so the failure lands in the '
    'same class as the CHECK constraints beside it; Spring renders that as '
    'DataIntegrityViolationException. SECURITY INVOKER, and search_path pinned so a temporary '
    'table cannot shadow journal_line and be counted in its place.';
COMMENT ON TRIGGER trg_journal_line_append_guard ON journal_line IS
    'The only trigger in this schema, and the prevention half of journal immutability that issue '
    '#54''s REVOKE UPDATE, DELETE cannot cover: a revoke stops a committed journal being changed '
    'or removed, not a later transaction appending a line to it. Bounded deliberately - it does '
    'not stop a NEW journal_entry being written, because a reversal is exactly that, and it is '
    'airtight against an actor who can also UPDATE line_count only once #54 has landed.';
