-- Issue #128: restate what posting_request.request_fingerprint is a digest of.
--
-- One statement, no schema change. It exists because the operator reads the database, and the
-- database was saying the opposite of what the application does.
--
-- ---------------------------------------------------------------------------------------------
-- This migration supersedes a comment in V7 that cannot be edited
-- ---------------------------------------------------------------------------------------------
--
-- V7__accounting_journal_schema.sql comments request_fingerprint as a digest of "source triple,
-- event, dates, currency and legs". The legs were never part of it once ADR 0023 settled the
-- composition: the source reference is claimed before a rule-resolved posting's legs are asked
-- for, so nothing the digest covers may depend on resolving them. PostingFingerprint hashes the
-- caller's asserted inputs only, and docs/database/accounting-erd.md says so twice ("never the
-- resolved legs"). V7 is frozen and forward-only, so that comment cannot be corrected where it was
-- written; COMMENT ON replaces it here. docs/database/accounting-erd.md and ADR 0023, not V7, are
-- the authority on the fingerprint's composition.
COMMENT ON COLUMN posting_request.request_fingerprint IS
    'SHA-256, lower-case hex, of the caller''s asserted inputs (ADR 0023): source triple and '
    'idempotency key, event, entry type, branch, correction and reversal lineage, the three '
    'dates, functional currency, product class and the financial facts with their position '
    'references. Never the resolved legs and never the raw payload, so a retry can be told from '
    'a conflicting reuse of the same key before any posting rule runs. Supersedes the V7 comment, '
    'which wrongly listed the legs.';
