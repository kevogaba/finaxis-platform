package com.finaxis.platform.common.web.validation

import com.finaxis.platform.common.web.scanRestControllers
import jakarta.validation.Constraint
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.Test
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.lang.reflect.AnnotatedArrayType
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.AnnotatedParameterizedType
import java.lang.reflect.AnnotatedType
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PLATFORM_PACKAGE = "com.finaxis.platform"
private const val CONSTRAINTS_PACKAGE = "jakarta.validation.constraints."

/**
 * Bean Validation only runs on a request body that is marked `@Valid` (or `@Validated`), and only
 * cascades into a nested object whose field is `@Valid`, or into the elements of a collection, map
 * or array whose element type is (`List<@Valid T>`). A constraint on a DTO that nothing triggers is
 * silently skipped, so this guard fails the build for every such gap (#206). It also refuses
 * `@Valid` on the container itself (`@field:Valid val items: List<T>`), which Hibernate Validator
 * deprecates (HV000271) in favour of the element form.
 *
 * Known limits, deliberately not covered:
 * - constraints inherited from a superclass or interface of a DTO (only the declared fields and
 *   no-argument getters of each type are inspected);
 * - type-use element constraints such as `List<@NotBlank String>` (only constraint annotations
 *   on the field or getter themselves are seen);
 * - polymorphic bodies, where the declared type is a base class or interface and the constrained
 *   types are only known at deserialisation time;
 * - a constraint that appears only on a type reached through a non-platform class.
 */
