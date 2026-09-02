package com.finaxis.platform.config

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.TypeReference
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JBossLoggingHintsTests {
    private val hints =
        RuntimeHints().also {
            JBossLoggingHints().registerHints(it, javaClass.classLoader)
        }

    @Test
    fun `registers the hibernate validator logger implementation`() {
        assertGeneratedTypeRegistered("org.hibernate.validator.internal.util.logging.Log_\$logger")
    }

    @Test
    fun `registers the hibernate validator message bundle implementation`() {
        assertGeneratedTypeRegistered(
            "org.hibernate.validator.internal.util.logging.Messages_\$bundle",
        )
    }

    @Test
    fun `registers every RESTEasy generated logger the keycloak admin client reaches`() {
        // The FINAXIS_KEYCLOAK_ADMIN_ENABLED=true image includes RESTEasy, and an earlier revision
        // registered none of these - so that build compiled cleanly and failed on its first admin
        // call. Class.forName inside the helper is what keeps this honest: these jars are on the
        // classpath whether or not the gateway bean is conditioned in, so a name that stopped
        // resolving would fail here rather than silently registering nothing.
        listOf(
            "org.jboss.resteasy.resteasy_jaxrs.i18n.LogMessages_\$logger",
            "org.jboss.resteasy.resteasy_jaxrs.i18n.Messages_\$bundle",
            "org.jboss.resteasy.client.jaxrs.i18n.LogMessages_\$logger",
            "org.jboss.resteasy.client.jaxrs.i18n.Messages_\$bundle",
            "org.jboss.resteasy.plugins.providers.jackson._private.JacksonLogger_\$logger",
            "org.jboss.resteasy.plugins.providers.jaxb.i18n.LogMessages_\$logger",
            "org.jboss.resteasy.plugins.providers.jaxb.i18n.Messages_\$bundle",
            "org.jboss.resteasy.plugins.providers.multipart.i18n.LogMessages_\$logger",
            "org.jboss.resteasy.plugins.providers.multipart.i18n.Messages_\$bundle",
        ).forEach(::assertGeneratedTypeRegistered)
    }

    private fun assertGeneratedTypeRegistered(name: String) {
        // A typo in the name would register nothing and still pass a weaker assertion, so the
        // class has to exist as well as be hinted.
        assertNotNull(Class.forName(name), "expected $name on the test classpath")
        assertTrue(
            RuntimeHintsPredicates
                .reflection()
                .onType(TypeReference.of(name))
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .test(hints),
            "expected $name to be registered with its constructors",
        )
    }
}
