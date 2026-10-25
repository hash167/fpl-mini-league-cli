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
    fun index(): Response = asset("/assets/index.html", MediaType.TEXT_HTML_TYPE)
        ?: Response.ok("<html><body><p>UI not packaged.</p></body></html>")
            .type(MediaType.TEXT_HTML_TYPE).build()

    @GET
    @Path("manifest.webmanifest")
    @Produces("application/manifest+json")
    fun manifest(): Response = asset(
        "/assets/manifest.webmanifest",
        MediaType.valueOf("application/manifest+json"),
    ) ?: Response.status(404).build()

    @GET
    @Path("sw.js")
    @Produces("application/javascript")
    fun serviceWorker(): Response = asset(
        "/assets/sw.js",
        MediaType.valueOf("application/javascript"),
    ) ?: Response.status(404).build()

    private fun asset(path: String, type: MediaType): Response? {
        val body = javaClass.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return null
        return Response.ok(body).type(type).build()
    }
}
