package com.finaxis.platform

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.grafana.LgtmStackContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun grafanaLgtmContainer(): LgtmStackContainer =
        LgtmStackContainer(DockerImageName.parse(TestContainerImages.GRAFANA_OTEL_LGTM))

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))

    @Bean
    @ServiceConnection
    fun rabbitContainer(): RabbitMQContainer =
        RabbitMQContainer(DockerImageName.parse(TestContainerImages.RABBITMQ))

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(TestContainerImages.REDIS)).withExposedPorts(6379)
}
