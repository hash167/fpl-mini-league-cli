import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeSub : StreamSubscriber {
    val events = CopyOnWriteArrayList<Pair<String, String>>()
    val comments = AtomicInteger()
    @Volatile var gone = false
    @Volatile var closed = false
    override val isClosed get() = gone || closed
    override fun send(event: String, id: Long, data: String): Boolean {
        if (gone) return false
        events += event to data; return true
    }
    override fun comment(text: String): Boolean { if (gone) return false; comments.incrementAndGet(); return true }
    override fun close() { closed = true }
}

class MatchStreamTest {
    private fun res(name: String) = MatchStreamTest::class.java.getResource("/espn/$name")!!.readText()
    private val bootstrap = Json.parseToJsonElement(res("fpl_bootstrap_min.json")).jsonObject
    private val live = Json.parseToJsonElement(res("fpl_live_gw7_min.json")).jsonObject
    private val fixtures = (Json.parseToJsonElement(res("fpl_fixtures_gw7.json")) as JsonArray).map { it.jsonObject }
    private val liveBase = buildMatchDetail(fixtures.single { it.int("id") == 62 }, 7, bootstrap, live, fixtures)
    private fun withLabel(label: String, phase: String = "live") =
        liveBase.copy(status = liveBase.status.copy(label = label, phase = phase), live = phase == "live")

    private val key = MatchStreamKey(62, 7, null)
    private val loads = AtomicInteger()
    @Volatile private var current: MatchDetail = withLabel("61'")
    @Volatile private var failLoad = false
    private val hub = MatchStreamHub(
        load = { loads.incrementAndGet(); if (failLoad) throw RuntimeException("boom"); current },
        serialize = { "${it.status.phase}|${it.status.label}|${it.source}" },
        pollMs = 3_600_000, idlePollMs = 3_600_000, keepaliveMs = 3_600_000,
        maxSubscribersPerMatch = 2
    )

    @AfterTest fun tearDown() = hub.shutdown()

    @Test
    fun `initial snapshot then pushes only on change`() {
        val a = FakeSub()
        assertTrue(hub.subscribe(key, a))
        assertEquals(listOf("snapshot"), a.events.map { it.first })
        assertTrue(hub.isPolling(key))
        hub.pollNow(key)
        hub.pollNow(key)
        assertEquals(1, a.events.size, "unchanged payload is not pushed")
        current = withLabel("62'")
        hub.pollNow(key)
        assertEquals(2, a.events.size)
        assertTrue(a.events.last().second.contains("62'"))
        assertEquals(1, hub.pushCount(key))
    }

    @Test
    fun `one shared poller and cached snapshot for many subscribers`() {
        val a = FakeSub(); val b = FakeSub()
        hub.subscribe(key, a); hub.subscribe(key, b)
        assertEquals(1, loads.get(), "second subscriber reuses the shared snapshot")
        current = withLabel("70'")
        hub.pollNow(key)
        assertEquals(2, loads.get())
        assertEquals(2, a.events.size); assertEquals(2, b.events.size)
        assertEquals(1, hub.activeMatches())
    }

    @Test
    fun `subscriber cap rejects extra clients`() {
        hub.subscribe(key, FakeSub()); hub.subscribe(key, FakeSub())
        val c = FakeSub()
        assertFalse(hub.subscribe(key, c))
        assertEquals("error", c.events.single().first)
        assertTrue(c.closed)
    }

    @Test
    fun `disconnected clients are cleaned up and poller stops when none remain`() {
        val a = FakeSub(); val b = FakeSub()
        hub.subscribe(key, a); hub.subscribe(key, b)
        a.gone = true
        hub.keepaliveNow()
        assertEquals(1, hub.subscriberCount(key))
        assertEquals(1, b.comments.get())
        b.gone = true
        current = withLabel("80'")
        hub.pollNow(key)
        assertFalse(hub.isPolling(key))
        assertEquals(0, hub.activeMatches())
    }

