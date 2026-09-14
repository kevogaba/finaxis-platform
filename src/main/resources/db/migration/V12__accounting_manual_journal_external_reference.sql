-- Issue #92: the structured external reference a manual journal answers to.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging.
--
-- title and narrative carry the substance of an adjustment and both reach the posted journal, but a
-- document number - a memo reference, a bank advice, an auditor's schedule - had nowhere to live
-- except inside that prose. A reference buried in free text cannot be searched on, reported on or
-- reconciled against the document it names, which is the whole reason for recording it.
--
-- One bounded column rather than a metadata map. A map becomes the place anything goes, and nothing
-- can then be reported on: "which adjustments cite bank advice 4471" needs a column, not a JSON
-- path that may or may not have been populated the same way twice. A second structured field is a
-- second column and a second decision, made in the ERD first.
--
-- No index: nothing reads a draft by document number yet, and every index in this schema earns its
-- place by a query. Issue #52's REST adapter is where a lookup would arrive, and is the change that
-- should add the index together with the query justifying it.
--
-- Nullable, because the column is added to a table that already holds drafts and because plenty of
-- adjustments answer to no external document at all.

ALTER TABLE manual_journal
    ADD COLUMN external_reference TEXT,
    ADD CONSTRAINT chk_manual_journal_external_reference CHECK (
        external_reference IS NULL
        OR (char_length(external_reference) BETWEEN 1 AND 100
            AND external_reference ~ '[^[:space:]]')
    );

COMMENT ON COLUMN manual_journal.external_reference IS
    'The document this adjustment answers to outside the ledger - a memo number, a bank advice, an '
    'auditor''s schedule reference. Structured so it can be searched and reported on rather than '
    'buried in the narrative. Descriptive only; accounting never resolves it.';
COMMENT ON CONSTRAINT chk_manual_journal_external_reference ON manual_journal IS
    'Bounded, and genuinely non-blank: a string of whitespace passes a length check, is '
    'indistinguishable from having supplied nothing, and still occupies a column reports group by. '
    'Written as a regular expression rather than btrim(...) <> '''': btrim strips spaces '
    'only, so a tab or a newline alone would satisfy it while ManualJournalPolicy, which '
    'asks String.isBlank(), refuses it - and the column would hold a value the service says '
    'cannot exist.';
