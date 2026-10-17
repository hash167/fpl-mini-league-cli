package web

import FplService
import MatchDetail
import ScoreboardResponse
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/live/scoreboard")
@Produces(MediaType.APPLICATION_JSON)
class ScoreboardResource(private val service: FplService) {
    @GET
    fun scoreboard(@QueryParam("gameweek") gameweek: Int?): ScoreboardResponse = service.liveScoreboard(gameweek)
}

@Path("/fixtures/{fixtureId}")
@Produces(MediaType.APPLICATION_JSON)
class FixturesResource(private val service: FplService) {
    @GET
    @Path("/match")
    fun match(
        @PathParam("fixtureId") fixtureId: Int,
        @QueryParam("gameweek") gameweek: Int?,
        @QueryParam("entryId") entryId: Int?
    ): MatchDetail = service.matchDetail(fixtureId, gameweek, entryId)
}
