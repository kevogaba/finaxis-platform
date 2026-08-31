package com.finaxis.platform.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Programmatic enforcement of ADR 0019's arithmetic rules.
 *
 * `BigDecimal.divide` without an explicit scale and `RoundingMode` throws `ArithmeticException` on
 * a non-terminating expansion — one third of a cent is enough — which turns a rounding-policy
 * question into a production incident on a posting path. The ADR bans it, and this is what makes
 * the ban real rather than aspirational.
 *
 * **Why a source scan rather than Detekt's `ForbiddenMethodCall`.** That rule matches fully
 * qualified signatures and therefore needs type resolution, which requires the Detekt tasks to be
 * given a compile classpath. This build does not configure one, so activating the rule looks like
 * enforcement while silently matching nothing — verified by adding a banned call and watching
 * Detekt stay green. A textual scan is cruder but actually fires, and it is honest about what it
 * checks.
 *
 * The scan is deliberately conservative: it flags a `.divide(` call whose argument list mentions
 * neither `RoundingMode` nor a scale-carrying `MathContext`. That can be defeated by aliasing, so
 * it is a floor rather than a proof — but it catches the overload a developer reaches for by
 * default, which is the actual failure mode.
 */
class MoneyArithmeticRuleTests {
    @Test
    fun `no production code divides a BigDecimal without an explicit rounding mode`() {
        val offenders =
            productionKotlinFiles()
                .flatMap { file ->
                    file
                        .readLines()
                        .withIndex()
                        .filter { (_, line) -> isUnsafeDivide(line) }
                        .map { (index, line) -> "${file.name}:${index + 1} ${line.trim()}" }
                }.toList()

        assertEquals(
            emptyList(),
            offenders,
            "BigDecimal.divide needs an explicit scale and RoundingMode (ADR 0019): $offenders",
        )
    }

    @Test
    fun `the scan looks at a source tree it can actually see`() {
        // Guards the rule above against passing because it found no files - a wrong working
        // directory or a moved source root would otherwise read as compliance.
        assertTrue(
            productionKotlinFiles().count() > MINIMUM_EXPECTED_FILES,
            "expected the production Kotlin sources, but the scan found almost nothing",
        )
    }

    private fun productionKotlinFiles(): Sequence<File> =
        File(PRODUCTION_SOURCE_ROOT)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }

    private fun isUnsafeDivide(line: String): Boolean {
        val code = line.substringBefore("//").trim()
        if (!code.contains(".divide(")) {
            return false
        }
        return SAFE_DIVIDE_MARKERS.none { code.contains(it) }
    }

    private companion object {
        const val PRODUCTION_SOURCE_ROOT = "src/main/kotlin"

        /** A `divide` carrying either of these has been given an explicit rounding policy. */
        val SAFE_DIVIDE_MARKERS = listOf("RoundingMode", "MathContext")

        /** The repository has hundreds of production Kotlin files; this is a floor, not a count. */
        const val MINIMUM_EXPECTED_FILES = 50
    }
}
