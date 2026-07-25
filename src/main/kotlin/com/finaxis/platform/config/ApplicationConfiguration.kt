package com.finaxis.platform.config

import com.fasterxml.jackson.databind.ObjectMapper
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
     * JSONB persistence and Namastack outbox events use the camel-case Jackson 2 contract
     * required by existing Springdoc integrations. Spring Boot 4 otherwise auto-configures only
     * Jackson 3's JsonMapper.
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
}
