package web

import FplApiException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import telemetry.FplTelemetry

class FplApiExceptionMapper : ExceptionMapper<FplApiException> {
    override fun toResponse(exception: FplApiException): Response {
        val status = if (exception.statusCode == 404) 404 else 502
        FplTelemetry.incrementHttpError(status)
        return Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(mapOf("error" to (exception.message ?: "FPL API error")))
            .build()
    }
}
