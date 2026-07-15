CREATE TABLE reference_sequence (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    sequence_code TEXT NOT NULL,
    next_value BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_reference_sequence_organisation_code UNIQUE (organisation_id, sequence_code),
    CONSTRAINT chk_reference_sequence_next_value CHECK (next_value > 0),
    CONSTRAINT chk_reference_sequence_version CHECK (row_version >= 0)
);

COMMENT ON TABLE reference_sequence IS
    'Organisation-scoped reference counters; records survive metadata-only deprovisioning.';
