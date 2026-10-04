package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.InvalidRequestException

/**
 * The justification a lifecycle transition cannot go without (suspend, close, return, reject,
 * revoke, deprovision ...): trimmed, [MIN_LENGTH] to [MAX_LENGTH] characters, never blank.
 *
 * Requiredness is carried by the type: a command that needs a reason takes a non-null [Reason],
 * so no caller can hand it a missing, blank or too-short one. The REST DTOs bound the same text
 * with Bean Validation; this type is the second line of defence so that a non-HTTP adapter (a
 * job, a message listener, another module) cannot bypass the bound, and the persisted
 * `status_reason`/audit text can never exceed its column. A rejection is an
 * [InvalidRequestException] with the `validation_failed` code, which the API maps to a 400.
 *
 * For an optional remark use [DecisionRemark] instead.
 */
class Reason private constructor(
    /** The normalised text: trimmed, non-blank and within the bounds. */
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is Reason && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    /** The bounds of a required reason, also used by the request DTOs. */
    companion object {
        /** The shortest reason accepted. */
        const val MIN_LENGTH: Int = 3

        /** The longest reason accepted, matching `organisation.status_reason` and friends. */
        const val MAX_LENGTH: Int = 500

        /**
         * Builds a required reason from caller text, trimmed and held to
         * [MIN_LENGTH]..[MAX_LENGTH] characters.
         *
         * @throws InvalidRequestException when [raw] is absent, blank, too short or too long.
         */
        fun required(raw: String?): Reason {
            val text = raw?.trim().orEmpty()
            if (text.length !in MIN_LENGTH..MAX_LENGTH) {
                throw InvalidRequestException(
                    "validation_failed",
                    "A reason of $MIN_LENGTH to $MAX_LENGTH characters is required.",
                )
            }
            return Reason(text)
        }
    }
}
