package web

import FplService
import MatchDetail
import MatchStreamKey
import ScoreboardResponse
import StreamHub
import StreamSubscriber
import APP_BUILD
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.Sse
import jakarta.ws.rs.sse.SseEventSink
import java.util.concurrent.TimeUnit

@Path("/live/scoreboard/stream")
class ScoreboardStreamResource(private val hub: StreamHub<Int, ScoreboardResponse>, private val service: FplService) {
    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun stream(@QueryParam("gameweek") gameweek: Int?, @Context sink: SseEventSink, @Context sse: Sse) {
        val gw = gameweek ?: runCatching { service.currentGameweek() }.getOrNull()
        if (gw == null) { sink.close(); return }
        hub.subscribe(gw, SinkSubscriber(sink, sse))
    }
}

@Path("/fixtures/{fixtureId}")
class MatchStreamResource(private val hub: StreamHub<MatchStreamKey, MatchDetail>) {
    @GET
    @Path("/match/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun stream(
        @PathParam("fixtureId") fixtureId: Int,
        @QueryParam("gameweek") gameweek: Int?,
        @QueryParam("entryId") entryId: Int?,
        @Context sink: SseEventSink,
        @Context sse: Sse
    ) {
        val key = MatchStreamKey(fixtureId, gameweek, entryId)
        hub.subscribe(key, SinkSubscriber(sink, sse))
    }
}

/** Adapts a Jersey SSE sink; a failed or slow write marks the client closed. */
class SinkSubscriber(private val sink: SseEventSink, private val sse: Sse) : StreamSubscriber {
    @Volatile private var failed = false
    override val isClosed: Boolean get() = failed || sink.isClosed

    private fun write(event: jakarta.ws.rs.sse.OutboundSseEvent): Boolean {
        if (isClosed) return false
        return try {
            sink.send(event).toCompletableFuture().get(5, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            failed = true
            runCatching { sink.close() }
            false
        }
    }

    override fun send(event: String, id: Long, data: String): Boolean =
        write(sse.newEventBuilder().name(event).id(id.toString()).reconnectDelay(3000).mediaType(MediaType.TEXT_PLAIN_TYPE).data(String::class.java, data).build())

    override fun comment(text: String): Boolean =
        write(sse.newEventBuilder().comment(text).build())

    override fun close() {
        runCatching { sink.close() }
    }
}

/**
 * Cache policy: SSE gets anti-buffering headers; the page, service worker, manifest and every
 * JSON API response must never be served stale by browsers, Home Screen apps or Cloudflare.
 */
class SseHeadersFilter : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        val type = response.mediaType
        val path = request.uriInfo.path.trimStart('/')
        when {
            type != null && type.type == "text" && type.subtype == "event-stream" -> {
                response.headers.putSingle("Cache-Control", "no-cache, no-transform")
                response.headers.putSingle("X-Accel-Buffering", "no")
            }
            path == "" || path == "sw.js" || path == "manifest.webmanifest" || (type != null && type.subtype == "html") -> {
                response.headers.putSingle("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0")
                response.headers.putSingle("Pragma", "no-cache")
                response.headers.putSingle("Expires", "0")
            }
            type != null && type.subtype == "json" ->
                response.headers.putSingle("Cache-Control", "no-store, max-age=0")
        }
    }
}

@jakarta.ws.rs.Path("/version")
class VersionResource {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    fun version(): Map<String, String> = mapOf("build" to APP_BUILD)
}
