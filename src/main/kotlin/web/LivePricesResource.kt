package web

import FplService
import OfficialPricesResponse
import PriceRiseEstimatesResponse
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/live/prices")
@Produces(MediaType.APPLICATION_JSON)
class LivePricesResource(private val service: FplService) {
    @GET
    fun latest(): OfficialPricesResponse = service.officialPrices()

    @GET
    @Path("/estimate")
    fun estimate(): PriceRiseEstimatesResponse = service.estimatePriceRises()

    @GET
    @Path("/refresh")
    fun refreshGet(): OfficialPricesResponse = service.refreshOfficialPrices()

    @POST
    @Path("/refresh")
    fun refreshPost(): OfficialPricesResponse = service.refreshOfficialPrices()
}
