package com.finaxis.platform.common.web

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.core.type.filter.RegexPatternTypeFilter
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Modifier
import java.util.regex.Pattern

internal fun scanRestControllers(): List<Class<*>> =
    scanPlatformTypes(AnnotationTypeFilter(RestController::class.java))

internal fun <T> scanImplementationsOf(contract: Class<T>): List<Class<out T>> =
    scanPlatformTypes()
        .filter { type ->
            type != contract &&
                contract.isAssignableFrom(type) &&
                !type.isInterface &&
                !Modifier.isAbstract(type.modifiers)
        }.map { type -> type.asSubclass(contract) }

internal fun scanTypesWithMethodAnnotation(annotation: Class<out Annotation>): List<Class<*>> =
    scanPlatformTypes().filter { type ->
        type.declaredMethods.any { method -> method.isAnnotationPresent(annotation) }
    }

internal fun scanPlatformTypes(): List<Class<*>> =
    scanPlatformTypes(RegexPatternTypeFilter(Pattern.compile(".*")))

private fun scanPlatformTypes(
    filter: org.springframework.core.type.filter.TypeFilter,
): List<Class<*>> =
    ClassPathScanningCandidateComponentProvider(false)
        .apply { addIncludeFilter(filter) }
        .findCandidateComponents("com.finaxis.platform")
        .map { candidate -> Class.forName(requireNotNull(candidate.beanClassName)) }
        .filterNot(::isTestClasspathType)

private fun isTestClasspathType(type: Class<*>): Boolean =
    type.protectionDomain.codeSource.location.path
        .contains("/test/")
