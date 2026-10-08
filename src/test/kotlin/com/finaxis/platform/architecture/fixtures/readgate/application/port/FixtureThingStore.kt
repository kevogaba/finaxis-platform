package com.finaxis.platform.architecture.fixtures.readgate.application.port

/** A fixture store port: web adapters must never depend on one. */
interface FixtureThingStore {
    /** Reads with no authorisation of any kind. */
    fun find(): String?
}
