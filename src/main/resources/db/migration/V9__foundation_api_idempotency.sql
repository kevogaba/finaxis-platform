CREATE TABLE api_idempotency_record (
    scope_organisation_id UUID NOT NULL,
    idempotency_key UUID NOT NULL,
    actor_fingerprint VARCHAR(128) NOT NULL,
    request_method VARCHAR(16) NOT NULL,
    normalized_path VARCHAR(2048) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    response_status INTEGER,
    response_headers JSONB,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_api_idempotency_record
        PRIMARY KEY (scope_organisation_id, idempotency_key),
    CONSTRAINT chk_api_idempotency_record_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_api_idempotency_record_cleanup
    ON api_idempotency_record (expires_at)
    WHERE status = 'COMPLETED';