class RequestBodyValidationArchitectureTest {
    @Test
    fun `every constrained request body is validated and cascades into constrained nested types`() {
        val violations = validationViolations(scanRestControllers())

        assertTrue(
            violations.isEmpty(),
            "Constrained @RequestBody DTOs must be @Valid, with @field:Valid on nested " +
                "constrained objects and List<@Valid T> on constrained elements:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun `guard rejects a constrained body that is not valid`() {
        assertEquals(
            listOf("${MissingValidController::class.java.name}#create: parameter 0 is not @Valid"),
            validationViolations(listOf(MissingValidController::class.java)),
        )
    }

    @Test
    fun `guard rejects a body constrained only by a class level rule that is not valid`() {
        assertEquals(
            listOf(
                "${ClassLevelController::class.java.name}#create: parameter 0 is not @Valid",
            ),
            validationViolations(listOf(ClassLevelController::class.java)),
        )
    }

    @Test
    fun `guard accepts valid and validated bodies and unconstrained bodies`() {
        assertTrue(validationViolations(listOf(ValidController::class.java)).isEmpty())
        assertTrue(validationViolations(listOf(ValidatedController::class.java)).isEmpty())
        assertTrue(validationViolations(listOf(UnconstrainedController::class.java)).isEmpty())
    }

    @Test
    fun `guard rejects a nested constrained field without cascade`() {
        val where = "${NestedMissingCascadeController::class.java.name}#create"
        val outer = OuterWithUncascadedChild::class.java.name

        assertEquals(
            listOf(
                "$where: $outer.child is not @Valid",
                "$where: $outer.children elements are not @Valid (List<@Valid T>)",
            ),
            validationViolations(listOf(NestedMissingCascadeController::class.java)).sorted(),
        )
    }

    @Test
    fun `guard refuses @Valid on a container instead of on its element type`() {
        val where = "${ContainerLevelCascadeController::class.java.name}#create"
        val outer = OuterWithContainerLevelCascade::class.java.name

        assertEquals(
            listOf(
                "$where: $outer.children elements are not @Valid (List<@Valid T>)",
                "$where: $outer.children is @Valid on its container, which Hibernate " +
                    "Validator deprecates: mark the element type (List<@Valid T>)",
                "$where: $outer.lookup elements are not @Valid (List<@Valid T>)",
                "$where: $outer.lookup is @Valid on its container, which Hibernate " +
                    "Validator deprecates: mark the element type (List<@Valid T>)",
            ),
            validationViolations(listOf(ContainerLevelCascadeController::class.java)).sorted(),
        )
    }

    @Test
    fun `guard accepts a cascaded nested field and rejects an unvalidated collection body`() {
        assertTrue(validationViolations(listOf(NestedCascadeController::class.java)).isEmpty())
        assertEquals(
            listOf(
                "${CollectionBodyController::class.java.name}#create: parameter 0 is not @Valid",
            ),
            validationViolations(listOf(CollectionBodyController::class.java)),
        )
    }

    @Test
    fun `guard requires a cascade into a nested type constrained only at class level`() {
        val where = "${NestedClassLevelMissingCascadeController::class.java.name}#create"
        val outer = OuterWithUncascadedClassLevelChild::class.java.name

        assertEquals(
            listOf("$where: $outer.child is not @Valid"),
            validationViolations(listOf(NestedClassLevelMissingCascadeController::class.java)),
        )
        assertTrue(
            validationViolations(listOf(NestedClassLevelCascadeController::class.java)).isEmpty(),
        )
    }

    private fun validationViolations(controllers: List<Class<*>>): List<String> =
        controllers.flatMap { controller ->
            controller.methods
                .sortedBy { it.name }
                .flatMap { method -> methodViolations(controller, method) }
        }

    private fun methodViolations(
        controller: Class<*>,
        method: Method,
    ): List<String> =
        method.parameters.withIndex().flatMap { (index, parameter) ->
            if (!parameter.isAnnotationPresent(RequestBody::class.java)) {
                return@flatMap emptyList()
            }
            val where = "${controller.name}#${method.name}"
            val bodyTypes = platformTypesIn(parameter.parameterizedType)
            val constrained = bodyTypes.any { isConstrained(it, mutableSetOf()) }
            buildList {
                if (constrained && !isValidationTrigger(parameter)) {
                    add("$where: parameter $index is not @Valid")
                }
                bodyTypes.forEach { addAll(cascadeViolations(it, where, mutableSetOf())) }
            }
        }

    private fun cascadeViolations(
        type: Class<*>,
        where: String,
        visited: MutableSet<Class<*>>,
    ): List<String> {
        if (!visited.add(type)) return emptyList()
        return type.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            .flatMap { field ->
                buildList {
                    addAll(
                        markViolations(
                            field.annotatedType,
                            field.isAnnotationPresent(Valid::class.java),
                            "$where: ${type.name}.${field.name}",
                        ),
                    )
                    platformTypesIn(field.genericType).forEach {
                        addAll(cascadeViolations(it, where, visited))
                    }
                }
            }
    }

    /**
     * Where the cascade into [type] must be marked: on the field itself for a nested object, on the
     * element type for a collection, map or array (each element type that reaches a constraint,
     * nested containers included), and never on the container.
     */
    private fun markViolations(
        type: AnnotatedType,
        valid: Boolean,
        label: String,
    ): List<String> {
        val elements = containerElements(type)
        if (elements == null) {
            val needsCascade = platformTypesIn(type.type).any { isConstrained(it, mutableSetOf()) }
            return if (needsCascade && !valid) listOf("$label is not @Valid") else emptyList()
        }
        return buildList {
            if (valid) {
                add(
                    "$label is @Valid on its container, which Hibernate Validator deprecates: " +
                        "mark the element type (List<@Valid T>)",
                )
            }
            elements.forEach { element ->
                val elementValid = element.isAnnotationPresent(Valid::class.java)
                if (containerElements(element) == null) {
                    val needsCascade =
                        platformTypesIn(element.type).any { isConstrained(it, mutableSetOf()) }
                    if (needsCascade && !elementValid) {
                        add("$label elements are not @Valid (List<@Valid T>)")
                    }
                } else {
                    addAll(markViolations(element, elementValid, label))
                }
            }
        }.distinct()
    }

    /** The element types of a container (type arguments, or an array's component), else null. */
    private fun containerElements(type: AnnotatedType): List<AnnotatedType>? =
        when (type) {
            is AnnotatedParameterizedType -> type.annotatedActualTypeArguments.toList()
            is AnnotatedArrayType -> listOf(type.annotatedGenericComponentType)
            else -> null
        }

    private fun isValidationTrigger(element: AnnotatedElement): Boolean =
        element.isAnnotationPresent(Valid::class.java) ||
            element.isAnnotationPresent(Validated::class.java)

    /** True when [type], or any platform type reachable from its fields, declares a constraint. */
    private fun isConstrained(
        type: Class<*>,
        visited: MutableSet<Class<*>>,
    ): Boolean {
        if (!visited.add(type)) return false
        val own =
            declaresConstraint(type) ||
                type.declaredFields.any { declaresConstraint(it) } ||
                type.declaredMethods.any { it.parameterCount == 0 && declaresConstraint(it) }
        return own ||
            type.declaredFields.any { field ->
                platformTypesIn(field.genericType).any { isConstrained(it, visited) }
            }
    }

    private fun declaresConstraint(element: AnnotatedElement): Boolean =
        element.annotations.any { annotation ->
            val annotationType = annotation.annotationClass.java
            annotationType.name.startsWith(CONSTRAINTS_PACKAGE) ||
                annotationType.isAnnotationPresent(Constraint::class.java)
        }

    /** The platform classes a type mentions, looking through collections, maps and arrays. */
    private fun platformTypesIn(type: Type): List<Class<*>> =
        when (type) {
            is Class<*> -> {
                when {
                    type.isArray -> platformTypesIn(type.componentType)
                    type.name.startsWith(PLATFORM_PACKAGE) && !type.isEnum -> listOf(type)
                    else -> emptyList()
                }
            }

            is ParameterizedType -> {
                platformTypesIn(type.rawType) + type.actualTypeArguments.flatMap(::platformTypesIn)
            }

            is GenericArrayType -> {
                platformTypesIn(type.genericComponentType)
            }

            is WildcardType -> {
                type.upperBounds.flatMap(::platformTypesIn)
            }

            else -> {
                emptyList()
            }
        }
}

private data class ConstrainedBody(
    @field:NotBlank
    @field:Size(min = 3, max = 500)
    val reason: String,
)

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [])
private annotation class ClassLevelRule

