package com.finaxis.platform.architecture.fixtures.readgate.application

/** Named like a service, but it can authorise nothing: no guard, no marked method. */
class UnguardedFixtureService {
    /** A read with no authorisation. */
    fun peek(): String = hashCode().toString()
}
