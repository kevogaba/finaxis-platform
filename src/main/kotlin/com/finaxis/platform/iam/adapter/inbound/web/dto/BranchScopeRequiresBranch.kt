package com.finaxis.platform.iam.adapter.inbound.web.dto

import com.finaxis.platform.lifecycle.application.RoleAssignmentScopeType
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.util.UUID
import kotlin.reflect.KClass

/** A request body that names a role-assignment scope and, for a BRANCH scope, its branch. */
interface BranchScoped {
    /** Whether the assignment applies to the whole tenant or to one branch. */
    val scopeType: RoleAssignmentScopeType

    /** The target branch; required for [RoleAssignmentScopeType.BRANCH]. */
    val branchId: UUID?
}

/**
 * Cross-field rule: a [BranchScoped] body with the BRANCH scope must carry a `branch_id`. The
 * violation is attributed to `branch_id`, so a client sees which field to fix. A TENANT scope with
 * a `branch_id` is deliberately not judged here: the application layer already refuses it.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [BranchScopeRequiresBranchValidator::class])
annotation class BranchScopeRequiresBranch(
    /** The client-facing violation message. */
    val message: String = "A branch is required when the scope type is BRANCH.",
    /** Validation groups; unused. */
    val groups: Array<KClass<*>> = [],
    /** Constraint payload; unused. */
    val payload: Array<KClass<out Payload>> = [],
)

/** Enforces [BranchScopeRequiresBranch] and attributes the violation to the `branchId` field. */
class BranchScopeRequiresBranchValidator :
    ConstraintValidator<BranchScopeRequiresBranch, BranchScoped> {
    override fun isValid(
        value: BranchScoped?,
        context: ConstraintValidatorContext,
    ): Boolean {
        if (value == null || value.scopeType != RoleAssignmentScopeType.BRANCH) return true
        if (value.branchId != null) return true
        context.disableDefaultConstraintViolation()
        context
            .buildConstraintViolationWithTemplate(context.defaultConstraintMessageTemplate)
            .addPropertyNode("branchId")
            .addConstraintViolation()
        return false
    }
}