@ClassLevelRule
private data class ClassLevelBody(
    val reason: String?,
)

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class ClassLevelController {
    @PostMapping
    fun create(
        @RequestBody body: ClassLevelBody,
    ) = Unit
}

private data class UnconstrainedBody(
    val reason: String?,
)

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class MissingValidController {
    @PostMapping
    fun create(
        @RequestBody body: ConstrainedBody,
    ) = Unit
}

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class ValidController {
    @PostMapping
    fun create(
        @RequestBody @Valid body: ConstrainedBody,
    ) = Unit
}

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class ValidatedController {
    @PostMapping
    fun create(
        @RequestBody @Validated body: ConstrainedBody,
    ) = Unit
}

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class UnconstrainedController {
    @PostMapping
    fun create(
        @RequestBody body: UnconstrainedBody,
    ) = Unit
}

private data class OuterWithUncascadedChild(
    val child: ConstrainedBody,
    val children: List<ConstrainedBody>,
)

private data class OuterWithCascadedChild(
    @field:Valid
    val child: ConstrainedBody,
    val children: List<@Valid ConstrainedBody>,
    val lookup: Map<String, List<@Valid ConstrainedBody>>,
)

private data class OuterWithContainerLevelCascade(
    @field:Valid
    val children: List<ConstrainedBody>,
    @field:Valid
    val lookup: Map<String, ConstrainedBody>,
)

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class ContainerLevelCascadeController {
    @PostMapping
    fun create(
        @RequestBody @Valid body: OuterWithContainerLevelCascade,
    ) = Unit
}

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class NestedMissingCascadeController {
    @PostMapping
    fun create(
        @RequestBody @Valid body: OuterWithUncascadedChild,
    ) = Unit
}

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class NestedCascadeController {
    @PostMapping
    fun create(
        @RequestBody @Valid body: OuterWithCascadedChild,
    ) = Unit
}

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class CollectionBodyController {
    @PostMapping
    fun create(
        @RequestBody body: List<ConstrainedBody>,
    ) = Unit
}

private data class OuterWithUncascadedClassLevelChild(
    val child: ClassLevelBody,
)

private data class OuterWithCascadedClassLevelChild(
    @field:Valid
    val child: ClassLevelBody,
)

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class NestedClassLevelMissingCascadeController {
    @PostMapping
    fun create(
        @RequestBody @Valid body: OuterWithUncascadedClassLevelChild,
    ) = Unit
}

@Suppress("UnusedParameter") // Fixture: only the handler signature matters.
private class NestedClassLevelCascadeController {
    @PostMapping
    fun create(
        @RequestBody @Valid body: OuterWithCascadedClassLevelChild,
    ) = Unit
}
