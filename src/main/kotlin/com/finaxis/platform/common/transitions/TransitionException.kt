package com.finaxis.platform.common.transitions

/**
 * Base exception for deterministic FSM validation and execution failures.
 */
open class TransitionException(
    message: String,
) : RuntimeException(message)

/**
 * Raised when a requested transition name is not defined for a state machine.
 */
class InvalidTransitionException(
    message: String,
) : TransitionException(message)

/**
 * Raised when a defined transition is attempted from the wrong source state.
 */
class TransitionNotAllowedException(
    message: String,
) : TransitionException(message)

/**
 * Raised when a guard or policy rejects an otherwise legal transition.
 */
class TransitionGuardException(
    message: String,
) : TransitionException(message)
