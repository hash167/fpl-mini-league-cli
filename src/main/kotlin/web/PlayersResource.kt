package web

import FplService
import PlayerBreakdown
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/players/{elementId}")
@Produces(MediaType.APPLICATION_JSON)
class PlayersResource(private val service: FplService) {
    @GET
    @Path("/breakdown")
    fun breakdown(
        @PathParam("elementId") elementId: Int,
        @QueryParam("gameweek") gameweek: Int?
    ): PlayerBreakdown = service.playerBreakdown(elementId, gameweek)
}
