package com.finaxis.platform.architecture.fixtures.readgate.application

import org.springframework.stereotype.Component

/** A concrete `@Component` that reads with no authorisation, and is named like no port. */
@Component
class FixtureBalanceFinder {
    /** A read with no authorisation. */
    fun find(): String = hashCode().toString()
}
