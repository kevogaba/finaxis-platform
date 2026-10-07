package com.finaxis.platform.architecture.fixtures.readgate.application

/** An application service that is not a query service and carries no marker. */
class UnmarkedApplicationFixtureService {
    /** A read with no authorisation. */
    fun peek(): String = hashCode().toString()
}
