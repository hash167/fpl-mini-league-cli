import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import web.ExecutionExceptionMapper
import web.CompletionExceptionMapper
import web.FPL_SLOW_MESSAGE
import web.FplApiExceptionMapper
import web.FplErrorResponses
import web.FplIoExceptionMapper

class StaleOnErrorCacheTest {
    private var now = 1_000_000L
    private fun cache(ttl: Long = 20_000, maxStale: Long = 60_000, cooldown: Long = 5_000) =
        TtlCache<String>(ttl, maxStale, cooldown, clock = { now }, name = "t")

    @Test
    fun `failed reload keeps and serves the last good value flagged stale`() {
        val c = cache()
        assertEquals("v1", c.get { "v1" })
        now += 25_000
        val (v, f) = Freshness.track { c.get { throw IOException("FPL down") } }
        assertEquals("v1", v)
        assertTrue(f.isStale)
        assertEquals(25, f.ageSeconds)
        // still held after the failure
        now += 6_000
        assertEquals("v2", c.get { "v2" })
    }

    @Test
    fun `slow reload returns the last good value after the soft wait and finishes in the background`() {
        val c = TtlCache<String>(ttlMs = 10_000, clock = { now }, name = "slow", softWaitMs = 100)
        c.get { "v1" }
        now += 11_000
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val (v, f) = Freshness.track { c.get { release.await(5, TimeUnit.SECONDS); "v2" } }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("v1", v)
        assertTrue(f.isStale)
        assertTrue(tookMs < 2_000, "took ${tookMs}ms")
        release.countDown()
        val deadline = System.currentTimeMillis() + 3_000
        while (c.ageMs() != 0L && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("v2", c.get { error("should be fresh now") })
    }

    @Test
    fun `fresh value is not stale and reports its age`() {
        val c = cache()
        c.get { "v1" }
        now += 7_000
        val (_, f) = Freshness.track { c.get { error("should not load") } }
        assertFalse(f.isStale)
        assertEquals(7, f.ageSeconds)
    }

    @Test
    fun `cold cache failure propagates`() {
        val c = cache()
        assertFailsWith<HttpTimeoutException> { c.get { throw HttpTimeoutException("timed out") } }
        assertNull(c.ageMs())
    }

    @Test
    fun `value older than max stale is not served`() {
        val c = cache(maxStale = 60_000)
        c.get { "v1" }
        now += 61_000
        assertFailsWith<IOException> { c.get { throw IOException("down") } }
    }

    @Test
    fun `after a failure FPL is not asked again during the cooldown`() {
        val c = cache(cooldown = 5_000)
        c.get { "v1" }
        now += 21_000
        val loads = AtomicInteger()
        c.get { loads.incrementAndGet(); throw IOException("down") }
        now += 2_000
        val (v, f) = Freshness.track { c.get { loads.incrementAndGet(); "v2" } }
        assertEquals("v1", v)
        assertTrue(f.isStale)
        assertEquals(1, loads.get())
        now += 4_000
        assertEquals("v2", c.get { loads.incrementAndGet(); "v2" })
        assertEquals(2, loads.get())
    }

    @Test
    fun `while one caller reloads others get the last value immediately (single flight, no lock convoy)`() {
        val c = TtlCache<String>(ttlMs = 50, maxStaleMs = 60_000)
        c.get { "old" }
        Thread.sleep(80)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val loads = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        val owner = pool.submit<String> {
            c.get { loads.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS); "new" }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val waiter = pool.submit<String> { c.get { loads.incrementAndGet(); "other" } }.get(2, TimeUnit.SECONDS)
        assertEquals("old", waiter)
        assertTrue(System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1))
        release.countDown()
        assertEquals("new", owner.get(5, TimeUnit.SECONDS))
        assertEquals(1, loads.get())
        pool.shutdown()
    }

