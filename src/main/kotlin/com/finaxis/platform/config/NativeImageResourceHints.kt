package com.finaxis.platform.config

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * Registers the classpath resources that third-party libraries read at runtime but do not declare
 * in their own GraalVM reachability metadata.
 *
 * A native image embeds only the resources it was told about, and a missing one is not a build
 * failure: it surfaces the first time the application asks for it, which for all of these is
 * during startup or the first background job. Spring's own AOT processing covers Spring-managed
 * resources - `db/migration` for Flyway among them - so this covers the rest.
 */
class NativeImageResourceHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        hints.resources().apply {
            // Namastack's JDBC outbox starter initialises its tables from a bundled script chosen
            // by database vendor. Without this the DSLContext bean fails with "No schema scripts
            // found at location 'classpath:schema/postgres/outbox-tables.sql'".
            registerPattern("schema/postgres/outbox-tables.sql")

            // Spring Modulith's JDBC event publication registry creates its table the same way.
            registerPattern("org/springframework/modulith/events/jdbc/schemas/*/schema-*.sql")

            // JobRunr lists its migration directories on the classpath and applies every `.sql`
            // child, so both the directories and their contents have to be present.
            registerPattern("org/jobrunr/storage/sql/common/migrations/*")
            registerPattern("org/jobrunr/storage/sql/postgres/migrations/*")

            // The FreeMarker templates the notifications module renders for outbound email. They
            // are only touched when an email is actually sent, so a missing hint would fail a
            // background job rather than startup.
            registerPattern("templates/email/*")
        }
    }
}
