CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    keycloak_subject VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(320),
    full_name VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_user_id UUID NULL,
    created_by_membership_id UUID NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_user_id UUID NULL,
    updated_by_membership_id UUID NULL
);

CREATE TABLE organisation (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_user_id UUID NULL,
    created_by_membership_id UUID NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_user_id UUID NULL,
    updated_by_membership_id UUID NULL
);

CREATE TABLE organisation_membership (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    organisation_id UUID NOT NULL REFERENCES organisation(id),
    status VARCHAR(32) NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL,
    left_at TIMESTAMPTZ NULL,
    created_by_user_id UUID NULL REFERENCES app_user(id),
    created_by_membership_id UUID NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_user_id UUID NULL REFERENCES app_user(id),
    updated_by_membership_id UUID NULL,
    CONSTRAINT uq_membership_user_organisation UNIQUE (user_id, organisation_id)
);

CREATE TABLE permission (
    id UUID PRIMARY KEY,
    module VARCHAR(100) NOT NULL,
    resource VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    code VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    risk_level VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    deprecated_at TIMESTAMPTZ NULL,
    replaced_by_permission_id UUID NULL REFERENCES permission(id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE role (
    id UUID PRIMARY KEY,
    organisation_id UUID NULL REFERENCES organisation(id),
    name VARCHAR(255) NOT NULL,
    code VARCHAR(120) NOT NULL,
    description TEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by_user_id UUID NULL REFERENCES app_user(id),
    created_by_membership_id UUID NULL REFERENCES organisation_membership(id),
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by_user_id UUID NULL REFERENCES app_user(id),
    updated_by_membership_id UUID NULL REFERENCES organisation_membership(id),
    CONSTRAINT uq_role_organisation_code UNIQUE (organisation_id, code)
);

CREATE TABLE role_permission (
    id UUID PRIMARY KEY,
    role_id UUID NOT NULL REFERENCES role(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permission(id),
    CONSTRAINT uq_role_permission UNIQUE (role_id, permission_id)
);

CREATE TABLE membership_role (
    id UUID PRIMARY KEY,
    membership_id UUID NOT NULL REFERENCES organisation_membership(id) ON DELETE CASCADE,
    role_id UUID NOT NULL REFERENCES role(id),
    granted_by UUID NULL REFERENCES app_user(id),
    granted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_membership_role UNIQUE (membership_id, role_id)
);

CREATE TABLE membership_permission (
    id UUID PRIMARY KEY,
    membership_id UUID NOT NULL REFERENCES organisation_membership(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permission(id),
    effect VARCHAR(16) NOT NULL,
    granted_by UUID NULL REFERENCES app_user(id),
    granted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_membership_permission UNIQUE (membership_id, permission_id),
    CONSTRAINT chk_membership_permission_effect CHECK (effect IN ('ALLOW', 'DENY'))
);

-- Branch and warehouse aggregates do not exist yet. These UUID references are intentionally
-- placeholders so IAM scope semantics can be introduced without inventing domain tables.
CREATE TABLE membership_branch_scope (
    id UUID PRIMARY KEY,
    membership_id UUID NOT NULL REFERENCES organisation_membership(id) ON DELETE CASCADE,
    branch_id UUID NOT NULL,
    granted_by UUID NULL REFERENCES app_user(id),
    granted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_membership_branch_scope UNIQUE (membership_id, branch_id)
);

CREATE TABLE membership_warehouse_scope (
    id UUID PRIMARY KEY,
    membership_id UUID NOT NULL REFERENCES organisation_membership(id) ON DELETE CASCADE,
    warehouse_id UUID NOT NULL,
    granted_by UUID NULL REFERENCES app_user(id),
    granted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_membership_warehouse_scope UNIQUE (membership_id, warehouse_id)
);

CREATE INDEX idx_membership_user_status ON organisation_membership(user_id, status);
CREATE INDEX idx_membership_organisation_status ON organisation_membership(organisation_id, status);
CREATE INDEX idx_membership_role_membership ON membership_role(membership_id);
CREATE INDEX idx_role_permission_role ON role_permission(role_id);
CREATE INDEX idx_membership_permission_membership ON membership_permission(membership_id);
