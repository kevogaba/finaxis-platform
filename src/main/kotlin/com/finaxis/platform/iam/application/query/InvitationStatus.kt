package com.finaxis.platform.iam.application.query

import java.time.Instant

/**
 * Where a member's identity provisioning stands, derived from the membership and the Keycloak
 * dispatch row (#274). It is a view, not a state: the user and membership state machines are
 * untouched. [status] is one of the [InvitationStatuses]; [lastErrorCode] is the closed failure
 * code of the last failed attempt, never the failure's text.
 */
data class InvitationStatus(
    val status: String,
    val attempts: Int,
    val lastErrorCode: String?,
    val updatedAt: Instant?,
)

/** The dispatch row an [InvitationStatus] is derived from. */
data class InvitationDispatch(
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: Instant,
)

/** The values of [InvitationStatus.status]. */
object InvitationStatuses {
    /** The membership awaits approval, which starts provisioning. */
    const val AWAITING_APPROVAL = "AWAITING_APPROVAL"

    /** The provisioning job is queued or running. */
    const val IN_PROGRESS = "IN_PROGRESS"

    /** The last provisioning attempt failed; it can be retried. */
    const val FAILED = "FAILED"

    /** The identity was provisioned (and the invitation email sent, when one was asked for). */
    const val COMPLETED = "COMPLETED"

    /** No provisioning was needed: the account already had an identity. */
    const val NOT_REQUIRED = "NOT_REQUIRED"
}

private const val UNKNOWN_ERROR_CODE = "UNKNOWN"
private val ERROR_CODE = Regex("^([A-Z][A-Z_]*):")

/**
 * The closed set a stored failure's prefix must belong to to be returned: the failure codes of
 * `InitialAdministratorBootstrapFailureCode` plus `CANCELLED`. Anything else, a legacy raw message
 * that happens to start with an uppercase label such as `ERROR:`, reports [UNKNOWN_ERROR_CODE].
 */
private val KNOWN_ERROR_CODES =
    setOf(
        "IDENTITY_PROVIDER_FAILED",
        "CONFLICT",
        "NOT_FOUND",
        "INVALID_STATE",
        "DATABASE_ERROR",
        "UNEXPECTED",
        "CANCELLED",
    )

/** Membership states reached only once provisioning is done, whatever the dispatch says. */
private val PROVISIONED_MEMBERSHIPS = setOf("ACTIVE", "SUSPENDED")

/**
 * Derives the status from the membership's state and its newest Keycloak dispatch, if any. A
 * stored failure holds `<CODE>:<ExceptionClass>`; rows written before that rule hold a raw message,
 * which is never returned: such a failure reports [UNKNOWN_ERROR_CODE].
 */
fun deriveInvitationStatus(
    membershipStatus: String,
    dispatch: InvitationDispatch?,
): InvitationStatus =
    when {
        dispatch == null && membershipStatus == "PENDING_APPROVAL" -> {
            InvitationStatus(InvitationStatuses.AWAITING_APPROVAL, 0, null, null)
        }

        dispatch == null -> {
            InvitationStatus(InvitationStatuses.NOT_REQUIRED, 0, null, null)
        }

        else -> {
            val status =
                when (dispatch.status) {
                    "SUCCEEDED" -> {
                        InvitationStatuses.COMPLETED
                    }

                    // A failed dispatch behind an ACTIVE member is stale: the identity committed
                    // and a later approval activated the member, so nothing is left to retry.
                    "FAILED" -> {
                        if (membershipStatus in PROVISIONED_MEMBERSHIPS) {
                            InvitationStatuses.COMPLETED
                        } else {
                            InvitationStatuses.FAILED
                        }
                    }

                    else -> {
                        InvitationStatuses.IN_PROGRESS
                    }
                }
            val code =
                dispatch.lastError
                    ?.takeIf { status == InvitationStatuses.FAILED }
                    ?.let(::failureCodeOf)
            InvitationStatus(status, dispatch.attempts, code, dispatch.updatedAt)
        }
    }

/** The closed code a stored failure holds, or [UNKNOWN_ERROR_CODE] for any other prefix. */
private fun failureCodeOf(lastError: String): String =
    ERROR_CODE
        .find(lastError)
        ?.groupValues
        ?.get(1)
        ?.takeIf { it in KNOWN_ERROR_CODES }
        ?: UNKNOWN_ERROR_CODE
