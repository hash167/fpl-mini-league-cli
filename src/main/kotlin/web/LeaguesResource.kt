package web

import FplService
import LiveLeagueResponse
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/leagues/{leagueId}")
@Produces(MediaType.APPLICATION_JSON)
class LeaguesResource(private val service: FplService) {
    @GET
    @Path("/live")
    fun live(
        @PathParam("leagueId") leagueId: Int,
        @QueryParam("gameweek") gameweek: Int?
    ): LiveLeagueResponse = service.liveLeague(leagueId, gameweek)
}
