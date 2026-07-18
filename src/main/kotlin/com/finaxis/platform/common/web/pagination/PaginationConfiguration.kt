package com.finaxis.platform.common.web.pagination

import com.finaxis.platform.common.web.api.InvalidPageRequestException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.domain.PageRequest
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

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

    /** Rejects malformed and out-of-range public pagination parameters before MVC caps them. */
    @Bean
    fun paginationParameterValidationInterceptor(
        properties: PaginationProperties,
    ): HandlerInterceptor =
        object : HandlerInterceptor {
            override fun preHandle(
                request: HttpServletRequest,
                response: HttpServletResponse,
                handler: Any,
            ): Boolean {
                request.parameterMap["page"]?.singleOrNull()?.let { page ->
                    if (page.toIntOrNull()?.takeIf { it >= 0 } == null) {
                        throw InvalidPageRequestException()
                    }
                }
                request.parameterMap["size"]?.singleOrNull()?.let { size ->
                    if (size.toIntOrNull()?.takeIf { it in 1..properties.maxPageSize } == null) {
                        throw InvalidPageRequestException()
                    }
                }
                return true
            }
        }

    /** Applies public pagination bounds to every versioned API endpoint. */
    @Bean
    fun paginationWebMvcConfigurer(
        paginationParameterValidationInterceptor: HandlerInterceptor,
    ): WebMvcConfigurer =
        object : WebMvcConfigurer {
            override fun addInterceptors(registry: InterceptorRegistry) {
                registry
                    .addInterceptor(
                        paginationParameterValidationInterceptor,
                    ).addPathPatterns("/api/**")
            }
        }
}
