import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun resource(name: String): String =
    EspnTest::class.java.getResource("/espn/$name")!!.readText()

private fun obj(name: String): JsonObject = Json.parseToJsonElement(resource(name)).jsonObject

class RecordedEspn(private val fail: Boolean = false, private val empty: Boolean = false) : EspnSource {
    val calls = mutableListOf<String>()
    override fun scoreboard(date: String): JsonObject? {
        calls += "sb:$date"
        if (fail) throw RuntimeException("ESPN down")
        if (empty) return JsonObject(emptyMap())
        return if (date == "20261017") obj("scoreboard_20261017.json") else JsonObject(emptyMap())
    }
    override fun summary(eventId: String): JsonObject? {
        calls += "sum:$eventId"
        if (fail) throw RuntimeException("ESPN down")
        return runCatching { obj("summary_$eventId.json") }.getOrNull()
    }
    override fun standings(): JsonObject? {
        calls += "standings"
        if (fail) throw RuntimeException("ESPN down")
        return if (empty) null else obj("standings_20261017.json")
    }
}

class EspnTest {
    private val bootstrap = obj("fpl_bootstrap_min.json")
    private val live = obj("fpl_live_gw7_min.json")
    private val fixtures = (Json.parseToJsonElement(resource("fpl_fixtures_gw7.json")) as JsonArray).map { it.jsonObject }
    private val players = fplPlayerNames(bootstrap)
    private fun fixture(id: Int) = fixtures.single { it.int("id") == id }
    // Hashim-style squad containing Neto (156, captain) and Virgil (Liverpool).
    private val virgil = players.single { it.webName == "Virgil" }.id
    private val squad = listOf(PickRow(156, 1, 2, true, false), PickRow(virgil, 2, 1, false, false))

    @Test
    fun `names are normalized without accents`() {
        assertEquals("moises caicedo", normalizeName("Moisés Caicedo"))
        assertEquals("christian norgaard", normalizeName("Christian Nørgaard"))
        assertEquals("nott m forest", normalizeName("Nott'm Forest"))
    }

    @Test
    fun `fpl fixtures map to espn events via aliases and kickoff`() {
        val sb = obj("scoreboard_20261017.json")
        assertEquals("401878771", findEspnEvent(sb, "Everton", "Chelsea", "2026-10-17T11:30:00Z"))
        assertEquals("401879259", findEspnEvent(sb, "Man City", "Ipswich Town", "2026-10-17T14:00:00Z"))
        assertEquals("401879258", findEspnEvent(sb, "Newcastle", "Aston Villa", "2026-10-17T16:30:00Z"))
        assertEquals("401878770", findEspnEvent(sb, "Fulham", "Hull City", "2026-10-17T14:00:00Z"))
        assertNull(findEspnEvent(sb, "Chelsea", "Everton", "2026-10-17T11:30:00Z"), "home/away must match")
        assertNull(findEspnEvent(sb, "Everton", "Chelsea", "2026-12-01T15:00:00Z"), "kickoff must be close")
        assertTrue(normalizeName("Nottingham Forest") in fplTeamKeys("Nott'm Forest"))
        assertTrue(normalizeName("Tottenham Hotspur") in fplTeamKeys("Spurs"))
        assertTrue(normalizeName("Wolverhampton Wanderers") in fplTeamKeys("Wolves"))
        assertTrue(normalizeName("Brighton & Hove Albion") in fplTeamKeys("Brighton"))
    }

    @Test
    fun `espn names match fpl players by normalized name and team`() {
        assertEquals(156, matchFplPlayer("Pedro Neto", 6, players))
        assertEquals(28, matchFplPlayer("Emiliano Martínez", 6, players))
        assertEquals(165, matchFplPlayer("João Pedro", 6, players))
        assertEquals(161, matchFplPlayer("Romeo Lavia", 6, players))
        assertEquals(159, matchFplPlayer("Moisés Caicedo", 6, players))
        assertNull(matchFplPlayer("Pedro Neto", 9, players), "team must match")
        assertNull(matchFplPlayer("Nobody Atall", 6, players))
    }

    @Test
    fun `finished match timeline has minutes running score HT and FT`() {
        val tl = parseTimeline(obj("summary_401878771.json"), "368", 9 to 6, players, setOf(156), finished = true)
        val goals = tl.filter { it.type == "goal" }
        assertEquals(listOf("46'", "90'"), goals.map { it.minute })
        assertEquals(listOf(0 to 1, 0 to 2), goals.map { it.homeScore to it.awayScore })
        assertTrue(goals.all { it.scoringSide == "a" && it.elementId == 156 && it.inSquad })
        val ht = tl.single { it.type == "ht" }
        assertEquals("45'+4'", ht.minute); assertEquals(0, ht.homeScore)
        assertEquals("90'+14'", tl.last { it.type == "ft" }.minute)
        assertEquals(7, tl.count { it.type == "yellow" })
        val sub = tl.first { it.type == "sub" }
        assertEquals("Tyrique George" to "Ainsley Maitland-Niles", sub.player to sub.secondary)
        assertEquals("h", sub.side)
        assertTrue(tl.indexOf(ht) < tl.indexOf(goals.first()), "timeline stays in match order")
    }

