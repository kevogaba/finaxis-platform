package com.finaxis.platform.common.audit

/**
 * Well-known [AuditCommand.metadata] keys for facts that have no dedicated `audit_event` column.
 */
object AuditMetadataKeys {
    /** Module that originated the audited action, e.g. `"lifecycle"` or `"iam"`. */
    const val SOURCE_MODULE = "sourceModule"

    /** Application command or use-case name that triggered the audited action. */
    const val COMMAND_NAME = "commandName"

    /** Identifier of the external system a dispatch integrated with, e.g. `"KEYCLOAK"`. */
    const val EXTERNAL_SYSTEM_REFERENCE = "externalSystemReference"

    /** Human-readable event-type name travelling alongside a Namastack outbox `target`. */
    const val EVENT_TYPE = "eventType"
}
