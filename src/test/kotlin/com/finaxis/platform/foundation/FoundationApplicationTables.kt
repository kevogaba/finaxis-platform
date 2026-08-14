package com.finaxis.platform.foundation

/** Every application-owned table created by `V1__foundation_schema.sql`. */
val APPLICATION_TABLES =
    listOf(
        "api_idempotency_record",
        "audit_event",
        "branch",
        "branch_transition_log",
        "business_date",
        "business_date_history",
        "identity_dispatch_log",
        "keycloak_identity_link",
        "membership_permission",
        "organisation",
        "organisation_initial_administrator_bootstrap",
        "organisation_setting",
        "organisation_transition_log",
        "permission",
        "reference_sequence",
        "role",
        "role_permission",
        "user_account",
        "user_account_transition_log",
        "user_branch_assignment",
        "user_organisation_membership",
        "user_organisation_membership_transition_log",
        "user_role_assignment",
    )
