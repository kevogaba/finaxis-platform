package com.finaxis.platform.config

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingProperties
import org.springframework.boot.opentelemetry.autoconfigure.OtlpProperties
import org.springframework.boot.opentelemetry.autoconfigure.logging.otlp.OtlpLoggingProperties
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import kotlin.test.assertEquals

/**
 * The OTLP exporters' endpoints as `application.yaml` configures them: Spring Boot 4.2's shared
 * `management.opentelemetry.otlp.endpoint`, which each exporter completes with its own path,
 * resolves to the same three URLs the per-signal endpoints used to name, and a per-signal value
 * (what an `OTEL_EXPORTER_OTLP_<SIGNAL>_ENDPOINT` variable maps onto) still wins for its signal.
 */
class OtlpEndpointConfigurationTests {
    @Test
    fun `the shared endpoint resolves to each exporter's local default`() {
        val endpoints = Endpoints.of(applicationYaml())

        assertEquals("http://localhost:4318/v1/traces", endpoints.traces)
        assertEquals("http://localhost:4318/v1/logs", endpoints.logs)
        assertEquals("http://localhost:4318/v1/metrics", endpoints.metrics)
    }

    @Test
    fun `a per-signal endpoint overrides the shared one for that signal only`() {
        val environment = applicationYaml()
        environment.propertySources.addFirst(
            MapPropertySource(
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                mapOf(
                    "management.opentelemetry.tracing.export.otlp.endpoint" to
                        "http://collector:4318/v1/traces",
                ),
            ),
        )
        val endpoints = Endpoints.of(environment)

        assertEquals("http://collector:4318/v1/traces", endpoints.traces)
        assertEquals("http://localhost:4318/v1/logs", endpoints.logs)
        assertEquals("http://localhost:4318/v1/metrics", endpoints.metrics)
    }

    private fun applicationYaml(): StandardEnvironment =
        StandardEnvironment().apply {
            propertySources.addLast(
                YamlPropertySourceLoader()
                    .load("application", FileSystemResource("src/main/resources/application.yaml"))
                    .first(),
            )
        }

    /** What Spring Boot's OTLP connection details resolve from the bound properties. */
    private data class Endpoints(
        val traces: String?,
        val logs: String?,
        val metrics: String?,
    ) {
        companion object {
            fun of(environment: StandardEnvironment): Endpoints {
                val binder = Binder.get(environment)
                val shared =
                    binder.bindOrCreate("management.opentelemetry.otlp", OtlpProperties::class.java)
                val tracing =
                    binder.bindOrCreate(
                        "management.opentelemetry.tracing.export.otlp",
                        OtlpTracingProperties::class.java,
                    )
                val logging =
                    binder.bindOrCreate(
                        "management.opentelemetry.logging.export.otlp",
                        OtlpLoggingProperties::class.java,
                    )
                val metrics =
                    binder.bindOrCreate(
                        "management.otlp.metrics.export",
                        OtlpMetricsProperties::class.java,
                    )
                return Endpoints(
                    traces = shared.resolveEndpoint(tracing.endpoint, "v1/traces"),
                    logs = shared.resolveEndpoint(logging.endpoint, "v1/logs"),
                    metrics = shared.resolveEndpoint(metrics.url, "v1/metrics"),
                )
            }
        }
    }
}
