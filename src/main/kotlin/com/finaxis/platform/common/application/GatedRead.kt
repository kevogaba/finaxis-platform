package com.finaxis.platform.common.application

/**
 * Marks a read-side use case that authorises its caller itself, before it returns anything.
 *
 * A web adapter may call a query service only through a method carrying this marker (ADR 0030
 * decision 6), so no controller can return data, or read a mutation's result back, without a
 * permission check. The marker is a promise the build verifies, not documentation:
 * `PermissionFreeReadRuleTests` fails when a marked method with a body neither calls a
 * permission guard (directly, through a helper of its own class, or through another marked
 * method it delegates to) nor, in the lifecycle and IAM query services, takes the caller.
 *
 * It is a marker on purpose. Adding it to a method that does not authorise is the one way to
 * defeat the rule, and the guard-call check above is what catches that.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class GatedRead