    @Test
    fun `cold concurrent callers share one load`() {
        val c = TtlCache<String>(ttlMs = 10_000)
        val loads = AtomicInteger()
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val futures = (1..4).map {
            pool.submit<String> { c.get { loads.incrementAndGet(); gate.await(2, TimeUnit.SECONDS); "v" } }
        }
        Thread.sleep(100)
        gate.countDown()
        futures.forEach { assertEquals("v", it.get(5, TimeUnit.SECONDS)) }
        assertEquals(1, loads.get())
        pool.shutdown()
    }

    @Test
    fun `bounded caches keep stale per key and stay bounded`() {
        val caches = BoundedTtlCaches<Int, String>(ttlMs = 20_000, maxKeys = 3, clock = { now })
        (1..3).forEach { k -> caches.get(k) { "v$k" } }
        now += 30_000
        val (v, f) = Freshness.track { caches.get(2) { throw IOException("down") } }
        assertEquals("v2", v)
        assertTrue(f.isStale)
        (4..20).forEach { k -> caches.get(k) { "v$k" } }
        assertEquals(3, caches.size())
    }

    @Test
    fun `freshness propagates into pool tasks`() {
        val c = cache()
        c.get { "v1" }
        now += 25_000
        val pool = Executors.newSingleThreadExecutor()
        val (_, f) = Freshness.track {
            pool.submit(Freshness.wrap(java.util.concurrent.Callable { c.get { throw IOException("x") } })).get()
        }
        assertTrue(f.isStale)
        pool.shutdown()
    }

    @Test
    fun `caching client serves stale bootstrap when FPL fails`() {
        val inner = FakeFplClient(bootstrap = Json.parseToJsonElement("""{"events":[{"id":9,"is_current":true}]}""").jsonObject)
        val client = CachingFplClient(inner, ttlMs = 20_000, clock = { now })
        client.bootstrap()
        now += 30_000
        inner.failWith = HttpTimeoutException("request timed out")
        val (b, f) = Freshness.track { client.bootstrap() }
        assertEquals(9, currentGameweekId(b))
        assertTrue(f.isStale)
    }
}

class RetryingGetTest {
    private var now = 0L
    private val sleeps = mutableListOf<Long>()
    private fun getter(transport: FplTransport, deadline: Long = 10_000) = RetryingGet(
        transport, requestTimeoutMs = 8_000, totalDeadlineMs = deadline,
        clock = { now }, sleeper = { sleeps += it; now += it }, jitterMs = { 300 }
    )

    @Test
    fun `retries once on IOException then succeeds`() {
        var calls = 0
        val body = getter({ _, _ -> calls += 1; if (calls == 1) throw IOException("reset") else 200 to "{}" }).get("u")
        assertEquals("{}", body)
        assertEquals(2, calls)
        assertEquals(listOf(300L), sleeps)
    }

    @Test
    fun `retries once on 5xx and gives up after the second failure`() {
        var calls = 0
        val e = assertFailsWith<FplApiException> { getter({ _, _ -> calls += 1; 503 to "busy" }).get("u") }
        assertEquals(503, e.statusCode)
        assertEquals(2, calls)
    }

    @Test
    fun `does not retry 4xx`() {
        var calls = 0
        val e = assertFailsWith<FplApiException> { getter({ _, _ -> calls += 1; 404 to "" }).get("u") }
        assertEquals(404, e.statusCode)
        assertEquals(1, calls)
    }

    @Test
    fun `second attempt only gets the time left in the total deadline`() {
        val timeouts = mutableListOf<Long>()
        var calls = 0
        assertFailsWith<HttpTimeoutException> {
            getter({ _, t ->
                timeouts += t; calls += 1
                now += t // the attempt used its whole timeout
                throw HttpTimeoutException("timed out")
            }).get("u")
        }
        assertEquals(listOf(8_000L, 1_700L), timeouts)
        assertTrue(now <= 10_000, "stayed within the 10s deadline (was $now)")
    }

    @Test
    fun `no retry when the deadline leaves no room`() {
        var calls = 0
        assertFailsWith<IOException> {
            getter({ _, t -> calls += 1; now += 9_500; throw IOException("slow fail") }).get("u")
        }
        assertEquals(1, calls)
        assertTrue(sleeps.isEmpty())
    }
}

