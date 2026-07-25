package com.finaxis.platform

import com.finaxis.platform.common.audit.AuditPermissionGuard
import com.finaxis.platform.common.web.idempotency.IdempotencyBodyFilter
import com.finaxis.platform.common.web.idempotency.IdempotencyKeyFilter
import com.finaxis.platform.common.web.scanImplementationsOf
import com.finaxis.platform.common.web.scanPlatformTypes
import com.finaxis.platform.common.web.scanRestControllers
import com.finaxis.platform.common.web.scanTypesWithMethodAnnotation
import com.finaxis.platform.lifecycle.PermissionGuard
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest
class PlatformApplicationTests
    @Autowired
    constructor(
        private val applicationContext: ApplicationContext,
    ) {
        @Test
        fun contextLoads() {
            assertTrue(true)
        }

        @Test
        fun `all scanned controllers and permission guards are wired`() {
            assertResolvable(scanRestControllers())
            assertResolvable(scanImplementationsOf(PermissionGuard::class.java))
            assertResolvable(scanImplementationsOf(AuditPermissionGuard::class.java))
        }

        @Test
        fun `idempotency infrastructure and query adapters are wired`() {
            val idempotencyComponents =
                scanPlatformTypes().filter { type ->
                    type.packageName.startsWith("com.finaxis.platform.common.web.idempotency") &&
                        AnnotatedElementUtils.hasAnnotation(type, Component::class.java)
                }
            val queryAdapters =
                scanPlatformTypes().filter { type ->
                    ".adapter.outbound.query" in type.packageName &&
                        AnnotatedElementUtils.hasAnnotation(type, Component::class.java)
                }

            assertResolvable(idempotencyComponents)
            assertRegisteredFilter(IdempotencyKeyFilter::class.java)
            assertRegisteredFilter(IdempotencyBodyFilter::class.java)
            assertResolvable(queryAdapters)
        }

        @Test
        fun `event listeners and JobRunr handlers are wired`() {
            assertResolvable(scanTypesWithMethodAnnotation(RabbitListener::class.java))
            assertResolvable(scanImplementationsOf(JobRequestHandler::class.java))
        }

        private fun assertResolvable(types: Collection<Class<*>>) {
            assertTrue(types.isNotEmpty())
            types.forEach { type -> applicationContext.getBean(type) }
        }

        private fun assertRegisteredFilter(type: Class<*>) {
            val filters =
                applicationContext.getBeansOfType(FilterRegistrationBean::class.java).values
            assertTrue(filters.any { registration -> type.isInstance(registration.filter) })
        }
    }
