package com.finaxis.platform.config

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.TypeReference
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import kotlin.test.Test
import kotlin.test.assertTrue

class HibernateValidatorHintsTests {
    private val hints =
        RuntimeHints().also {
            HibernateValidatorHints().registerHints(it, javaClass.classLoader)
        }

    @Test
    fun `registers the validators behind the constraints the api uses`() {
        listOf(
            "bv.NotNullValidator",
            "bv.notempty.NotEmptyValidatorForCharSequence",
            "bv.size.SizeValidatorForCharSequence",
        ).forEach { assertValidatorRegistered(it) }
    }

    private fun assertValidatorRegistered(relativeName: String) {
        val name = "${HibernateValidatorHints.BUILT_IN_VALIDATOR_PACKAGE}.$relativeName"
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
