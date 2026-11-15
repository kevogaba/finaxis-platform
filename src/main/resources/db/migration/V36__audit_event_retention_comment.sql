-- Issue #278: the audit retention job (ADR 0042, amending ADR 0007).
--
-- WHAT. One COMMENT ON TABLE, no schema and no data change, as V16 restated a column. V1 calls
-- audit_event an "append-only" trail and ADR 0007 says no code path issues UPDATE or DELETE on
-- it. Both are now one exception short: the retention job deletes, through one adapter
-- (JooqAuditRetentionStore, held to that by AuditRetentionDeletePathTests), the rows of a tenant
-- that stores audit_retention_days which are older than that many days (never fewer than 2555),
-- INFO in severity and SUCCESS in outcome, and not an amendment the maker-checker rules still
-- read (#255). Nothing updates a row, and nothing else deletes one. The job is OFF unless the
-- deployment turns it on (finaxis.audit.retention.mode).
--
-- The application still connects as the table owner, so this remains a rule of the code rather
-- than a privilege; ADR 0042 records that and leaves a separate deletion role as later work.

COMMENT ON TABLE audit_event IS
    'Append-only business and security audit trail; never store credentials, tokens, or session '
    'cookies. The only delete is the retention job (ADR 0042): INFO/SUCCESS rows older than the '
    'tenant''s audit_retention_days, never amendments the maker-checker rules still read.';
