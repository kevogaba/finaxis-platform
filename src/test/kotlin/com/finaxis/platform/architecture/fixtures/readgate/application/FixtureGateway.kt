package com.finaxis.platform.architecture.fixtures.readgate.application

/** An interface that is named like no port and no service. */
interface FixtureGateway {
    /** A read with no authorisation. */
    fun fetch(): String?
}
