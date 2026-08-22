package web

import FplService
import LiveOverallEstimateStatus
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/overall")
@Produces(MediaType.APPLICATION_JSON)
class OverallResource(private val service: FplService) {
    @GET
    @Path("/live-estimate")
    fun liveEstimate(
        @QueryParam("gameweek") gameweek: Int?
    ): LiveOverallEstimateStatus = service.liveOverallEstimateStatus(gameweek)
}
