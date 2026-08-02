package com.finaxis.platform.architecture

import com.finaxis.platform.PlatformApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter
import java.io.File
import kotlin.test.assertTrue

/**
 * Spring Modulith verification for the application module graph discovered from the main
 * application package.
 */
class ModulithArchitectureTest {
    @Test
    fun `verifies Spring Modulith module boundaries`() {
        ApplicationModules.of(PlatformApplication::class.java).verify()
    }

    @Test
    fun `writes Spring Modulith documentation`() {
        val outputDir = File("build/spring-modulith-docs")
        outputDir.deleteRecursively()

        ApplicationModules.of(PlatformApplication::class.java).writeDocumentation()

        assertTrue(outputDir.isDirectory, "Documenter should create $outputDir")
        val written = outputDir.listFiles()?.map { it.name }.orEmpty()
        assertTrue("components.puml" in written, "expected an overall components diagram")
        assertTrue("all-docs.adoc" in written, "expected a combined documentation file")
        assertTrue(
            written.any { it.startsWith("module-") && it.endsWith(".adoc") },
            "expected at least one per-module documentation file",
        )
    }
}

private fun ApplicationModules.writeDocumentation() {
    Documenter(this).writeDocumentation()
}
