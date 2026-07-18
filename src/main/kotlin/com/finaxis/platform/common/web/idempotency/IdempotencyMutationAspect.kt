package com.finaxis.platform.common.web.idempotency

import com.finaxis.platform.common.web.api.ApiJsonCodec
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.core.Ordered
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

/** Applies atomic durable idempotency around annotated MVC mutation methods. */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class IdempotencyMutationAspect(
    private val executor: IdempotencyExecutor,
    private val scopeResolver: MutationScopeResolver,
    private val requestHasher: CanonicalRequestHasher,
    private val apiJsonCodec: ApiJsonCodec,
    replayHandlers: List<IdempotencyReplayHandler>,
) {
    private val handlers = replayHandlers.associateBy(IdempotencyReplayHandler::mode)

    /** Executes the annotated mutation once and adapts a durable or replayed response. */
    @Around(
        "@within(org.springframework.web.bind.annotation.RestController) && " +
            "@annotation(idempotentMutation)",
    )
    fun around(
        joinPoint: ProceedingJoinPoint,
        idempotentMutation: IdempotentMutation,
    ): Any? {
        val attributes =
            RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
        val request = attributes.request
        val idempotencyRequest =
            request.getAttribute(IdempotencyKeyFilter.IDEMPOTENCY_REQUEST_ATTRIBUTE)
                as? HttpServletRequest
                ?: error("Idempotency key filter did not wrap the mutation request")
        val servletResponse = attributes.response
        val key =
            request.getAttribute(IdempotencyKeyFilter.IDEMPOTENCY_KEY_ATTRIBUTE) as? UUID
                ?: error("Idempotency key filter did not prepare the mutation request")
        var originalResult: Any? = null
        val response =
            executor.execute(
                scope = scopeResolver.resolve(idempotencyRequest),
                key = key,
                fingerprint = requestHasher.fingerprint(idempotencyRequest),
            ) {
                val result = joinPoint.proceed()
                originalResult = result
                toDurableResponse(joinPoint, result)
            }
        if (response.replayed) {
            servletResponse?.setHeader(
                IdempotencyKeyFilter.IDEMPOTENCY_REPLAYED_HEADER,
                "true",
            )
        }
        return when (idempotentMutation.replayMode) {
            IdempotencyReplayMode.EXACT_RESPONSE -> {
                restoreExactResponse(joinPoint, response, originalResult, servletResponse)
            }

            IdempotencyReplayMode.REISSUE_CONTEXT_TOKEN -> {
                restoreSensitiveResponse(response, idempotencyRequest, servletResponse)
            }
        }
    }

    private fun toDurableResponse(
        joinPoint: ProceedingJoinPoint,
        result: Any?,
    ): IdempotencyResponse {
        val response =
            when (result) {
                is IdempotencyReplayResponse -> {
                    result
                }

                is ResponseEntity<*> -> {
                    IdempotencyReplayResponse(
                        durableBody = result.body,
                        status = result.statusCode.value(),
                        headers = result.headers.toSingleValueMap(),
                    )
                }

                else -> {
                    IdempotencyReplayResponse(
                        durableBody = result,
                        status = declaredStatus(joinPoint),
                    )
                }
            }
        return IdempotencyResponse(
            status = response.status,
            headers = response.headers,
            body = response.durableBody?.let(apiJsonCodec.mapper::writeValueAsString),
        )
    }

    private fun declaredStatus(joinPoint: ProceedingJoinPoint): Int {
        val method = (joinPoint.signature as MethodSignature).method
        return AnnotatedElementUtils
            .findMergedAnnotation(method, ResponseStatus::class.java)
            ?.code
            ?.value()
            ?: HTTP_OK
    }

    private fun restoreExactResponse(
        joinPoint: ProceedingJoinPoint,
        response: IdempotencyResponse,
        originalResult: Any?,
        servletResponse: HttpServletResponse?,
    ): Any? {
        if (!response.replayed) return originalResult
        response.headers.forEach { (name, value) -> servletResponse?.setHeader(name, value) }
        servletResponse?.status = response.status
        val method = (joinPoint.signature as MethodSignature).method
        return when {
            ResponseEntity::class.java.isAssignableFrom(method.returnType) -> {
                val builder = ResponseEntity.status(response.status)
                response.headers.forEach(builder::header)
                response.body?.let {
                    builder.contentType(MediaType.APPLICATION_JSON).body(it)
                } ?: builder.build<Any>()
            }

            response.body == null -> {
                null
            }

            else -> {
                val returnType =
                    apiJsonCodec.mapper.typeFactory.constructType(method.genericReturnType)
                apiJsonCodec.mapper.readValue<Any>(response.body, returnType)
            }
        }
    }

    private fun restoreSensitiveResponse(
        response: IdempotencyResponse,
        request: HttpServletRequest,
        servletResponse: HttpServletResponse?,
    ): Any {
        val durableJson =
            requireNotNull(response.body) {
                "Context-token replay requires a durable JSON body"
            }
        servletResponse?.status = response.status
        response.headers.forEach { (name, value) -> servletResponse?.setHeader(name, value) }
        val handler =
            requireNotNull(handlers[IdempotencyReplayMode.REISSUE_CONTEXT_TOKEN]) {
                "No context-token replay handler is configured"
            }
        return handler.restore(durableJson, request)
    }

    private companion object {
        const val HTTP_OK = 200
    }
}
