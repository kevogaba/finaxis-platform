-- Issue #127: a draft posting-rule version can be withdrawn, so it never blocks its rule for good.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. If this file
-- and that document disagree, this file is wrong.
--
-- Only a version's author may amend or submit it, and a rule admits one draft or pending version
-- at a time. Without a way out, a draft whose author left the tenant would freeze its rule: no one
-- could finish it and no one could start the next one. CANCELLED is that way out - terminal, never
-- approved, and so outside ex_posting_rule_version_no_overlap and invisible to the resolver.
--
-- One statement pair and no data change: every existing row already satisfies the wider CHECK.
ALTER TABLE posting_rule_version
    DROP CONSTRAINT chk_posting_rule_version_status,
    ADD CONSTRAINT chk_posting_rule_version_status CHECK (
        status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'RETIRED', 'CANCELLED')
    );

COMMENT ON COLUMN posting_rule_version.status IS
    'DRAFT, PENDING_APPROVAL, ACTIVE, SUPERSEDED, RETIRED or CANCELLED. Only ACTIVE, SUPERSEDED and '
    'RETIRED are approved and resolve postings; CANCELLED is a withdrawn draft that never did.';