    @Test
    fun `live timeline shows assister added time red card and no FT`() {
        val tl = parseTimeline(obj("summary_401879262.json"), "337", 4 to 14, players, emptySet(), finished = false)
        val goal = tl.single { it.type == "goal" }
        assertEquals("44'", goal.minute); assertEquals("Keane Lewis-Potter", goal.player)
        assertEquals("Igor Thiago", goal.secondary); assertEquals("Header", goal.detail)
        assertEquals(1 to 0, goal.homeScore to goal.awayScore)
        val red = tl.single { it.type == "red" }
        assertEquals("45'+8'", red.minute); assertEquals("h", red.side)
        assertFalse(tl.any { it.type == "ft" })
    }

    @Test
    fun `commentary is newest first`() {
        val c = parseCommentary(obj("summary_401878771.json"))
        assertEquals("Match ends, Everton 0, Chelsea 2.", c.first().text)
        assertTrue(c.zipWithNext().all { (a, b) -> a.sequence > b.sequence })
    }

    @Test
    fun `espn team stats include possession shots corners fouls offsides`() {
        val s = parseEspnStats(obj("summary_401879262.json"), "337").associateBy { it.label }
        assertEquals("39%" to "61%", s.getValue("Possession").home to s.getValue("Possession").away)
        assertEquals("6" to "9", s.getValue("Shots").home to s.getValue("Shots").away)
        assertEquals("1" to "0", s.getValue("Shots on target").home to s.getValue("Shots on target").away)
        assertEquals("3" to "3", s.getValue("Corners").home to s.getValue("Corners").away)
        assertEquals("4" to "10", s.getValue("Fouls").home to s.getValue("Fouls").away)
        assertNotNull(s["Offsides"]); assertEquals("80%", s.getValue("Pass accuracy").home)
        assertTrue(s.values.all { it.source == "ESPN" })
    }

    @Test
    fun `enrichment merges espn into fpl view and keeps fpl points and squad`() {
        val base = buildMatchDetail(fixture(64), 7, bootstrap, live, fixtures, squad, 5498977)
        val espn = enrichWithEspn(RecordedEspn(), base, bootstrap, squad)
        assertNotNull(espn)
        assertEquals("401878771", espn.eventId)
        assertEquals("FT", espn.statusLabel)
        val m = applyEspn(base, espn)
        assertEquals("ESPN", m.source); assertTrue(m.minutesAvailable)
        assertEquals("FT", m.status.label)
        assertEquals("Possession", m.stats.first().label)
        assertTrue(m.stats.any { it.source == "FPL" && it.label == "Total BPS" })
        val chelsea = espn.awayLineup!!
        assertEquals("4-2-3-1", chelsea.formation)
        assertEquals(11, chelsea.starters.size)
        val neto = chelsea.starters.single { it.name == "Pedro Neto" }
        assertTrue(neto.inSquad); assertTrue(neto.captain); assertTrue(neto.subbedOut)
        assertEquals(base.awayLineup.single { it.elementId == 156 }.points, neto.fplPoints)
        assertTrue(chelsea.subs.first().subbedIn)
    }

    @Test
    fun `live fixture uses espn clock`() {
        val base = buildMatchDetail(fixture(62), 7, bootstrap, live, fixtures, squad, 5498977)
        val m = applyEspn(base, enrichWithEspn(RecordedEspn(), base, bootstrap, squad))
        assertEquals("51'", m.status.label)
        assertTrue(m.live)
        assertTrue(m.espn!!.awayLineup!!.starters.any { it.inSquad }, "Virgil highlighted")
    }

