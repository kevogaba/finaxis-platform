package com.finaxis.platform.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Shared application-level Spring configuration.
 */
@Configuration
@EnableCaching
class ApplicationConfiguration {
    /**
     * Provides a central UTC clock for deterministic time handling.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

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
