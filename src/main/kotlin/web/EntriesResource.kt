package web

import EntryLiveResponse
import EntryPicksResponse
import FplService
import MiniLeaguesResponse
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType

@Path("/entries/{entryId}")
@Produces(MediaType.APPLICATION_JSON)
class EntriesResource(private val service: FplService) {
    @GET
    @Path("/mini-leagues")
    fun miniLeagues(@PathParam("entryId") entryId: Int): MiniLeaguesResponse =
        service.miniLeagues(entryId)

    @GET
    @Path("/live")
    fun live(
        @PathParam("entryId") entryId: Int,
        @QueryParam("gameweek") gameweek: Int?,
        @QueryParam("autosubs") @DefaultValue("true") autosubs: Boolean
    ): EntryLiveResponse = service.entryLive(entryId, gameweek, autosubs)

    @GET
    @Path("/event/{gameweek}/picks")
    fun picks(
        @PathParam("entryId") entryId: Int,
        @PathParam("gameweek") gameweek: Int,
        @QueryParam("autosubs") @DefaultValue("true") autosubs: Boolean
    ): EntryPicksResponse = service.entryPicksLive(entryId, gameweek, autosubs)
}