    @Test
    fun `explicit unsubscribe stops the poller`() {
        val a = FakeSub()
        hub.subscribe(key, a)
        hub.unsubscribe(key, a)
        assertFalse(hub.isPolling(key)); assertEquals(0, hub.activeMatches())
    }

    @Test
    fun `full time sends final event closes streams and stops poller`() {
        val a = FakeSub()
        hub.subscribe(key, a)
        current = withLabel("FT", phase = "ft")
        hub.pollNow(key)
        assertEquals("final", a.events.last().first)
        assertTrue(a.closed)
        assertFalse(hub.isPolling(key)); assertEquals(0, hub.activeMatches())
    }

    @Test
    fun `finished match on connect gets one final event and no poller`() {
        current = withLabel("FT", phase = "ft")
        val a = FakeSub()
        assertTrue(hub.subscribe(key, a))
        assertEquals(listOf("final"), a.events.map { it.first })
        assertTrue(a.closed); assertFalse(hub.isPolling(key))
    }

    @Test
    fun `load failures keep last snapshot and stream stays open`() {
        val a = FakeSub()
        hub.subscribe(key, a)
        failLoad = true
        hub.pollNow(key)
        assertEquals(1, a.events.size); assertFalse(a.closed); assertTrue(hub.isPolling(key))
        failLoad = false
        current = withLabel("63'")
        hub.pollNow(key)
        assertEquals(2, a.events.size)
    }

    @Test
    fun `espn outage falls back to FPL only payload through the service`() {
        val service = FplService(
            FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = fixtures),
            pricesSnapshotPath = null, espn = RecordedEspn(fail = true)
        )
        val h = MatchStreamHub(
            load = { k -> service.matchDetail(k.fixtureId, k.gameweek, k.entryId) },
            serialize = { it.source }, pollMs = 3_600_000, idlePollMs = 3_600_000, keepaliveMs = 3_600_000
        )
        try {
            val a = FakeSub()
            assertTrue(h.subscribe(key, a))
            assertEquals("snapshot" to "FPL only", a.events.single())
        } finally { h.shutdown() }
    }

    @Test
    fun `real poller ticks on schedule`() {
        val h = MatchStreamHub(load = { current }, serialize = { it.status.label }, pollMs = 50, idlePollMs = 50, keepaliveMs = 40)
        try {
            val a = FakeSub()
            h.subscribe(key, a)
            current = withLabel("88'")
            val deadline = System.currentTimeMillis() + 3000
            while (a.events.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals("88'", a.events.last().second)
            while (a.comments.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(a.comments.get() > 0, "keepalives are sent")
        } finally { h.shutdown() }
    }
}

class ScoreboardStreamTest {
    private fun sb(score: Int, finished: Boolean = false) = ScoreboardResponse(
        7, !finished, listOf(ScoreboardFixture(62, 1, score, if (finished) "FT" else "70'", if (finished) "post" else "in", true, finished, 70, "ESPN")), "ESPN"
    )
    @Volatile private var current = sb(1)
    private val hub = ScoreboardStreamHub(load = { current }, serialize = { "${it.fixtures[0].awayScore}|${it.fixtures[0].clock}" },
        pollMs = 3_600_000, keepaliveMs = 3_600_000)

    @AfterTest fun tearDown() = hub.shutdown()

    @Test
    fun `scoreboard stream pushes score changes and finishes when all games end`() {
        val a = FakeSub()
        assertTrue(hub.subscribe(7, a))
        hub.pollNow(7)
        assertEquals(1, a.events.size)
        current = sb(2)
        hub.pollNow(7)
        assertEquals("snapshot" to "2|70'", a.events.last())
        current = sb(2, finished = true)
        hub.pollNow(7)
        assertEquals("final", a.events.last().first)
        assertTrue(a.closed); assertFalse(hub.isPolling(7))
    }
}
