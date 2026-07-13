CREATE TABLE organisation (
    id UUID PRIMARY KEY,
    tenant_code VARCHAR(100) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    legal_name VARCHAR(255),
    registration_number VARCHAR(100),
    country_code CHAR(2) NOT NULL,
    base_currency_code CHAR(3) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    activated_at TIMESTAMPTZ,
    suspended_at TIMESTAMPTZ,
    deprovisioned_at TIMESTAMPTZ,
    status_reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_organisation_tenant_code UNIQUE (tenant_code),
    CONSTRAINT chk_organisation_status CHECK (
        status IN (
            'DRAFT', 'PENDING_APPROVAL', 'PROVISIONING', 'ACTIVE', 'SUSPENDED',
            'DEPROVISIONING', 'DEPROVISIONED', 'REJECTED', 'ARCHIVED'
        )
    ),
    CONSTRAINT chk_organisation_currency CHECK (base_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_organisation_country CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_organisation_version CHECK (row_version >= 0)
);

COMMENT ON TABLE organisation IS 'Top-level organisation and isolation boundary for all scoped data.';
COMMENT ON COLUMN organisation.tenant_code IS 'Stable external organisation code; retained for tenancy integration compatibility.';

CREATE TABLE user_account (
    id UUID PRIMARY KEY,
    username TEXT NOT NULL,
    email TEXT NOT NULL,
    phone_e164 TEXT,
    display_name TEXT NOT NULL,
    status TEXT NOT NULL,
    preferred_locale TEXT,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_user_account_status CHECK (
        status IN (
            'DRAFT', 'PENDING_APPROVAL', 'PROVISIONING_IDP', 'INVITED', 'ACTIVE',
            'SUSPENDED', 'LOCKED', 'DEACTIVATING', 'DEACTIVATED', 'ARCHIVED'
        )
    ),
    CONSTRAINT chk_user_account_version CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX uq_user_account_lower_email ON user_account (LOWER(email));
CREATE UNIQUE INDEX uq_user_account_lower_username ON user_account (LOWER(username));
COMMENT ON TABLE user_account IS 'Global platform user; organisation access is represented only by memberships.';

CREATE TABLE keycloak_identity_link (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES user_account (id),
    provider TEXT NOT NULL DEFAULT 'KEYCLOAK',
    subject TEXT NOT NULL,
    realm_name TEXT,
    linked_at TIMESTAMPTZ NOT NULL,
    unlinked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_keycloak_identity_provider_subject UNIQUE (provider, subject),
    CONSTRAINT chk_keycloak_identity_provider CHECK (provider = 'KEYCLOAK'),
    CONSTRAINT chk_keycloak_identity_version CHECK (row_version >= 0)
);

CREATE INDEX idx_keycloak_identity_user ON keycloak_identity_link (user_id);
COMMENT ON TABLE keycloak_identity_link IS 'External identity-provider binding; Keycloak remains authentication only.';

CREATE TABLE branch (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    branch_code TEXT NOT NULL,
    branch_name TEXT NOT NULL,
    branch_type TEXT NOT NULL,
    parent_branch_id UUID,
    status TEXT NOT NULL,
    timezone TEXT NOT NULL,
    address_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    opened_on DATE,
    closed_on DATE,
    status_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_branch_organisation_code UNIQUE (organisation_id, branch_code),
    CONSTRAINT uq_branch_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT fk_branch_parent_same_organisation
        FOREIGN KEY (organisation_id, parent_branch_id)
        REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_branch_status CHECK (
        status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'CLOSED', 'ARCHIVED')
    ),
    CONSTRAINT chk_branch_dates CHECK (closed_on IS NULL OR opened_on IS NULL OR closed_on >= opened_on),
    CONSTRAINT chk_branch_version CHECK (row_version >= 0)
);

CREATE INDEX idx_branch_organisation_status ON branch (organisation_id, status);
CREATE INDEX idx_branch_parent ON branch (organisation_id, parent_branch_id);
COMMENT ON TABLE branch IS 'Organisation-scoped operating location. Parent relationships cannot cross organisations.';

CREATE TABLE user_organisation_membership (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    user_id UUID NOT NULL REFERENCES user_account (id),
    membership_status TEXT NOT NULL,
    membership_type TEXT NOT NULL,
    primary_branch_id UUID,
    joined_at TIMESTAMPTZ,
    suspended_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    status_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_membership_organisation_user UNIQUE (organisation_id, user_id),
    CONSTRAINT uq_membership_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT fk_membership_primary_branch_same_organisation
        FOREIGN KEY (organisation_id, primary_branch_id)
        REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_membership_status CHECK (
        membership_status IN ('PENDING_APPROVAL', 'ACTIVE', 'SUSPENDED', 'REVOKED')
    ),
    CONSTRAINT chk_membership_type CHECK (
        membership_type IN ('STAFF', 'ADMIN', 'AUDITOR', 'SYSTEM')
    ),
    CONSTRAINT chk_membership_version CHECK (row_version >= 0)
);

CREATE INDEX idx_membership_organisation_status
    ON user_organisation_membership (organisation_id, membership_status);
CREATE INDEX idx_membership_organisation_user
    ON user_organisation_membership (organisation_id, user_id);
CREATE INDEX idx_membership_user ON user_organisation_membership (user_id);

CREATE TABLE user_branch_assignment (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    assignment_type TEXT NOT NULL,
    status TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    assigned_by UUID,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_branch_assignment_membership
        FOREIGN KEY (organisation_id, user_id)
        REFERENCES user_organisation_membership (organisation_id, user_id),
    CONSTRAINT fk_user_branch_assignment_branch
        FOREIGN KEY (organisation_id, branch_id)
        REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_user_branch_assignment_type CHECK (
        assignment_type IN ('HOME', 'OPERATE', 'APPROVE', 'VIEW')
    ),
    CONSTRAINT chk_user_branch_assignment_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    CONSTRAINT chk_user_branch_assignment_version CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX uq_active_user_branch_assignment
    ON user_branch_assignment (organisation_id, user_id, branch_id, assignment_type)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_user_branch_assignment_organisation_status
    ON user_branch_assignment (organisation_id, status);
CREATE INDEX idx_user_branch_assignment_organisation_user
    ON user_branch_assignment (organisation_id, user_id);
CREATE INDEX idx_user_branch_assignment_organisation_branch
    ON user_branch_assignment (organisation_id, branch_id);

CREATE TABLE permission (
    id UUID PRIMARY KEY,
    permission_code TEXT NOT NULL,
    permission_name TEXT NOT NULL,
    module_code TEXT NOT NULL,
    description TEXT,
    risk_level TEXT NOT NULL,
    system_permission BOOLEAN NOT NULL DEFAULT TRUE,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_permission_code UNIQUE (permission_code),
    CONSTRAINT chk_permission_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_permission_status CHECK (status IN ('ACTIVE', 'DEPRECATED', 'DISABLED')),
    CONSTRAINT chk_permission_version CHECK (row_version >= 0)
);

CREATE TABLE role (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    role_code TEXT NOT NULL,
    role_name TEXT NOT NULL,
    description TEXT,
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_role_organisation_code UNIQUE (organisation_id, role_code),
    CONSTRAINT uq_role_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT chk_role_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT chk_role_version CHECK (row_version >= 0)
);

CREATE INDEX idx_role_organisation_status ON role (organisation_id, status);

CREATE TABLE role_permission (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL REFERENCES permission (id),
    granted_at TIMESTAMPTZ NOT NULL,
    granted_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_role_permission_role
        FOREIGN KEY (organisation_id, role_id) REFERENCES role (organisation_id, id),
    CONSTRAINT uq_role_permission UNIQUE (organisation_id, role_id, permission_id),
    CONSTRAINT chk_role_permission_version CHECK (row_version >= 0)
);

CREATE INDEX idx_role_permission_organisation_permission
    ON role_permission (organisation_id, permission_id);
CREATE INDEX idx_role_permission_permission ON role_permission (permission_id);

CREATE TABLE membership_permission (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    permission_id UUID NOT NULL REFERENCES permission (id),
    effect TEXT NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL,
    granted_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_membership_permission_membership
        FOREIGN KEY (organisation_id, membership_id)
        REFERENCES user_organisation_membership (organisation_id, id),
    CONSTRAINT uq_membership_permission UNIQUE (organisation_id, membership_id, permission_id),
    CONSTRAINT chk_membership_permission_effect CHECK (effect IN ('ALLOW', 'DENY')),
    CONSTRAINT chk_membership_permission_version CHECK (row_version >= 0)
);

COMMENT ON TABLE membership_permission IS
    'Direct allow/deny permission override for a membership, layered over role-derived grants.';

CREATE INDEX idx_membership_permission_membership ON membership_permission (membership_id);

CREATE TABLE user_role_assignment (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    branch_id UUID,
    scope_type TEXT NOT NULL,
    status TEXT NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    assigned_by UUID,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_user_role_assignment_membership
        FOREIGN KEY (organisation_id, user_id)
        REFERENCES user_organisation_membership (organisation_id, user_id),
    CONSTRAINT fk_user_role_assignment_role
        FOREIGN KEY (organisation_id, role_id) REFERENCES role (organisation_id, id),
    CONSTRAINT fk_user_role_assignment_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_user_role_assignment_scope CHECK (
        (scope_type = 'TENANT' AND branch_id IS NULL) OR
        (scope_type = 'BRANCH' AND branch_id IS NOT NULL)
    ),
    CONSTRAINT chk_user_role_assignment_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED')),
    CONSTRAINT chk_user_role_assignment_version CHECK (row_version >= 0)
);

CREATE UNIQUE INDEX uq_active_tenant_user_role_assignment
    ON user_role_assignment (organisation_id, user_id, role_id, scope_type)
    WHERE status = 'ACTIVE' AND branch_id IS NULL;
CREATE UNIQUE INDEX uq_active_branch_user_role_assignment
    ON user_role_assignment (organisation_id, user_id, role_id, branch_id, scope_type)
    WHERE status = 'ACTIVE' AND branch_id IS NOT NULL;
CREATE INDEX idx_user_role_assignment_organisation_status
    ON user_role_assignment (organisation_id, status);
CREATE INDEX idx_user_role_assignment_organisation_user
    ON user_role_assignment (organisation_id, user_id);
CREATE INDEX idx_user_role_assignment_organisation_branch
    ON user_role_assignment (organisation_id, branch_id);

CREATE TABLE organisation_setting (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    setting_key TEXT NOT NULL,
    setting_value JSONB NOT NULL,
    value_type TEXT NOT NULL,
    is_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_organisation_setting_effective UNIQUE (organisation_id, setting_key, effective_from),
    CONSTRAINT chk_organisation_setting_range CHECK (
        effective_to IS NULL OR effective_to > effective_from
    ),
    CONSTRAINT chk_organisation_setting_version CHECK (row_version >= 0)
);

CREATE INDEX idx_organisation_setting_organisation_key
    ON organisation_setting (organisation_id, setting_key);
COMMENT ON TABLE organisation_setting IS 'Time-effective organisation configuration. Sensitive values must be encrypted before persistence.';

CREATE TABLE business_date (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL UNIQUE REFERENCES organisation (id),
    current_business_date DATE NOT NULL,
    current_cob_date DATE,
    status TEXT NOT NULL,
    last_advanced_at TIMESTAMPTZ,
    advanced_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_business_date_status CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED', 'ADVANCING')),
    CONSTRAINT chk_business_date_version CHECK (row_version >= 0)
);

CREATE TABLE organisation_transition_log (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    entity_id UUID NOT NULL REFERENCES organisation (id),
    transition_name TEXT NOT NULL,
    status_from TEXT NOT NULL,
    status_to TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    metadata_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT chk_organisation_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_organisation_transition_log_organisation_entity
    ON organisation_transition_log (organisation_id, entity_id, created_at DESC);

CREATE TABLE branch_transition_log (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    branch_id UUID NOT NULL,
    entity_id UUID NOT NULL,
    transition_name TEXT NOT NULL,
    status_from TEXT NOT NULL,
    status_to TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    metadata_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT fk_branch_transition_log_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_branch_transition_log_entity CHECK (entity_id = branch_id),
    CONSTRAINT chk_branch_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_branch_transition_log_organisation_entity
    ON branch_transition_log (organisation_id, entity_id, created_at DESC);

CREATE TABLE user_account_transition_log (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    branch_id UUID,
    entity_id UUID NOT NULL REFERENCES user_account (id),
    transition_name TEXT NOT NULL,
    status_from TEXT NOT NULL,
    status_to TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    metadata_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT fk_user_account_transition_log_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_user_account_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_user_account_transition_log_organisation_entity
    ON user_account_transition_log (organisation_id, entity_id, created_at DESC);

CREATE TABLE user_organisation_membership_transition_log (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL,
    branch_id UUID,
    entity_id UUID NOT NULL,
    transition_name TEXT NOT NULL,
    status_from TEXT NOT NULL,
    status_to TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    metadata_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT fk_membership_transition_log_membership
        FOREIGN KEY (organisation_id, entity_id)
        REFERENCES user_organisation_membership (organisation_id, id),
    CONSTRAINT fk_membership_transition_log_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_membership_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_membership_transition_log_organisation_entity
    ON user_organisation_membership_transition_log (organisation_id, entity_id, created_at DESC);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    event_time TIMESTAMPTZ NOT NULL,
    actor_user_id UUID,
    actor_external_subject TEXT,
    actor_type TEXT NOT NULL,
    branch_id UUID,
    event_type TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    entity_id UUID,
    action TEXT NOT NULL,
    outcome TEXT NOT NULL,
    severity TEXT NOT NULL,
    ip_address TEXT,
    user_agent TEXT,
    correlation_id TEXT,
    request_id TEXT,
    before_jsonb JSONB,
    after_jsonb JSONB,
    metadata_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_user_id) REFERENCES user_account (id),
    CONSTRAINT fk_audit_event_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_audit_event_actor_type CHECK (
        actor_type IN ('USER', 'SYSTEM', 'SERVICE_ACCOUNT', 'INTEGRATION', 'MIGRATION', 'SCHEDULER')
    ),
    CONSTRAINT chk_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT chk_audit_event_severity CHECK (severity IN ('INFO', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL'))
);

CREATE INDEX idx_audit_event_organisation_time ON audit_event (organisation_id, event_time DESC);
CREATE INDEX idx_audit_event_entity ON audit_event (entity_type, entity_id);
CREATE INDEX idx_audit_event_actor ON audit_event (actor_user_id);
COMMENT ON TABLE audit_event IS 'Append-only business and security audit trail; never store credentials, tokens, or session cookies.';

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    routing_key TEXT NOT NULL,
    payload_jsonb JSONB NOT NULL,
    publish_status TEXT NOT NULL,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_outbox_event_status CHECK (
        publish_status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD_LETTER')
    ),
    CONSTRAINT chk_outbox_event_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT chk_outbox_event_version CHECK (row_version >= 0)
);

CREATE INDEX idx_outbox_event_publish_status_retry ON outbox_event (publish_status, next_retry_at);
CREATE INDEX idx_outbox_event_organisation_created ON outbox_event (organisation_id, created_at);
COMMENT ON TABLE outbox_event IS 'Application visibility record for selected integration events; Namastack remains the delivery outbox.';
