package com.finaxis.platform.config

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.TypeReference
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.util.ClassUtils
import kotlin.test.Test
import kotlin.test.assertTrue

class NativeImageProxyConfigurationTests {
    private val classLoader = javaClass.classLoader

    @Test
    fun `every declared proxy target is still on the classpath`() {
        // The list is matched by class name, so an upstream rename would silently turn the
        // pre-generation into a no-op and only surface as a failed native image at runtime.
        NativeImageProxyConfiguration.INTERFACE_DECLARED_PROXY_TARGETS.forEach {
            assertTrue(
                ClassUtils.isPresent(it, classLoader),
                "$it is no longer on the classpath; check whether its bean is still proxied and " +
                    "update NativeImageProxyConfiguration",
            )
        }
    }

    @Test
    fun `each generated proxy is registered with the members cglib reads`() {
        // Constructors alone are not enough: CGLIB reads CGLIB$FACTORY_DATA off the class and
        // calls its callback setters when it instantiates the proxy.
        val hints =
            RuntimeHints().also {
                NativeImageProxyConfiguration.GeneratedProxyHints().registerHints(it, classLoader)
            }
        NativeImageProxyConfiguration.INTERFACE_DECLARED_PROXY_TARGETS.forEach {
            val proxyName = NativeImageProxyConfiguration.proxyClassFor(it, classLoader).name
            listOf(
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.ACCESS_DECLARED_FIELDS,
                MemberCategory.INVOKE_DECLARED_METHODS,
            ).forEach { category ->
                assertTrue(
                    RuntimeHintsPredicates
                        .reflection()
                        .onType(TypeReference.of(proxyName))
                        .withMemberCategory(category)
                        .test(hints),
                    "expected $proxyName to be registered with $category",
                )
            }
            // The proxy resolves the methods it overrides off the target class reflectively.
            assertTrue(
                RuntimeHintsPredicates
                    .reflection()
                    .onType(TypeReference.of(it))
                    .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)
                    .test(hints),
                "expected $it to be registered with its methods",
            )
        }
    }

    @Test
    fun `every declared proxy target can be proxied by subclassing`() {
        NativeImageProxyConfiguration.INTERFACE_DECLARED_PROXY_TARGETS.forEach {
            val target = ClassUtils.resolveClassName(it, classLoader)
            assertTrue(
                target.isAssignableFrom(
                    NativeImageProxyConfiguration.proxyClassFor(it, classLoader),
                ),
                "expected a subclass proxy of ${target.name}",
            )
        }
    }
}
