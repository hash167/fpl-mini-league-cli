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
    /** Liveness/readiness: answers from memory only, never touches the FPL API, caches or locks. */
    @GET
    fun health(): HealthResponse = HealthResponse(status = "ok", fpl = "not-checked")

    /** Deep check (fetches/uses the cached FPL bootstrap). Not used by Kubernetes probes. */
    @GET
    @Path("/fpl")
    fun fpl(): HealthResponse = service.health()
}
