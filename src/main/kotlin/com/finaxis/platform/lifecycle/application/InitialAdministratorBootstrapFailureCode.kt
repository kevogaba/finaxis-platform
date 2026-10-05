package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.transitions.TransitionException
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import org.springframework.dao.DataAccessException

/**
 * The closed set of values `organisation_initial_administrator_bootstrap.last_failure_code` may
 * hold, and the only text of a failed bootstrap that tenant members and platform operators can
 * read through the API.
 *
 * The set is closed on purpose: a free-text failure can carry SQL, identity-provider output or an
 * email address, and `GET /api/v1/tenant` returns this column to every `tenant.view` holder. The
 * exception class, the root-cause class and the message-free frames go to the recorder's log
 * line; no exception message is logged. A failed retry's audit row records
 * the code and the exception class name, never the message (the Keycloak-job failure row holds the
 * class name only). `V24` enforces the same set with a `CHECK`, so a value outside it cannot be
 * stored whatever writes it; keep the two in step.
 */
enum class InitialAdministratorBootstrapFailureCode {
    /** The identity provider could not be reached, refused the call, or is not enabled. */
    IDENTITY_PROVIDER_FAILED,

    /** A uniqueness or state conflict, such as a username already in use or a repeated invite. */
    CONFLICT,

    /** A record the bootstrap depends on (organisation, user, membership, role) is missing. */
    NOT_FOUND,

    /** An internal invariant or precondition failed, such as a rejected transition or state. */
    INVALID_STATE,

    /** The database refused a read or write while bootstrapping. */
    DATABASE_ERROR,

    /** Anything else: an exception no other member names. */
    UNEXPECTED,
    ;

    /** Mapping from a thrown exception, and from a stored value, to a code. */
    companion object {
        /** Maps [failure] to exactly one code by its type; the message is never consulted. */
        fun from(failure: Throwable): InitialAdministratorBootstrapFailureCode =
            when (failure) {
                is IdentityProvisioningException -> IDENTITY_PROVIDER_FAILED

                is ConflictException -> CONFLICT

                is ResourceNotFoundException -> NOT_FOUND

                is IllegalStateException,
                is IllegalArgumentException,
                is InvalidOperationException,
                is TransitionException,
                -> INVALID_STATE

                is DataAccessException -> DATABASE_ERROR

                else -> UNEXPECTED
            }

        /**
         * Reads a stored value back, answering [UNEXPECTED] for text outside the set. After `V24`
         * the database cannot hold such text; this keeps a read from failing on a row an
         * out-of-band write left behind.
         */
        fun fromStored(value: String?): InitialAdministratorBootstrapFailureCode? =
            value?.let { stored -> entries.firstOrNull { it.name == stored } ?: UNEXPECTED }
    }
}
