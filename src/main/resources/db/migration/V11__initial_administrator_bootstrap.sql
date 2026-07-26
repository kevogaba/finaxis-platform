CREATE TABLE organisation_initial_administrator_bootstrap (
    organisation_id UUID PRIMARY KEY REFERENCES organisation (id) ON DELETE CASCADE,
    admin_email VARCHAR(255) NOT NULL,
    admin_username VARCHAR(100) NOT NULL,
    admin_display_name VARCHAR(255) NOT NULL,
    admin_phone_e164 VARCHAR(30),
    send_application_invite BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    requested_by UUID NOT NULL,
    submitted_by UUID,
    approved_by UUID,
    user_id UUID REFERENCES user_account (id) ON DELETE SET NULL,
    membership_id UUID REFERENCES user_organisation_membership (id) ON DELETE SET NULL,
    head_office_id UUID REFERENCES branch (id) ON DELETE SET NULL,
    role_id UUID REFERENCES role (id) ON DELETE SET NULL,
    last_failure_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    submitted_at TIMESTAMPTZ,
    approved_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_bootstrap_status CHECK (
        status IN (
            'DRAFT', 'PENDING_ACTIVATION', 'QUEUED', 'PROVISIONING_IDENTITY', 'COMPLETED', 'FAILED'
        )
    ),
    CONSTRAINT chk_bootstrap_version CHECK (row_version >= 0)
);

CREATE INDEX idx_bootstrap_organisation ON organisation_initial_administrator_bootstrap (organisation_id);

COMMENT ON TABLE organisation_initial_administrator_bootstrap IS 'Maker-checker flow details and asynchronous bootstrap status for the initial administrator user.';
