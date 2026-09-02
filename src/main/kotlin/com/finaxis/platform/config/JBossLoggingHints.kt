package com.finaxis.platform.config

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * Registers the logger and message-bundle implementations JBoss Logging loads by name.
 *
 * `Logger.getMessageLogger` resolves an annotated interface to a generated `<Interface>_$logger`
 * class through `Class.forName`, and `getMessageBundle` does the same for `<Interface>_$bundle`.
 * Neither name appears anywhere in the bytecode, so a native image drops both and the lookup fails
 * at startup with `Invalid logger interface ... (implementation not found)` - taking Hibernate
 * Validator, and with it every `@ConfigurationProperties` binding, down with it.
 *
 * Both users on the classpath are registered: Hibernate Validator's pair, and the RESTEasy set that
 * arrives with `keycloak-admin-client`.
 *
 * An earlier revision registered only Hibernate Validator, on the reasoning that RESTEasy's gateway
 * is conditioned out of the image so forcing its classes in "would only add weight" - and left a
 * note that `FINAXIS_KEYCLOAK_ADMIN_ENABLED=true` "needs them listed here too". That reasoning does
 * not hold, and the note describes a supported build that does not work. `@ConditionalOnProperty`
 * conditions out the **bean**, not the dependency: the RESTEasy jars are on the image classpath
 * either way. So the weight was being paid already, while the one build variant that needs the
 * hints was the variant that failed - on its first admin call, with an implementation-not-found
 * error, after building cleanly.
 *
 * The names are the generated implementations JBoss Logging resolves by `Class.forName`, read out
 * of the jars rather than guessed. `registerTypeIfPresent` keeps each one a no-op if a future
 * dependency change drops the jar that carries it.
 */
class JBossLoggingHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        generatedTypes().forEach { type ->
            hints.reflection().registerTypeIfPresent(
                classLoader,
                type,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.ACCESS_DECLARED_FIELDS,
            )
        }
    }

    /**
     * The generated names, written out in full rather than assembled from a package constant.
     *
     * Multi-dollar literals, so the `$` that separates the generated suffix is a plain character
     * instead of `\$`. That keeps each entry byte-identical to the binary name JBoss Logging asks
     * `Class.forName` for - which matters here more than brevity, because the only way to check an
     * entry is to search for that exact name, and an escape breaks the search.
     */
    private fun generatedTypes(): List<String> = HIBERNATE_VALIDATOR_TYPES + RESTEASY_TYPES

    private companion object {
        /** Needed by every image: Hibernate Validator backs `@ConfigurationProperties` binding. */
        val HIBERNATE_VALIDATOR_TYPES =
            listOf(
                $$"org.hibernate.validator.internal.util.logging.Log_$logger",
                $$"org.hibernate.validator.internal.util.logging.Messages_$bundle",
            )

        /**
         * RESTEasy's set, needed by an image built with `FINAXIS_KEYCLOAK_ADMIN_ENABLED=true`.
         *
         * One per RESTEasy module the Keycloak admin client pulls in: the JAX-RS core, the client,
         * and the Jackson, JAXB and multipart providers it registers. Each module generates its own
         * `LogMessages_$logger`, and most also a `Messages_$bundle`; the Jackson provider is the
         * exception, generating only a logger.
         */
        val RESTEASY_TYPES =
            listOf(
                $$"org.jboss.resteasy.resteasy_jaxrs.i18n.LogMessages_$logger",
                $$"org.jboss.resteasy.resteasy_jaxrs.i18n.Messages_$bundle",
                $$"org.jboss.resteasy.client.jaxrs.i18n.LogMessages_$logger",
                $$"org.jboss.resteasy.client.jaxrs.i18n.Messages_$bundle",
                $$"org.jboss.resteasy.plugins.providers.jackson._private.JacksonLogger_$logger",
                $$"org.jboss.resteasy.plugins.providers.jaxb.i18n.LogMessages_$logger",
                $$"org.jboss.resteasy.plugins.providers.jaxb.i18n.Messages_$bundle",
                $$"org.jboss.resteasy.plugins.providers.multipart.i18n.LogMessages_$logger",
                $$"org.jboss.resteasy.plugins.providers.multipart.i18n.Messages_$bundle",
            )
    }
}