    @Test
    fun `match for another gameweek resolves its real gameweek instead of 404`() {
        val fake = FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = fixtures, allFixtureList = fixtures)
        val gw7Only = object : FplClient by fake {
            override fun fixtures(eventId: Int): List<kotlinx.serialization.json.JsonObject> = if (eventId == 7) fixtures else emptyList()
        }
        val svc = FplService(gw7Only, pricesSnapshotPath = null)
        // Client asks for GW 8 (current) but the fixture belongs to GW 7.
        assertEquals(7, svc.matchDetail(64, 8, null).gameweek)
        val e = assertFailsWith<FplApiException> { svc.matchDetail(999999, 8, null) }
        assertEquals(404, e.statusCode)
    }

    @Test
    fun `espn failure or no match falls back to FPL only`() {
        val f = fixture(64)
        val down = FplService(FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = fixtures), pricesSnapshotPath = null, espn = RecordedEspn(fail = true))
        val m1 = down.matchDetail(64, 7, null)
        assertEquals("FPL only", m1.source); assertNull(m1.espn); assertFalse(m1.minutesAvailable)
        val none = FplService(FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = fixtures), pricesSnapshotPath = null, espn = RecordedEspn(empty = true))
        assertEquals("FPL only", none.matchDetail(64, 7, null).source)
        val noEspn = FplService(FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = listOf(f)), pricesSnapshotPath = null)
        assertEquals("FPL only", noEspn.matchDetail(64, 7, null).source)
        val ok = FplService(FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = fixtures), pricesSnapshotPath = null, espn = RecordedEspn())
        assertEquals("ESPN", ok.matchDetail(64, 7, null).source)
    }
}

class HttpEspnSourceBackoffTest {
    @Test
    fun `unreachable espn fails fast and returns null`() {
        var now = 0L
        val src = HttpEspnSource(baseUrl = "http://127.0.0.1:9", clock = { now }, timeout = java.time.Duration.ofMillis(300))
        assertNull(src.scoreboard("20261017"))
        assertNull(src.summary("1"))
        assertNull(src.summary("2"))
        val started = System.nanoTime()
        assertNull(src.summary("3"), "global backoff after 3 failures")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 100, "backoff skips the network")
        now += 31_000L
        assertNull(src.summary("3"))
    }
}

class ScoreboardTest {
    private fun res(name: String) = ScoreboardTest::class.java.getResource("/espn/$name")!!.readText()
    private val bootstrap = Json.parseToJsonElement(res("fpl_bootstrap_min.json")).jsonObject
    private val gw7 = (Json.parseToJsonElement(res("fpl_fixtures_all.json")) as JsonArray).map { it.jsonObject }
        .filter { it.int("event") == 7 }

    @Test
    fun `strip uses espn score and clock when mapped and fpl otherwise`() {
        val sb = buildScoreboard(7, gw7, bootstrap, RecordedEspn())
        assertEquals("ESPN", sb.source); assertTrue(sb.live)
        val bre = sb.fixtures.single { it.id == 62 }
        // Recorded ESPN scoreboard (08:19) says 1-0 at 51'; FPL fixture (08:42) is a different snapshot.
        assertEquals("ESPN", bre.source); assertEquals(1 to 0, bre.homeScore to bre.awayScore); assertEquals("51'", bre.clock)
        val eve = sb.fixtures.single { it.id == 64 }
        assertEquals("FT", eve.clock); assertTrue(eve.finished); assertEquals(0 to 2, eve.homeScore to eve.awayScore)
        val upcoming = sb.fixtures.single { it.id == 61 }
        assertEquals("FPL", upcoming.source); assertEquals("pre", upcoming.state)
    }

    @Test
    fun `strip falls back to fpl when espn fails`() {
        val sb = buildScoreboard(7, gw7, bootstrap, RecordedEspn(fail = true))
        assertEquals("FPL", sb.source)
        val bre = sb.fixtures.single { it.id == 62 }
        assertEquals("FPL", bre.source); assertEquals(1 to 1, bre.homeScore to bre.awayScore)
        assertEquals("in", bre.state)
        assertEquals("FPL", buildScoreboard(7, gw7, bootstrap, null).source)
    }

    @Test
    fun `pre-match summary with published rosters reports lineups`() {
        assertTrue(espnHasLineups(obj("summary_401878769_pre.json")))
        assertFalse(espnHasLineups(JsonObject(emptyMap())))
        assertFalse(espnHasLineups(null))
    }
}

class HttpEspnSourceSingleFlightTest {
    @Test
    fun `concurrent misses for one key share a single ESPN request`() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            Thread.sleep(300)
            val body = """{"events":[]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val src = HttpEspnSource(baseUrl = "http://127.0.0.1:${server.address.port}", timeout = java.time.Duration.ofSeconds(2))
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            val start = java.util.concurrent.CountDownLatch(1)
            val results = (1..6).map { pool.submit<JsonObject?> { start.await(); src.scoreboard("20261025") } }
            start.countDown()
            results.forEach { assertNotNull(it.get(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(1, hits.get(), "one upstream request for six concurrent callers")
            pool.shutdownNow()
        } finally {
            server.stop(0)
        }
    }
}
