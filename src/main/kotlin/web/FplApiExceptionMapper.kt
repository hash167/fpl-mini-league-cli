package web

import FplApiException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import java.io.IOException
import java.io.UncheckedIOException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import org.slf4j.LoggerFactory
import telemetry.FplTelemetry

const val FPL_SLOW_MESSAGE = "FPL is slow right now, showing last data when available"

private val mapperLog = LoggerFactory.getLogger("fpl.errors")

/** Shared mapping so every mapper (and tests) agree on status + body. */
object FplErrorResponses {
    /** Walks wrapper exceptions (ExecutionException, CompletionException, UncheckedIOException). */
    fun rootUpstreamFailure(t: Throwable): Throwable? {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur is IOException) return cur
            if (cur is FplApiException) return cur
            cur = cur.cause
            depth += 1
        }
        return null
    }

    fun slow(cause: Throwable): Response {
        mapperLog.warn("FPL unavailable: {}: {}", cause.javaClass.simpleName, cause.message)
        FplTelemetry.incrementHttpError(503)
        return Response.status(503)
            .type(MediaType.APPLICATION_JSON)
            .header("Retry-After", "10")
            .entity(mapOf("message" to FPL_SLOW_MESSAGE, "error" to FPL_SLOW_MESSAGE))
            .build()
    }

    fun forFplApi(exception: FplApiException): Response {
        if (exception.statusCode >= 500) return slow(exception)
        val status = if (exception.statusCode == 404) 404 else 502
        FplTelemetry.incrementHttpError(status)
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(mapOf("error" to (exception.message ?: "FPL API error")))
            .build()
    }

    fun forAny(t: Throwable): Response {
        return when (val root = rootUpstreamFailure(t)) {
            is FplApiException -> forFplApi(root)
            null -> {
                mapperLog.error("Unhandled error", t)
                FplTelemetry.incrementHttpError(500)
                Response.status(500)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(mapOf("error" to "Something went wrong", "message" to "Something went wrong"))
                    .build()
            }
            else -> slow(root)
        }
    }
}

class FplApiExceptionMapper : ExceptionMapper<FplApiException> {
    override fun toResponse(exception: FplApiException): Response = FplErrorResponses.forFplApi(exception)
}

/** HttpTimeoutException / ConnectException / any other transport failure talking to FPL. */
class FplIoExceptionMapper : ExceptionMapper<IOException> {
    override fun toResponse(exception: IOException): Response = FplErrorResponses.slow(exception)
}

class UncheckedIoExceptionMapper : ExceptionMapper<UncheckedIOException> {
    override fun toResponse(exception: UncheckedIOException): Response = FplErrorResponses.forAny(exception)
}

class ExecutionExceptionMapper : ExceptionMapper<ExecutionException> {
    override fun toResponse(exception: ExecutionException): Response = FplErrorResponses.forAny(exception)
}

class CompletionExceptionMapper : ExceptionMapper<CompletionException> {
    override fun toResponse(exception: CompletionException): Response = FplErrorResponses.forAny(exception)
}
