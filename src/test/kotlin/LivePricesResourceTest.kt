import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import web.LivePricesResource

class LivePricesResourceTest {
    private fun service(): FplService {
        val dir = java.nio.file.Files.createTempDirectory("fpl-prices")
        val bootstrap = Json.parseToJsonElement(
            """{"events":[{"id":3,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"}],
            "elements":[{"id":1,"web_name":"Saka","first_name":"B","second_name":"S","team":1,"element_type":3,"now_cost":100}]}"""
        ).jsonObject
        return FplService(FakeFplClient(bootstrap = bootstrap), pricesSnapshotPath = dir.resolve("p.json").toString())
    }

    @Test
    fun `refresh needs the ingest token`() {
        val r = LivePricesResource(service(), "s3cret")
        assertEquals(403, r.refresh(null).status)
        assertEquals(403, r.refresh("wrong").status)
        assertEquals(200, r.refresh("s3cret").status)
    }

    @Test
    fun `refresh is disabled when no token is configured`() {
        assertEquals(503, LivePricesResource(service(), null).refresh("anything").status)
        assertEquals(503, LivePricesResource(service(), " ").refresh("anything").status)
    }

    @Test
    fun `refresh is POST only`() {
        val refresh = LivePricesResource::class.java.methods.filter { it.getAnnotation(Path::class.java)?.value == "/refresh" }
        assertEquals(1, refresh.size)
        assertTrue(refresh.single().isAnnotationPresent(POST::class.java))
        assertTrue(!refresh.single().isAnnotationPresent(GET::class.java))
    }
}
