package com.finaxis.platform.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption

/**
 * The application's production bytecode (`com.finaxis.platform`, test classes excluded), imported
 * once per test JVM and shared by every ArchUnit rule class.
 *
 * Each rule class used to import it in a property initializer, and JUnit makes a new instance of
 * the class for every test method, so a run re-read the whole codebase's class files once per
 * rule - about seventy times, a few seconds each. [JavaClasses] is an immutable snapshot, and every
 * one of those imports asked for exactly this set, so one import answers them all.
 *
 * A rule that needs a different set (the deliberately non-compliant fixtures under `fixtures`)
 * imports it itself.
 */
internal object ProductionClasses {
    val all: JavaClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.finaxis.platform")
    }
}
