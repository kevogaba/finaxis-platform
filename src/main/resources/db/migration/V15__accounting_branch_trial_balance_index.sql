-- Issue #49: the branch trial-balance index, the one index the read models need and the ledger
-- does not already carry.
--
-- Transcribed from docs/database/accounting-erd.md, which names it under "Indexing" as deferred to
-- this issue, with the justifying query. If this file and that document disagree, this file is
-- wrong.
--
-- Nothing else here: issue #49 builds read models over structures that already exist. The one
-- projection Phase E adds arrived in V14, and the ERD's rule holds - an index that serves a query a
-- later issue introduces is specified in that document but created by the issue that introduces the
-- query, each with an EXPLAIN plan in its pull request.

-- Q4, the trial balance by date, period or branch. The key is tenant first (INV-8, INV-15), then
-- the branch the report is scoped to, then the date range it covers, and finally the account it
-- groups by. `direction` and `functional_amount` are payload rather than selectivity, so they ride
-- in INCLUDE: that keeps the B-tree key narrow while still letting the aggregate be index-only.
--
-- V7's idx_journal_line_account_date cannot serve this. It leads with gl_account_id, so a
-- branch-scoped report over every account would have to probe it once per account in the chart or
-- fall back to a scan; this one walks a single range per branch and date window.
CREATE INDEX idx_journal_line_branch_account_date
    ON journal_line (organisation_id, branch_id, posting_date, gl_account_id)
    INCLUDE (direction, functional_amount);

COMMENT ON INDEX idx_journal_line_branch_account_date IS
    'Q4, the branch trial balance: one tenant, one branch, a date range, grouped by account. '
    'Complements idx_journal_line_account_date, which leads with the account and answers the '
    'single-account ledger instead.';
