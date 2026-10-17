import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeagueTableTest {
    private fun res(name: String) = LeagueTableTest::class.java.getResource("/espn/$name")!!.readText()
    private val bootstrap = Json.parseToJsonElement(res("fpl_bootstrap_min.json")).jsonObject
    private val standings = Json.parseToJsonElement(res("standings_20261017.json")).jsonObject
    // Recorded 08:42 PT: BRE 1-1 LIV, FUL 1-0 HUL, MCI 3-1 IPS in play; EVE 0-2 CHE finished.
    private val all = (Json.parseToJsonElement(res("fpl_fixtures_all.json")) as JsonArray).map { it.jsonObject }
    private val teamId = bootstrap.arr("teams").mapNotNull { it as? JsonObject }.associate { it.str("short_name")!! to it.int("id")!! }
    private fun row(t: LeagueTable, short: String) = t.rows.single { it.shortName == short }

    @Test
    fun `espn baseline plus live scores re-sorts the table`() {
        val t = buildLeagueTable(all, bootstrap, standings, teamId.getValue("BRE") to teamId.getValue("LIV"))
        assertEquals("ESPN", t.source); assertNull(t.note)
        assertEquals(20, t.rows.size)
        val mci = row(t, "MCI")
        assertEquals(1, mci.position); assertEquals(21, mci.points); assertEquals(7, mci.played)
        assertTrue(mci.live); assertEquals("3–1 v IPS", mci.liveScore)
        val ful = row(t, "FUL")
        assertEquals(20, ful.preMatchPosition); assertTrue(ful.movement > 0); assertEquals(5, ful.points)
        assertEquals("0–1 @ FUL", row(t, "HUL").liveScore)
        val bre = row(t, "BRE")
        assertTrue(bre.inMatch && row(t, "LIV").inMatch); assertFalse(mci.inMatch)
        assertEquals(11, bre.points); assertEquals(7, bre.played)
        val che = row(t, "CHE")
        assertFalse(che.live); assertEquals(13, che.points); assertEquals(7, che.played)
        assertEquals(che.preMatchPosition - che.position, che.movement)
        assertEquals(t.rows.map { it.position }, (1..20).toList())
        assertTrue(t.rows.zipWithNext().all { (a, b) -> a.points >= b.points })
        assertTrue(mci.badgeUrl!!.startsWith("https://resources.premierleague.com/"))
    }

    @Test
    fun `fpl derived baseline matches espn when results agree`() {
        val espn = buildLeagueTable(all, bootstrap, standings, 0 to 0)
        val fpl = buildLeagueTable(all, bootstrap, null, 0 to 0)
        assertEquals("FPL", fpl.source); assertNotNull(fpl.note)
        assertEquals(espn.rows.map { it.shortName to it.points }, fpl.rows.map { it.shortName to it.points })
    }

    @Test
    fun `lagging espn standings fall back to fpl results`() {
        val finished62 = all.map { f ->
            if (f.int("id") == 62) JsonObject(f + ("finished_provisional" to JsonPrimitive(true))) else f
        }
        val t = buildLeagueTable(finished62, bootstrap, standings, 0 to 0)
        assertEquals("FPL", t.source)
        assertTrue(t.note!!.contains("not yet updated"))
        val bre = row(t, "BRE")
        assertEquals(7, bre.played); assertFalse(bre.live); assertEquals(11, bre.points)
    }

    @Test
    fun `open match score override from espn is applied`() {
        val t = buildLeagueTable(all, bootstrap, standings, 0 to 0, mapOf(67 to (1 to 1)))
        assertEquals(19, row(t, "MCI").points); assertEquals("1–1 v IPS", row(t, "MCI").liveScore)
    }

    @Test
    fun `service attaches table and falls back to fpl when espn fails`() {
        val live = Json.parseToJsonElement(res("fpl_live_gw7_min.json")).jsonObject
        val gw7 = (Json.parseToJsonElement(res("fpl_fixtures_gw7.json")) as JsonArray).map { it.jsonObject }
        fun svc(espn: EspnSource) = FplService(
            FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = gw7, allFixtureList = all),
            pricesSnapshotPath = null, espn = espn
        )
        val ok = svc(RecordedEspn()).matchDetail(62, 7, null)
        assertEquals("ESPN", ok.table!!.source)
        assertTrue(ok.table!!.rows.single { it.shortName == "BRE" }.inMatch)
        val down = svc(RecordedEspn(fail = true)).matchDetail(62, 7, null)
        assertEquals("FPL", down.table!!.source)
        assertEquals("FPL only", down.source)
    }
}
