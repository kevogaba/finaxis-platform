package com.finaxis.platform

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.rabbitmq.autoconfigure.RabbitConnectionDetails
import org.testcontainers.grafana.LgtmStackContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * The PostgreSQL, RabbitMQ and Grafana LGTM servers the test suite runs against: one of each per
 * test JVM, started on first use and reaped by Testcontainers' Ryuk when the JVM exits.
 *
 * The suite builds roughly forty distinct Spring contexts. Declared as `@Bean`s, every one of them
 * started - and on eviction stopped - its own containers, so a full run paid for forty-odd
 * PostgreSQL boots, a RabbitMQ boot per messaging context and a multi-minute-ceiling LGTM boot per
 * observability context, and the cached contexts kept all of those servers running at once.
 *
 * Sharing the server does not share the data. Each context still gets what a fresh container gave
 * it, from [newDatabase] and [newVirtualHost]:
 * - an empty database of its own on the shared PostgreSQL server, migrated by the context's own
 *   Flyway exactly as before. No test reads another database's rows, and advisory locks are
 *   database-scoped. The `pg_stat_activity`/`pg_locks` probes are server-wide views, but each looks
 *   only for backends blocked by its own test's transactions (by pid, by `current_database()`, or
 *   on an advisory key only that test contends), and tests in one JVM run one at a time, so no
 *   other database has such a backend while a probe runs. Forks each start their own servers.
 * - a RabbitMQ virtual host of its own, so its queues, exchanges and messages are invisible to a
 *   cached context that declares the same names.
 *
 * Redis stays a container per context (see [PostgresTestConfiguration]): it starts in about a
 * second, and the rate limiter takes its database index from `spring.data.redis.database` rather
 * than the connection details, so a shared server could not keep two contexts' buckets apart.
 *
 * LGTM carries no per-context state anything asserts on; it is shared as it is.
 */
object SharedTestContainers {
    /**
     * Each cached context holds a full Hikari pool (ten connections) open on its own database, and
     * about forty contexts can be cached at once; PostgreSQL's default of 100 would refuse the
     * fourth or fifth context. `fsync=off` restates Testcontainers' own default, which a custom
     * command replaces.
     */
    private const val MAX_CONNECTIONS = 1000

    private val databaseSequence = AtomicInteger()
    private val virtualHostSequence = AtomicInteger()

    private val postgres: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=$MAX_CONNECTIONS")
            .apply { start() }
    }

    private val rabbit: RabbitMQContainer by lazy {
        RabbitMQContainer(DockerImageName.parse(TestContainerImages.RABBITMQ)).apply { start() }
    }

    /**
     * The observability stack - an OpenTelemetry collector, Grafana, Loki, Tempo, Prometheus and
     * Pyroscope in one image - is the heaviest container in the suite, and on a loaded CI runner
     * it can take longer than Testcontainers' default 60-second startup wait to log that it is up.
     * That wait is a ceiling, not a delay: a fast start is still fast.
     */
    val lgtm: LgtmStackContainer by lazy {
        SharedLgtmStackContainer(DockerImageName.parse(TestContainerImages.GRAFANA_OTEL_LGTM))
            .withStartupTimeout(Duration.ofMinutes(3))
            .apply { start() }
    }

    /**
     * HTTP/1.1 because the management API does not speak cleartext HTTP/2: the client's default
     * `h2c` upgrade attempt gets the connection closed under it.
     */
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    /** Creates an empty database on the shared server, for one Spring context. */
    fun newDatabase(): IsolatedPostgresDatabase {
        val name = "context_${databaseSequence.incrementAndGet()}"
        postgres.admin { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
        return IsolatedPostgresDatabase(name)
    }

    /** Creates an empty virtual host on the shared broker, for one Spring context. */
    fun newVirtualHost(): IsolatedRabbitVirtualHost {
        val name = "context_${virtualHostSequence.incrementAndGet()}"
        rabbitManagement("PUT", "/api/vhosts/$name", "{}")
        rabbitManagement(
            "PUT",
            "/api/permissions/$name/${encode(rabbit.adminUsername)}",
            """{"configure":".*","write":".*","read":".*"}""",
        )
        return IsolatedRabbitVirtualHost(name)
    }

    private fun PostgreSQLContainer.admin(block: (Connection) -> Unit) =
        DriverManager.getConnection(jdbcUrl, username, password).use(block)

    private fun rabbitManagement(
        method: String,
        path: String,
        body: String? = null,
    ) {
        val credentials =
            Base64.getEncoder().encodeToString(
                "${rabbit.adminUsername}:${rabbit.adminPassword}".toByteArray(),
            )
        val request =
            HttpRequest
                .newBuilder(URI.create(rabbit.httpUrl + path))
                .header("Authorization", "Basic $credentials")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method(
                    method,
                    body?.let(HttpRequest.BodyPublishers::ofString)
                        ?: HttpRequest.BodyPublishers.noBody(),
                ).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "RabbitMQ management $method $path answered ${response.statusCode()}: " +
                response.body()
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /**
     * One context's database on the shared server: what Spring Boot's own service connection
     * offered for a container of its own (JDBC for the application's pool, and Flyway's separate
     * connection), pointed at that database. Dropped when its context closes, so evicted
     * contexts do not accumulate on the server.
     */
    class IsolatedPostgresDatabase internal constructor(
        private val name: String,
    ) : JdbcConnectionDetails,
        FlywayConnectionDetails,
        AutoCloseable {
        /** The server's own JDBC URL (parameters included), naming this database instead. */
        private val url: String =
            postgres.jdbcUrl.replaceFirst("/${postgres.databaseName}?", "/$name?").also {
                check(it != postgres.jdbcUrl) { "Unexpected JDBC URL shape: ${postgres.jdbcUrl}" }
            }

        override fun getUsername(): String = postgres.username

        override fun getPassword(): String = postgres.password

        override fun getJdbcUrl(): String = url

        override fun getDriverClassName(): String = postgres.driverClassName

        override fun close() {
            runCatching {
                postgres.admin { c ->
                    c.createStatement().use {
                        it.execute(
                            "DROP DATABASE IF EXISTS $name WITH (FORCE)",
                        )
                    }
                }
            }
        }
    }

    /**
     * One context's virtual host on the shared broker, with the broker's own address and admin
     * credentials, as Spring Boot's service connection offered for a broker of its own. Deleted
     * when its context closes.
     */
    class IsolatedRabbitVirtualHost internal constructor(
        private val name: String,
    ) : RabbitConnectionDetails,
        AutoCloseable {
        override fun getUsername(): String = rabbit.adminUsername

        override fun getPassword(): String = rabbit.adminPassword

        override fun getVirtualHost(): String = name

        override fun getAddresses(): List<RabbitConnectionDetails.Address> =
            URI
                .create(
                    rabbit.amqpUrl,
                ).let { listOf(RabbitConnectionDetails.Address(it.host, it.port)) }

        override fun close() {
            runCatching { rabbitManagement("DELETE", "/api/vhosts/$name") }
        }
    }

    /**
     * Spring Boot closes every container bean with its context, which would stop the one LGTM
     * stack every other context is still connected to. This one is never stopped by a context;
     * Ryuk removes it when the JVM exits.
     */
    private class SharedLgtmStackContainer(
        image: DockerImageName,
    ) : LgtmStackContainer(image) {
        override fun stop() = Unit
    }
}
