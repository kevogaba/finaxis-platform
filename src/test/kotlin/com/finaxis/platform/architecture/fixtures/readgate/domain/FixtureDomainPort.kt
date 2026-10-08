package com.finaxis.platform.architecture.fixtures.readgate.domain

/** A read port declared in a domain package, as `LifecyclePrerequisites` is. */
interface FixtureDomainPort {
    /** Reads with no caller and no authorisation. */
    fun organisationState(): String?
}
