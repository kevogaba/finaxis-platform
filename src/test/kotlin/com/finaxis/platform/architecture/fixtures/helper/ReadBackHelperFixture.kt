package com.finaxis.platform.architecture.fixtures.helper

/** Fixture carrying the retired permission-free read-back name. */
class ReadBackHelperFixture {
    /** The violation: a method named like the five helpers ADR 0030 deleted. */
    fun getThingAfterAuthorizedMutation(): String = hashCode().toString()
}
