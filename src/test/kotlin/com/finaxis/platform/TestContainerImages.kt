package com.finaxis.platform

/**
 * Testcontainer images pinned to the same explicit tags used by compose.yaml.
 */
object TestContainerImages {
    const val GRAFANA_OTEL_LGTM = "grafana/otel-lgtm:0.28.0"
    const val GREENMAIL = "greenmail/standalone:2.1.13"
    const val POSTGRES = "postgres:18.4"
    const val RABBITMQ = "rabbitmq:4.3.2-management"
    const val REDIS = "redis:8.8.0"
}
