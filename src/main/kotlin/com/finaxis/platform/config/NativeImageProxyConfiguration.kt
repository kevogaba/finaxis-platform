package com.finaxis.platform.config

import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.target.EmptyTargetSource
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.context.aot.AbstractAotProcessor
import org.springframework.util.ClassUtils

/**
 * Pre-generates the CGLIB proxy classes Spring AOT cannot discover on its own.
 *
 * A native image cannot enhance a class at runtime, so every CGLIB proxy has to exist before the
 * image is built. Spring AOT creates them while processing the context - `preDetermineBeanTypes`
 * asks each auto-proxy creator what a bean's type will be, and the proxy class falls out of that
 * answer - but only for the type it can see. A `@Bean` method that declares an interface return
 * type hides the implementation the proxy actually has to subclass, so no proxy is generated and
 * the image fails on first use of the bean with
 * `UnsupportedOperationException: CGLIB runtime enhancement not supported on native image`.
 *
 * Spring Modulith's `jdbcEventPublicationRepository` is exactly that shape: declared as
 * `EventPublicationRepository`, implemented by a package-private `@Transactional` class. Building
 * the proxy class here produces the same class, under the same generated name, that the auto-proxy
 * creator asks for at runtime, and the AOT engine captures it because it installs its CGLIB class
 * handler for the whole of the AOT context refresh - post-processors included.
 *
 * This runs only while AOT processing is in progress. On a plain JVM the proxy is generated on
 * demand and nothing here is needed.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeImageProxyConfiguration.GeneratedProxyHints::class)
class NativeImageProxyConfiguration {
    /**
     * Generates the proxy classes for implementations Spring AOT never sees by their own type.
     */
    @Bean
    fun interfaceDeclaredProxyClassGenerator(): BeanFactoryPostProcessor =
        BeanFactoryPostProcessor { beanFactory ->
            if (!java.lang.Boolean.getBoolean(AbstractAotProcessor.AOT_PROCESSING)) {
                return@BeanFactoryPostProcessor
            }
            val classLoader = beanFactory.beanClassLoader
            INTERFACE_DECLARED_PROXY_TARGETS
                .filter { ClassUtils.isPresent(it, classLoader) }
                .forEach { proxyClassFor(it, classLoader) }
        }

    /**
     * Registers the pre-generated proxy classes for reflection.
     *
     * Two registrations are needed, and Spring makes both for the proxies it generates itself:
     *
     * - the proxy class, with fields and methods as well as constructors. CGLIB reads
     *   `CGLIB$FACTORY_DATA` off it and calls its callback setters when instantiating, so
     *   constructors alone fail with `MissingReflectionRegistrationError`.
     * - the *target* class's methods. The generated proxy resolves the methods it overrides
     *   reflectively in its static initializer; without them every resolved `Method` is null and
     *   the first call through the proxy dies in `AdvisedSupport$MethodCacheKey` with a
     *   `NullPointerException` that names neither the proxy nor the missing metadata.
     *
     * Both beans are lazy, so neither gap shows up at startup - only at the first outbox tick or
     * event publication.
     */
    class GeneratedProxyHints : RuntimeHintsRegistrar {
        override fun registerHints(
            hints: RuntimeHints,
            classLoader: ClassLoader?,
        ) {
            INTERFACE_DECLARED_PROXY_TARGETS
                .filter { ClassUtils.isPresent(it, classLoader) }
                .forEach {
                    // Asking for the class rather than composing its name: CGLIB numbers generated
                    // classes per classloader, so the index is only `0` in a JVM that has generated
                    // no other proxy for the same target. Whichever of this and the post-processor
                    // runs first generates it; the other gets the same cached class back.
                    hints.reflection().registerType(
                        TypeReference.of(proxyClassFor(it, classLoader)),
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.ACCESS_DECLARED_FIELDS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                    )
                    hints.reflection().registerTypeIfPresent(
                        classLoader,
                        it,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.INVOKE_PUBLIC_METHODS,
                    )
                }
        }
    }

    internal companion object {
        /**
         * Builds the subclass proxy the auto-proxy creator asks for at runtime. CGLIB caches by
         * configuration, so repeated calls return the same class.
         */
        fun proxyClassFor(
            implementationClassName: String,
            classLoader: ClassLoader?,
        ): Class<*> {
            val factory = ProxyFactory()
            factory.isProxyTargetClass = true
            factory.targetSource =
                EmptyTargetSource.forClass(
                    ClassUtils.resolveClassName(implementationClassName, classLoader),
                )
            return factory.getProxyClass(classLoader)
        }

        /**
         * Implementation classes that a `@Bean` method declares only by an interface, and which
         * an auto-proxy creator then proxies at runtime.
         */
        val INTERFACE_DECLARED_PROXY_TARGETS =
            listOf(
                // Advised by Namastack's outbox observability, whose pointcut matches the `Outbox`
                // interface the bean is declared as. Pre-generating the proxy is what keeps that
                // observability in the native image rather than excluding it from the build.
                "io.namastack.outbox.OutboxService",
                // `@Transactional`, and the event publication registry, so it cannot be dropped.
                "org.springframework.modulith.events.jdbc.JdbcEventPublicationRepositoryV2",
            )
    }
}
