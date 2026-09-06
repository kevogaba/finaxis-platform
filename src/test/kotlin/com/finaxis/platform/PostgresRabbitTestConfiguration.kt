package com.finaxis.platform

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

/**
 * [PostgresTestConfiguration] plus a real RabbitMQ container, for tests that assert
 * `/actuator/health`'s aggregate status code: the RabbitMQ health indicator dials
 * `spring.rabbitmq.host`/`port` regardless of what the test is actually exercising, and without a
 * reachable broker the aggregate reports `DOWN` for a reason unrelated to the test's own
 * assertion. Deliberately without [TestcontainersConfiguration]'s Grafana LGTM container - that
 * stack has its own multi-minute startup ceiling and nothing here needs it.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresRabbitTestConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(TestContainerImages.REDIS)).withExposedPorts(6379)

    @Bean
    @ServiceConnection
    fun rabbitContainer(): RabbitMQContainer =
        RabbitMQContainer(DockerImageName.parse(TestContainerImages.RABBITMQ))

    @Bean
    fun jwtDecoder(): JwtDecoder = JwtDecoder { error("Test context does not decode JWTs") }
}
