ALTER TABLE user_organisation_membership
    ADD COLUMN pending_keycloak_invite BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE user_organisation_membership
    ADD COLUMN pending_application_invite BOOLEAN NOT NULL DEFAULT FALSE;