class ExceptionMappingTest {
    @Suppress("UNCHECKED_CAST")
    private fun body(r: jakarta.ws.rs.core.Response) = r.entity as Map<String, String>

    @Test
    fun `timeouts and io errors map to 503 with the friendly message`() {
        val r = FplIoExceptionMapper().toResponse(HttpTimeoutException("request timed out"))
        assertEquals(503, r.status)
        assertEquals(FPL_SLOW_MESSAGE, body(r)["message"])
        assertEquals(503, FplIoExceptionMapper().toResponse(java.net.ConnectException("refused")).status)
    }

    @Test
    fun `wrapped io errors map to 503`() {
        assertEquals(503, ExecutionExceptionMapper().toResponse(ExecutionException(HttpTimeoutException("t"))).status)
        assertEquals(503, CompletionExceptionMapper().toResponse(CompletionException(IOException("x"))).status)
        assertEquals(503, FplErrorResponses.forAny(RuntimeException(ExecutionException(IOException("deep")))).status)
    }

    @Test
    fun `fpl api errors keep 404 and map upstream 5xx to 503`() {
        assertEquals(404, FplApiExceptionMapper().toResponse(FplApiException("nope", 404)).status)
        assertEquals(503, FplApiExceptionMapper().toResponse(FplApiException("busy", 503)).status)
        assertEquals(502, FplApiExceptionMapper().toResponse(FplApiException("bad", 400)).status)
    }

    @Test
    fun `non upstream wrapped errors stay 500`() {
        assertEquals(500, ExecutionExceptionMapper().toResponse(ExecutionException(IllegalStateException("bug"))).status)
    }
}

class ServiceStaleFlagsTest {
    private val bootstrap = Json.parseToJsonElement("""{"events":[{"id":1,"is_current":true}],"elements":[],"teams":[]}""").jsonObject

    @Test
    fun `mini-leagues reports stale and error when a standings lookup fails`() {
        val client = FakeFplClient(
            bootstrap = bootstrap,
            classicLeagues = listOf(LeagueRef(1, "A"), LeagueRef(2, "B")),
            standings = mapOf(1 to LeagueStandingsPage(listOf(StandingRow(1, 7, "T", "M")), false, 5)),
            standingsErrors = mapOf(2 to IOException("timeout"))
        )
        val r = FplService(client, pricesSnapshotPath = null).miniLeagues(7)
        assertEquals(listOf(1), r.leagues.map { it.id })
        assertTrue(r.stale)
        assertTrue(r.error!!.contains("FPL is slow"))
    }

    @Test
    fun `mini-leagues is fresh when everything loads`() {
        val client = FakeFplClient(
            bootstrap = bootstrap,
            classicLeagues = listOf(LeagueRef(1, "A")),
            standings = mapOf(1 to LeagueStandingsPage(listOf(StandingRow(1, 7, "T", "M")), false, 5))
        )
        val r = FplService(CachingFplClient(client), pricesSnapshotPath = null).miniLeagues(7)
        assertFalse(r.stale)
        assertNull(r.error)
    }

    @Test
    fun `live board is served stale with dataAge when FPL starts failing`() {
        var now = 1_000_000L
        val inner = FakeFplClient(bootstrap = bootstrap)
        val svc = FplService(CachingFplClient(inner, clock = { now }), pricesSnapshotPath = null)
        val fresh = svc.liveBoard()
        assertFalse(fresh.stale)
        now += 120_000
        inner.failWith = HttpTimeoutException("request timed out")
        val stale = svc.liveBoard()
        assertTrue(stale.stale)
        assertEquals(120, stale.dataAge)
        assertEquals(fresh.gameweek, stale.gameweek)
    }

    @Test
    fun `cold failure still surfaces as an error`() {
        val inner = FakeFplClient(bootstrap = bootstrap)
        inner.failWith = HttpTimeoutException("request timed out")
        val svc = FplService(CachingFplClient(inner), pricesSnapshotPath = null)
        assertFailsWith<HttpTimeoutException> { svc.liveBoard() }
    }
}
