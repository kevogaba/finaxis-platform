package com.finaxis.platform.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Documentation-convention rules binding the module graph to the BIAN semantic map.
 *
 * Every Spring Modulith application module states its BIAN Service Domain mapping in its own
 * package documentation, so `docs/architecture/bian-service-landscape.md` cannot silently drift
 * from the modules that actually exist. A new financial module cannot merge without declaring
 * whether it maps to a Service Domain or is deliberately platform-specific.
 *
 * See `docs/adr/0017-bian-semantic-reference-architecture.md`.
 */
class BianModuleMappingTests {
    private val moduleDescriptors: List<File> =
        DESCRIPTOR_ROOT
            .walkTopDown()
            .filter { it.isFile && it.name == DESCRIPTOR_NAME }
            .filter { APPLICATION_MODULE.containsMatchIn(it.readText()) }
            .sortedBy { it.path }
            .toList()

    @Test
    fun `every production package is either a declared module or a documented exemption`() {
        // Discovery must not start from the descriptors themselves. Doing so made this suite
        // circular: a new top-level module that forgot its package-info.java was excluded from
        // `moduleDescriptors` by construction, so it needed neither a BIAN line nor a landscape
        // section, and the floor of five was still satisfied by the existing modules. The whole
        // stated purpose is that a new financial module cannot merge without declaring its mapping.
        //
        // So the module set is derived from the production package layout instead, and anything
        // without a descriptor must be named here with a reason.
        val packages =
            SOURCE_ROOTS
                .map { File(it) }
                .filter { it.isDirectory }
                .flatMap { it.listFiles()?.filter(File::isDirectory).orEmpty() }
                .map { it.name }
                .toSortedSet()

        val declared = moduleDescriptors.map { it.parentFile.name }.toSet()
        val undeclared = packages - declared - NON_MODULE_PACKAGES

        assertTrue(
            undeclared.isEmpty(),
            "these production packages are neither @ApplicationModule descriptors nor listed in " +
                "NON_MODULE_PACKAGES: $undeclared. Add a package-info.java with an " +
                "@ApplicationModule and a BIAN: line, or record why the package is not a module",
        )
        assertTrue(
            moduleDescriptors.isNotEmpty(),
            "no descriptors were found at all under $DESCRIPTOR_ROOT, so the rules below would " +
                "pass vacuously - check the source layout rather than deleting this guard",
        )
    }

    @Test
    fun `every application module declares a BIAN mapping line`() {
        val missing =
            moduleDescriptors
                .filterNot { declaresBianMapping(it.readText()) }
                .map { it.path }

        assertTrue(
            missing.isEmpty(),
            "these @ApplicationModule descriptors have no `BIAN:` documentation line: $missing. " +
                "Add `BIAN: <Service Domain> (adopted|adapted)` or `BIAN: none - <reason>` to " +
                "the package Javadoc and record the mapping in $LANDSCAPE_DOC",
        )
    }

    @Test
    fun `every application module has a section in the BIAN landscape document`() {
        val landscape = File(LANDSCAPE_DOC)
        assertTrue(landscape.isFile, "expected the BIAN landscape document at $LANDSCAPE_DOC")

        val landscapeText = landscape.readText()
        val unmapped =
            moduleDescriptors
                .map { it.parentFile.name }
                .filterNot { HEADING.matcher(landscapeText, it) }

        assertTrue(
            unmapped.isEmpty(),
            "modules with no `### <module>` section in $LANDSCAPE_DOC: $unmapped",
        )
    }

    /**
     * Whether the descriptor declares a well-formed BIAN mapping.
     *
     * Reads the whole `BIAN:` paragraph rather than its first line: a mapping that names several
     * Service Domains wraps, so the `(adopted)`/`(adapted)` qualifier routinely lands on a
     * continuation line. A single-line check rejects those, which is a false negative on a
     * correctly written descriptor.
     */
    private fun declaresBianMapping(source: String): Boolean {
        val start = BIAN_MARKER.find(source) ?: return false
        val paragraph =
            source
                .substring(start.range.first)
                .lineSequence()
                .takeWhile { !it.contains("*/") && !it.trimStart().startsWith("@") }
                .joinToString(" ")
        return QUALIFIED_MAPPING.containsMatchIn(paragraph)
    }

    /** Matches a complete `### <module>` heading, so `account` does not match `### accounting`. */
    private object HEADING {
        fun matcher(
            text: String,
            module: String,
        ): Boolean =
            Regex("""^###\s+\Q$module\E\s*$""", RegexOption.MULTILINE).containsMatchIn(text)
    }

    private companion object {
        val DESCRIPTOR_ROOT = File("src/main/java/com/finaxis/platform")
        const val DESCRIPTOR_NAME = "package-info.java"

        /**
         * Matches the annotation whether written fully qualified or imported. Matching only the
         * fully qualified form let a module written in the idiomatic Java style opt out of all
         * three rules silently, which is the opposite of this suite's purpose.
         */
        val APPLICATION_MODULE =
            Regex("""@(org\.springframework\.modulith\.)?ApplicationModule\b""")
        const val LANDSCAPE_DOC = "docs/architecture/bian-service-landscape.md"

        /** Production source roots whose immediate subdirectories are candidate modules. */
        val SOURCE_ROOTS =
            listOf("src/main/kotlin/com/finaxis/platform", "src/main/java/com/finaxis/platform")

        /**
         * Packages that are deliberately not `@ApplicationModule`s, each for a stated reason.
         *
         * `config` is infrastructure wiring rather than a domain module, and `common` is the
         * shared kernel every module depends on. Both are recorded in CLAUDE.md. Adding to this
         * set is a decision, which is the point of requiring it.
         */
        val NON_MODULE_PACKAGES = setOf("common", "config")

        /** Matches a Javadoc line such as ` * BIAN: Financial Accounting (adapted) - ...`. */
        val BIAN_MARKER = Regex("""^\s*\*\s*(<p>)?BIAN:""", RegexOption.MULTILINE)

        /** A named Service Domain qualified `(adopted)`/`(adapted)`, or a reasoned `none`. */
        val QUALIFIED_MAPPING =
            Regex("""BIAN:\s*(none\s*[-\u2014]\s*\S|.*?\((adopted|adapted)\))""")
    }
}
