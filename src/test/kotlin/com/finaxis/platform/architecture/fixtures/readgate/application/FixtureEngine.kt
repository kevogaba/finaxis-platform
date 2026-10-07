package com.finaxis.platform.architecture.fixtures.readgate.application

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** A concrete type with no stereotype: a configuration class builds it. */
class FixtureEngine {
    /** A read with no authorisation. */
    fun run(): String = hashCode().toString()
}

/** Builds [FixtureEngine] through a `@Bean` method. */
@Configuration(proxyBeanMethods = false)
class FixtureEngineConfiguration {
    /** The bean. */
    @Bean
    fun fixtureEngine(): FixtureEngine = FixtureEngine()
}
