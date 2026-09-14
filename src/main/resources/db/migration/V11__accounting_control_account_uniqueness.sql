-- Issue #91: one control account per subsidiary-ledger class per tenant.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging.
--
-- SubledgerProofQuery names a tenant, a branch, a control class, a date and a currency - never an
-- account - so a provider asked about SAVINGS_DEPOSITS answers for the whole class. With two
-- control accounts of that class in one tenant, the same whole-class total is compared against
-- each of them and at least one verdict is silently wrong: a MATCHED that proves nothing, or a
-- BREAK against an account that was never out. Making the class unique per tenant is what gives
-- the question one answer. The alternative - an account or partition key on the port - stays
-- deliberately unbuilt until a product module needs it, because a port is easier to widen than to
-- narrow.
--
-- V9's idx_gl_account_control covered exactly (organisation_id, control_subledger_kind) WHERE
-- is_control_account, so the unique index below serves every lookup it served and the non-unique
-- one is dropped rather than left as a duplicate write cost. No migration through V10 seeds a
-- control account, so no existing row can violate this.
--
-- The index is partial on is_control_account and carries no status predicate, which is what keeps
-- the invariant total: a deactivated control account still answers to its class, because a proof of
-- a date on which it was live is legitimate and would otherwise be compared against whichever
-- account replaced it.
--
-- A tenant that classified the wrong account is not stuck, but the way out is narrow and deliberate:
-- ChartOfAccountsService.requireAmendable admits exactly one amendment to an INACTIVE account -
-- releasing its control classification, nothing else changed, and only while nothing has ever
-- posted to it. So the replacement path is deactivate, release, create and approve the replacement.
-- Deactivation is a maker-checker transition with its own HIGH audit event, which is what stops a
-- live control account being de-classified out from under the proofs that depend on it; and the
-- no-history restriction is what stops the release stranding a balance outside its own class, where
-- every later proof would report it as a permanent BREAK against a replacement that was never out.

DROP INDEX idx_gl_account_control;

CREATE UNIQUE INDEX uq_gl_account_control_kind
    ON gl_account (organisation_id, control_subledger_kind)
    WHERE is_control_account;

COMMENT ON INDEX uq_gl_account_control_kind IS
    'One control account per subsidiary-ledger class per tenant, and the lookup for "the control '
    'account of this tenant for this class". Unique because SubledgerProofProvider answers for a '
    'class rather than an account: a second control account of the same class would be compared '
    'against an aggregate that is not its own (INV-14).';
