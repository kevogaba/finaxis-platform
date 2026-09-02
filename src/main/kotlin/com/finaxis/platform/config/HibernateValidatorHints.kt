package com.finaxis.platform.config

import jakarta.validation.ConstraintValidator
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.util.ClassUtils

/**
 * Registers Hibernate Validator's built-in constraint validators for reflective instantiation.
 *
 * Every constraint is enforced by a `ConstraintValidator` that Spring instantiates through
 * `SpringConstraintValidatorFactory`, by name and with no arguments. Spring AOT registers the
 * validators reached from constrained *beans*, but request DTOs are not beans - they are method
 * parameters bound per request - so the validators their annotations need are missing from the
 * image and the first validated request fails with
 * `BeanInstantiationException: No default constructor found`.
 *
 * The set is discovered by scanning rather than listed, because the constraints a request DTO uses
 * change with the API surface and a missing one is not a build failure: it is a 500 on whichever
 * endpoint first uses it.
 */
class HibernateValidatorHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        if (!ClassUtils.isPresent(BUILT_IN_VALIDATOR_MARKER, classLoader)) {
            return
        }
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(ConstraintValidator::class.java))
        scanner
            .findCandidateComponents(BUILT_IN_VALIDATOR_PACKAGE)
            .mapNotNull { it.beanClassName }
            .forEach {
                hints.reflection().registerTypeIfPresent(
                    classLoader,
                    it,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                )
            }
    }

    internal companion object {
        /** Package holding every constraint validator Hibernate Validator ships. */
        const val BUILT_IN_VALIDATOR_PACKAGE =
            "org.hibernate.validator.internal.constraintvalidators"

        /** Present exactly when the scanned package is on the classpath. */
        const val BUILT_IN_VALIDATOR_MARKER = "$BUILT_IN_VALIDATOR_PACKAGE.bv.NotNullValidator"
    }
}
