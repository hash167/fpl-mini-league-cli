package web

import FplService
import OfficialPricesResponse
import PriceRiseEstimatesResponse
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.security.MessageDigest

@Path("/live/prices")
@Produces(MediaType.APPLICATION_JSON)
class LivePricesResource(private val service: FplService, private val ingestToken: String? = null) {
    @GET
    fun latest(): OfficialPricesResponse = service.officialPrices()

    @GET
    @Path("/estimate")
    fun estimate(): PriceRiseEstimatesResponse = service.estimatePriceRises()

    /**
     * Re-baselines the persisted official prices (the nightly movers diff). POST only, and only with
     * the shared ingest token (same check as /live/gw-stats/top10k): a stray request must not wipe it.
     */
    @POST
    @Path("/refresh")
    fun refresh(@HeaderParam("X-Ingest-Token") token: String?): Response {
        val expected = ingestToken?.takeIf { it.isNotBlank() }
            ?: return Response.status(503).entity(mapOf("error" to "refresh disabled")).build()
        if (token == null || !MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())) {
            return Response.status(403).entity(mapOf("error" to "forbidden")).build()
        }
        return Response.ok(service.refreshOfficialPrices()).build()
    }
}
