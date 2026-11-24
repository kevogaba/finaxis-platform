package com.finaxis.platform

import com.finaxis.platform.SharedTestContainers.IsolatedPostgresDatabase
import com.finaxis.platform.SharedTestContainers.IsolatedRabbitVirtualHost
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * [PostgresTestConfiguration] plus a virtual host of its own on the suite's shared RabbitMQ broker
 * (see [SharedTestContainers]), for tests that assert `/actuator/health`'s aggregate status code:
 * the RabbitMQ health indicator dials the broker regardless of what the test is actually
 * exercising, and without a reachable broker the aggregate reports `DOWN` for a reason unrelated
 * to the test's own assertion. Deliberately without [TestcontainersConfiguration]'s Grafana LGTM
 * stack - nothing here needs it.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresRabbitTestConfiguration {
    @Bean
    fun postgresDatabase(): IsolatedPostgresDatabase = SharedTestContainers.newDatabase()

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(TestContainerImages.REDIS)).withExposedPorts(6379)

    @Bean
    fun rabbitVirtualHost(): IsolatedRabbitVirtualHost = SharedTestContainers.newVirtualHost()

    @Bean
    fun amqpEnabled(): DynamicPropertyRegistrar = SharedTestContainers.amqpEnabled()

    @Bean
    fun jwtDecoder(): JwtDecoder = JwtDecoder { error("Test context does not decode JWTs") }
}
