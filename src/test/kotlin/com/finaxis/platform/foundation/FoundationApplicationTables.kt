package com.finaxis.platform.foundation

/**
 * Every application-owned table, in the order `ORDER BY table_name` returns them.
 *
 * `V1` created the first 23; `V6` added the five accounting tables. Starter-managed tables —
 * `outbox_record`, `event_publication` and JobRunr's — are deliberately absent: they live outside
 * Flyway and carry no `guid`, so the conventions asserted against this list do not apply to them.
 *
 * This list is hand-maintained on purpose. `FoundationSchemaGuidTests` compares it for **equality**
 * against what the database holds, so a migration that adds a table without adding it here fails
 * rather than silently escaping the identifier conventions.
 */
val APPLICATION_TABLES =
    listOf(
        "accounting_fiscal_period",
        "accounting_fiscal_year",
        "api_idempotency_record",
        "audit_event",
        "branch",
        "branch_transition_log",
        "business_date",
        "business_date_history",
        "fiscal_period_transition_log",
        "gl_account",
        "gl_account_transition_log",
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
