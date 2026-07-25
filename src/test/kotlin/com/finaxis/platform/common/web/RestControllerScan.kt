package com.finaxis.platform.common.web

import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RestController

internal fun scanRestControllers(): List<Class<*>> =
    ClassPathScanningCandidateComponentProvider(false)
        .apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
        .findCandidateComponents("com.finaxis.platform")
        .map { candidate -> Class.forName(requireNotNull(candidate.beanClassName)) }
        .filterNot { type ->
            type.protectionDomain.codeSource.location.path
                .contains("/test/")
        }
