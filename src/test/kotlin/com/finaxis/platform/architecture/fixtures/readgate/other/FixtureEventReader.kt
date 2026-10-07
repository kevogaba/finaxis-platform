package com.finaxis.platform.architecture.fixtures.readgate.other

/** A read port that is neither in an `application.port` package nor named like a store. */
interface FixtureEventReader {
    /** Reads with no authorisation of any kind. */
    fun next(): String?
}
