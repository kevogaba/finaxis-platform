package com.finaxis.platform.lifecycle.adapter.inbound.web

/*
 * OpenAPI descriptions of the audit search filters (#183), shared by the tenant search and both
 * platform searches so the three routes cannot describe one parameter differently.
 */

internal const val AUDIT_ACTION_DOC = "Exact action, for example branch.update."
internal const val AUDIT_ACTION_PREFIX_DOC =
    "Literal prefix of the action, 2 to 64 characters, for example branch. (% and _ match " +
        "themselves). This is the event type filter: event_type is the action."
internal const val AUDIT_OUTCOME_DOC = "Exact outcome. Case-insensitive."
internal const val AUDIT_SEVERITY_DOC =
    "Exact severity. Case-insensitive. Not together with min_severity."
internal const val AUDIT_MIN_SEVERITY_DOC =
    "This severity or above (INFO < LOW < MEDIUM < HIGH < CRITICAL). Case-insensitive. Not " +
        "together with severity."
internal const val AUDIT_BRANCH_DOC = "Branch the event was recorded against."
internal const val AUDIT_Q_DOC =
    "Case-insensitive text, 3 to 64 characters, found anywhere in the action, entity type or " +
        "reason (% and _ match themselves). Requires occurred_from, at most 31 days before " +
        "occurred_to (or now)."
internal const val AUDIT_ACTOR_TYPE_DOC =
    "USER for a person, SYSTEM for a job, listener or bootstrap. Case-insensitive."
internal const val AUDIT_ACTOR_SUBJECT_DOC =
    "Exact identity-provider subject of the actor (actor_external_subject), 1 to 255 characters."
internal const val PLATFORM_AUDIT_ACTOR_SUBJECT_DOC =
    "Not available: platform pages withhold actor_external_subject, so any value is a 400."
internal const val AUDIT_SORT_DIR_DOC =
    "Order by event time, then id: DESC (default, newest first) or ASC. Case-insensitive."
