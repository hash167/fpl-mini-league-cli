package web

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response

@Path("/")
class UiResource {
    @GET
    @Produces(MediaType.TEXT_HTML)
    fun index(): Response {
        val html = javaClass.getResourceAsStream("/assets/index.html")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: "<html><body><p>UI not packaged.</p></body></html>"
        return Response.ok(html).type(MediaType.TEXT_HTML_TYPE).build()
    }
}
