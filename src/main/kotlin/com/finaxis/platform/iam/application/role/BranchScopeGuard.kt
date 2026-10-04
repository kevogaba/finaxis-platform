package com.finaxis.platform.iam.application.role

import com.finaxis.platform.common.application.InvalidRequestException
import java.util.UUID

/**
 * The branch a BRANCH-scoped role assignment targets.
 *
 * The request DTO already refuses a BRANCH scope without a branch; this is the second line of
 * defence, so a caller that skipped that validation (a non-HTTP adapter, a missing `@Valid`) gets
 * the same `validation_failed` 400 instead of an unchecked failure surfacing as a 500.
 *
 * @throws InvalidRequestException when [branchId] is absent.
 */
fun requireBranchScopeBranchId(branchId: UUID?): UUID =
    branchId
        ?: throw InvalidRequestException(
            "validation_failed",
            "A branch-scoped role assignment requires a branch.",
        )
