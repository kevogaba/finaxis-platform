package com.finaxis.platform

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Spring Boot entry point for the Finaxis platform service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class PlatformApplication

/**
 * Starts the Finaxis platform application.
 */
fun main(args: Array<String>) {
    runApplication<PlatformApplication>(*args)
}
