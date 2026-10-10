package web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * GW Stats: top-10k transfers in/out for the upcoming gameweek.
 * The fpl-top10k-transfers CronJob computes the aggregate and POSTs it here;
 * we persist it atomically under <dataDir>/top10k (gw{N}.json + latest.json).
 */
@Path("/live/gw-stats")
@Produces(MediaType.APPLICATION_JSON)
class GwStatsResource(
    dataDir: String,
    private val ingestToken: String?,
    private val mapper: ObjectMapper = ObjectMapper()
) {
    private val dir = Paths.get(dataDir, "top10k")

    @GET
    @Path("/top10k")
    fun latest(): Response {
        val f = dir.resolve("latest.json")
        if (!Files.isRegularFile(f)) {
            return Response.status(404).entity(mapOf("available" to false)).build()
        }
        return Response.ok(Files.readString(f)).header("Cache-Control", "public, max-age=300").build()
    }

    @POST
    @Path("/top10k")
    @Consumes(MediaType.APPLICATION_JSON)
    fun ingest(@HeaderParam("X-Ingest-Token") token: String?, body: String): Response {
        val expected = ingestToken?.takeIf { it.isNotBlank() }
            ?: return Response.status(503).entity(mapOf("error" to "ingest disabled")).build()
        if (token == null || !MessageDigest.isEqual(token.toByteArray(), expected.toByteArray())) {
            return Response.status(403).entity(mapOf("error" to "forbidden")).build()
        }
        val node = try { mapper.readTree(body) } catch (e: Exception) { null }
        if (node !is ObjectNode || !node.path("gw").isInt || !node.path("transfers_in").isArray ||
            !node.path("transfers_out").isArray) {
            return Response.status(400).entity(mapOf("error" to "invalid payload")).build()
        }
        val gw = node.path("gw").asInt()
        val json = mapper.writeValueAsString(node)
        Files.createDirectories(dir)
        writeAtomic("gw$gw.json", json)
        writeAtomic("latest.json", json)
        return Response.ok(mapOf("stored" to true, "gw" to gw)).build()
    }

    private fun writeAtomic(name: String, json: String) {
        val tmp = Files.createTempFile(dir, ".$name", ".tmp")
        Files.writeString(tmp, json)
        Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
