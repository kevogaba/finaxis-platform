package com.finaxis.platform.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import java.time.Clock

/**
 * Shared application-level Spring configuration.
 */
@Configuration
@EnableCaching
class ApplicationConfiguration {
    /**
     * JSONB persistence uses the Jackson 2 API required by Namastack and existing Springdoc
     * integrations. Spring Boot 4 otherwise auto-configures only Jackson 3's JsonMapper.
     */
    @Bean
    fun jackson2ObjectMapper(): ObjectMapper = ObjectMapper().findAndRegisterModules()

    /**
     * Provides a central UTC clock for deterministic time handling.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /**
     * Registers access logging after request handling so emitted access logs use the shared
     * SLF4J/Logback pipeline instead of Tomcat's native access-log writer.
     */
    @Bean
    fun httpAccessLogFilter(): FilterRegistrationBean<HttpAccessLogFilter> =
        FilterRegistrationBean(HttpAccessLogFilter()).apply {
            order = Ordered.LOWEST_PRECEDENCE
        }

    /**
     * Configures the OpenAPI document and bearer authentication scheme.
     */
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("Finaxis Platform API").version("v1"))
            .components(
                Components().addSecuritySchemes(
                    "bearer-key",
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"),
                ),
            )
}
