CREATE INDEX idx_membership_organisation_created_at_id
    ON user_organisation_membership (organisation_id, created_at DESC, id DESC);

CREATE INDEX idx_user_branch_assignment_organisation_assigned_at_id
    ON user_branch_assignment (organisation_id, assigned_at DESC, id DESC);

CREATE INDEX idx_user_role_assignment_organisation_assigned_at_id
    ON user_role_assignment (organisation_id, assigned_at DESC, id DESC);

CREATE INDEX idx_user_account_created_at_id
    ON user_account (created_at DESC, id DESC);
