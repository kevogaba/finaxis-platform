package com.finaxis.platform.config

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.TypeReference

/**
 * Registers the types Redis stores through Java serialization.
 *
 * Both Redis-backed features here use `JdkSerializationRedisSerializer`: the
 * `iam.effective-permissions` cache writes the resolved permission set, and the HTTP session
 * writes the active-organisation context and the security context beside it. A native image can
 * serialize only the types it was told about, and neither the cache nor the session is touched
 * during startup, so the gap surfaces as a 500 on the first authorized request rather than as
 * anything at build time:
 *
 * ```
 * SerializationException: Cannot serialize
 * Caused by: UnsupportedFeatureError: SerializationConstructorAccessor class not found
 *   for declaringClass: java.util.HashSet
 * ```
 *
 * The set was taken from a GraalVM tracing-agent run over the full smoke path rather than guessed,
 * because a missing entry fails one endpoint rather than the build. See
 * `docs/operations/native-image-deployment.md` for how to repeat that run.
 *
 * Registered through `reflection().withJavaSerialization(true)` rather than `serialization()`.
 * Spring deprecated the whole Java-serialization hint family - `SerializationHints`,
 * `JavaSerializationHint` and their predicates - in 7.0.6 with `forRemoval = true`. The two produce
 * **identical** native metadata: `ReflectionHintsAttributes` reads the deprecated hints and the
 * `TypeHint.hasJavaSerialization()` flag through the same `handleSerializable` path, emitting
 * `"serializable": true` into `reflect-config.json` either way. There is no separate
 * serialization writer left in `org.springframework.aot.nativex` to write anything else.
 */
class RedisSerializationHints : RuntimeHintsRegistrar {
    override fun registerHints(
        hints: RuntimeHints,
        classLoader: ClassLoader?,
    ) {
        SERIALIZED_TYPES.forEach { type ->
            hints.reflection().registerType(TypeReference.of(type)) {
                it.withJavaSerialization(true)
            }
        }
    }

    internal companion object {
        /** Application types the HTTP session carries. */
        private val APPLICATION_TYPES =
            listOf(
                "com.finaxis.platform.iam.application.context.ActiveOrganisationContext",
                "com.finaxis.platform.iam.application.context.AppPrincipal",
                "com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken",
            )

        /**
         * Spring Security persists the authentication into the session it finds, so its context
         * types travel with the active-organisation context.
         */
        private val SECURITY_TYPES =
            listOf(
                "org.springframework.security.authentication.AbstractAuthenticationToken",
                "org.springframework.security.core.authority.SimpleGrantedAuthority",
                "org.springframework.security.core.context.SecurityContextImpl",
            )

        /**
         * The permission cache's own value, plus the marker Spring caches use for a null hit.
         *
         * `EffectivePermissionResolver.resolve` ends in `allowed.minus(denied)`, and Kotlin's
         * `Set.minus` short-circuits when nothing is denied: it returns `this.toSet()`, which is
         * `emptySet()` for no permissions and `setOf(single)` for exactly one. Those are
         * `kotlin.collections.EmptySet` and `java.util.Collections$SingletonSet`, **not** the
         * `LinkedHashSet` the multi-permission path produces - so a membership with zero or one
         * effective permission serializes a type the image was never told about, and only that
         * user's first authorized request fails. The compact cases are the ones a tracing-agent run
         * over a well-populated tenant is least likely to reach, which is exactly why they are
         * listed rather than discovered.
         */
        private val CACHE_TYPES =
            listOf(
                "java.util.HashSet",
                "java.util.LinkedHashSet",
                $$"java.util.Collections$SingletonSet",
                "kotlin.collections.EmptySet",
                "org.springframework.cache.support.NullValue",
            )

        /** JDK types reached from the graphs above. */
        private val JDK_TYPES =
            listOf(
                "java.lang.Integer",
                "java.lang.Long",
                "java.lang.Number",
                "java.lang.String",
                "java.util.ArrayList",
                $$"java.util.Collections$UnmodifiableCollection",
                $$"java.util.Collections$UnmodifiableList",
                $$"java.util.Collections$UnmodifiableRandomAccessList",
                "java.util.UUID",
            )

        val SERIALIZED_TYPES = APPLICATION_TYPES + SECURITY_TYPES + CACHE_TYPES + JDK_TYPES
    }
}
