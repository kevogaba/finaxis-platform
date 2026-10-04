package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.InvalidRequestException

/**
 * The optional remark a lifecycle decision may carry (submit, approve, activate, reactivate ...):
 * trimmed and at most [MAX_LENGTH] characters, never blank.
 *
 * A command whose remark may be left out takes a `DecisionRemark?`: an absent or blank input is
 * `null`, so a blank string is never stored. Like [Reason] this is the second line of defence
 * behind the REST DTOs' Bean Validation, and a rejection is an [InvalidRequestException] with the
 * `validation_failed` code (a 400). A transition that must be justified takes a [Reason] instead.
 */
class DecisionRemark private constructor(
    /** The normalised text: trimmed, non-blank and within the bound. */
    val value: String,
) {
    override fun equals(other: Any?): Boolean = other is DecisionRemark && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    /** The bound of an optional remark, also used by the request DTOs. */
    companion object {
        /** The longest remark accepted; the same bound as a [Reason]. */
        const val MAX_LENGTH: Int = Reason.MAX_LENGTH

        /**
         * Builds an optional remark: absent or blank input is `null`, anything else is trimmed
         * and held to [MAX_LENGTH] characters.
         *
         * @throws InvalidRequestException when the trimmed text is longer than [MAX_LENGTH].
         */
        fun optional(raw: String?): DecisionRemark? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text.length > MAX_LENGTH) {
                throw InvalidRequestException(
                    "validation_failed",
                    "A remark must be at most $MAX_LENGTH characters.",
                )
            }
            return DecisionRemark(text)
        }
    }
}
