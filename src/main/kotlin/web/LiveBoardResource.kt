package web

import FplService
import LiveBoardResponse
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/live/board")
@Produces(MediaType.APPLICATION_JSON)
class LiveBoardResource(private val service: FplService) {
    @GET
    fun board(
        @QueryParam("gameweek") gameweek: Int?
    ): LiveBoardResponse = service.liveBoard(gameweek)
}
