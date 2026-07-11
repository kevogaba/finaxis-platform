package com.finaxis.platform.common.web.pagination

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer

/**
 * Spring MVC pagination configuration shared by all public listing endpoints.
 */
@Configuration
class PaginationConfiguration {
    /**
     * Applies the configured fallback and maximum page size to Spring Data Pageable arguments.
     */
    @Bean
    fun pageableHandlerMethodArgumentResolverCustomizer(
        properties: PaginationProperties,
    ): PageableHandlerMethodArgumentResolverCustomizer =
        PageableHandlerMethodArgumentResolverCustomizer { resolver ->
            resolver.setFallbackPageable(PageRequest.of(0, properties.defaultPageSize))
            resolver.setMaxPageSize(properties.maxPageSize)
        }
}
