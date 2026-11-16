package com.finaxis.platform

import com.finaxis.platform.SharedTestContainers.IsolatedPostgresDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * A database of its own on the suite's shared PostgreSQL server (see [SharedTestContainers]),
 * plus a Redis container of its own.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {
    @Bean
    fun postgresDatabase(): IsolatedPostgresDatabase = SharedTestContainers.newDatabase()

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(TestContainerImages.REDIS)).withExposedPorts(6379)

    @Bean
    fun jwtDecoder(): JwtDecoder = JwtDecoder { error("Test context does not decode JWTs") }
}
