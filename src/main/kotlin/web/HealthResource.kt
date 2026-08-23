package web

import FplService
import HealthResponse
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
class HealthResource(private val service: FplService) {
    @GET
    fun health(): HealthResponse = service.health()
}
