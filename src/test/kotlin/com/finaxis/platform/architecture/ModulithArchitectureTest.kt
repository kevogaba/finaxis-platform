package com.finaxis.platform.architecture

import com.finaxis.platform.PlatformApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

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
        ApplicationModules.of(PlatformApplication::class.java).writeDocumentation()
    }
}

private fun ApplicationModules.writeDocumentation() {
    Documenter(this).writeDocumentation()
}
