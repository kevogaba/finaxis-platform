package com.finaxis.platform

import com.finaxis.platform.SharedTestContainers.IsolatedPostgresDatabase
import com.finaxis.platform.SharedTestContainers.IsolatedRabbitVirtualHost
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.grafana.LgtmStackContainer
import org.testcontainers.utility.DockerImageName

/**
 * The full set of backing services: a database and a RabbitMQ virtual host of its own on the
 * suite's shared servers, the shared Grafana LGTM stack (see [SharedTestContainers]), a Redis
 * container of its own, and the shared GreenMail server below.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    /** Shared by every context; see [SharedTestContainers.lgtm]. */
    @Bean
    @ServiceConnection
    fun grafanaLgtmContainer(): LgtmStackContainer = SharedTestContainers.lgtm

    @Bean
    fun postgresDatabase(): IsolatedPostgresDatabase = SharedTestContainers.newDatabase()

    @Bean
    fun rabbitVirtualHost(): IsolatedRabbitVirtualHost = SharedTestContainers.newVirtualHost()

    @Bean
    fun amqpEnabled(): DynamicPropertyRegistrar = SharedTestContainers.amqpEnabled()

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse(TestContainerImages.REDIS)).withExposedPorts(6379)

    companion object {
        /**
         * A Testcontainers "singleton container" (started once, in a static initializer, and
         * reaped by Testcontainers' own Ryuk container at suite end — never stopped by Spring)
         * rather than a Spring `@Bean`. `application.yaml` now always sets a `spring.mail.host`
         * default, so `MailSenderAutoConfiguration`'s `@ConditionalOnProperty` is satisfied either
         * way; what still requires this pattern is that `JavaMailSenderImpl` binds its host/port
         * at bean-creation time, before a bean-based `DynamicPropertyRegistrar` (which only runs
         * later, inside `finishBeanFactoryInitialization()`) could have overridden them with this
         * container's real mapped port. Consuming tests instead expose `spring.mail.host`/`port`
         * via their own static `@DynamicPropertySource` method reading from this field directly
         * (Spring only scans a test class and its enclosing classes for `@DynamicPropertySource`,
         * never `@Import`-ed configuration classes like this one).
         */
        val GREENMAIL_CONTAINER: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(TestContainerImages.GREENMAIL))
                .withExposedPorts(3025, 3143)
                .withEnv(
                    "GREENMAIL_ADDITIONAL_OPTS",
                    "-Dgreenmail.auth.disabled -Dgreenmail.verbose",
                ).apply { start() }
    }
}
