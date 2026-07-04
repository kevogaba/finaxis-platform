package com.finaxis.platform

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<PlatformApplication>().with(TestcontainersConfiguration::class).run(*args)
}
