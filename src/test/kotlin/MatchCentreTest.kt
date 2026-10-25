import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchCentreTest {
    private fun j(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val bootstrap = j(
        """{"teams":[{"id":1,"code":3,"name":"Arsenal","short_name":"ARS"},{"id":2,"code":7,"name":"Aston Villa","short_name":"AVL"}],
        "elements":[
          {"id":10,"web_name":"Raya","team":1,"element_type":1},
          {"id":11,"web_name":"Saka","team":1,"element_type":3},
          {"id":12,"web_name":"Havertz","team":1,"element_type":4},
          {"id":20,"web_name":"Martinez","team":2,"element_type":1},
          {"id":21,"web_name":"Watkins","team":2,"element_type":4},
          {"id":22,"web_name":"Konsa","team":2,"element_type":2}]}"""
    )

    private fun fixture(started: Boolean, finished: Boolean, provisional: Boolean, minutes: Int, bonus: String = "[]") = j(
        """{"id":5,"event":7,"team_h":1,"team_a":2,"team_h_score":2,"team_a_score":1,"started":$started,
        "finished":$finished,"finished_provisional":$provisional,"minutes":$minutes,"kickoff_time":"2026-10-17T14:00:00Z",
        "stats":[
          {"identifier":"goals_scored","h":[{"element":11,"value":1}],"a":[{"element":21,"value":1}]},
          {"identifier":"assists","h":[{"element":12,"value":1}],"a":[]},
          {"identifier":"own_goals","h":[],"a":[{"element":22,"value":1}]},
          {"identifier":"penalties_saved","h":[],"a":[{"element":20,"value":1}]},
          {"identifier":"penalties_missed","h":[{"element":12,"value":1}],"a":[]},
          {"identifier":"yellow_cards","h":[],"a":[{"element":22,"value":1}]},
          {"identifier":"red_cards","h":[],"a":[]},
          {"identifier":"saves","h":[{"element":10,"value":2}],"a":[{"element":20,"value":5}]},
          {"identifier":"bonus","h":${bonus},"a":[]},
          {"identifier":"bps","h":[{"element":11,"value":40},{"element":12,"value":20},{"element":10,"value":12}],"a":[{"element":20,"value":30},{"element":21,"value":25},{"element":22,"value":-3}]}
        ]}"""
    )

    private fun line(id: Int, minutes: Int, pts: Int, extra: String = "", starts: Int = 1, xg: String = "0.10") =
        """{"id":$id,"stats":{"minutes":$minutes,"starts":$starts,"expected_goals":"$xg","expected_assists":"0.05"},
        "explain":[{"fixture":5,"stats":[{"identifier":"minutes","value":$minutes,"points":$pts}$extra]}]}"""

    private val live = j(
        """{"elements":[${line(10, 60, 2)},${line(11, 60, 2, ",{\"identifier\":\"goals_scored\",\"value\":1,\"points\":5}", xg = "0.80")},
        ${line(12, 30, 1, starts = 0)},${line(20, 60, 2)},${line(21, 60, 2, ",{\"identifier\":\"goals_scored\",\"value\":1,\"points\":4}")},
        ${line(22, 60, 1)}, {"id":99,"stats":{"minutes":0},"explain":[]}]}"""
    )

    private val gwFixtures get() = listOf(fixture(true, false, false, 60))

    @Test
    fun `badge url uses FPL team code`() {
        assertEquals("https://resources.premierleague.com/premierleague/badges/70/t3.png", badgeUrl(3))
        assertNull(badgeUrl(null))
    }

    @Test
    fun `status labels cover upcoming live ht and ft`() {
        assertEquals("upcoming", matchStatus(false, false, false, 0, "k").phase)
        assertEquals("60'", matchStatus(true, false, false, 60, null).label)
        assertEquals("45'+ / HT", matchStatus(true, false, false, 45, null).label)
        assertEquals("90'+", matchStatus(true, false, false, 90, null).label)
        assertEquals("FT", matchStatus(true, true, false, 90, null).label)
        assertEquals("ft", matchStatus(true, false, true, 90, null).phase)
    }

    @Test
    fun `live match builds header facts projected bonus lineup and stats`() {
        val squad = listOf(PickRow(11, 1, 2, isCaptain = true, isViceCaptain = false))
        val m = buildMatchDetail(fixture(true, false, false, 60), 7, bootstrap, live, gwFixtures, squad, 5498977)
        assertEquals("Arsenal", m.home.name)
        assertEquals("https://resources.premierleague.com/premierleague/badges/70/t7.png", m.away.badgeUrl)
        assertEquals(2, m.home.score); assertEquals(1, m.away.score)
        assertTrue(m.live); assertEquals("60'", m.status.label)
        assertFalse(m.minutesAvailable)

        val og = m.facts.single { it.type == "own_goals" }
        assertEquals("a", og.side); assertEquals("h", og.scoringSide)
        assertTrue(m.facts.any { it.type == "goals_scored" && it.player == "Saka" && it.inSquad })
        assertTrue(m.facts.any { it.type == "penalties_saved" && it.player == "Martinez" })
        assertTrue(m.facts.any { it.type == "penalties_missed" && it.player == "Havertz" })
        assertTrue(m.facts.any { it.type == "yellow_cards" && it.player == "Konsa" })

        assertFalse(m.status.bonusConfirmed)
        assertEquals("projected", m.status.bonusSource)
        assertEquals(listOf("Saka" to 3, "Martinez" to 2, "Watkins" to 1), m.bonus.map { it.player to it.bonus })
        assertEquals("Saka", m.topBps.first().player)

        val saka = m.homeLineup.single { it.player == "Saka" }
        assertTrue(saka.inSquad); assertTrue(saka.captain)
        assertEquals(7, saka.points); assertEquals(3, saka.projectedBonus); assertEquals(1, saka.goals)
        assertEquals(listOf("Raya", "Saka", "Havertz"), m.homeLineup.map { it.player })
        assertEquals(false, m.homeLineup.last().started)
        assertEquals(3, m.awayLineup.size)

        val stat = m.stats.associateBy { it.label }
        assertEquals("2" to "5", stat.getValue("Saves").home to stat.getValue("Saves").away)
        assertEquals("1.00", stat.getValue("Expected goals (xG)").home)
        assertEquals("72" to "52", stat.getValue("Total BPS").home to stat.getValue("Total BPS").away)
    }

    @Test
    fun `finished match uses official bonus and marks it confirmed`() {
        val f = fixture(true, true, true, 90, bonus = """[{"element":12,"value":3}]""")
        val m = buildMatchDetail(f, 7, bootstrap, live, listOf(f))
        assertFalse(m.live); assertEquals("FT", m.status.label)
        assertTrue(m.status.bonusConfirmed)
        assertEquals(listOf("Havertz" to 3), m.bonus.map { it.player to it.bonus })
        assertTrue(m.homeLineup.all { it.projectedBonus == 0 })
        assertEquals("confirmed", m.status.bonusSource)
    }

    @Test
    fun `live bonus published by FPL is provisional not confirmed`() {
        val f = fixture(true, false, false, 60, bonus = """[{"element":11,"value":3}]""")
        val m = buildMatchDetail(f, 7, bootstrap, live, listOf(f))
        assertFalse(m.status.bonusConfirmed)
        assertEquals("provisional", m.status.bonusSource)
        assertEquals(listOf("Saka" to 3), m.bonus.map { it.player to it.bonus })
    }

    @Test
    fun `double gameweek team skips aggregated starts and xG`() {
        val other = j("""{"id":6,"team_h":2,"team_a":3}""")
        val m = buildMatchDetail(fixture(true, false, false, 60), 7, bootstrap, live, gwFixtures + other)
        assertTrue(m.awayLineup.all { it.started == null })
        assertFalse(m.stats.any { it.label.startsWith("Expected") })
    }

    @Test
    fun `service finds fixture and returns 404 for unknown id`() {
        val f = fixture(true, false, false, 60)
        val client = FakeFplClient(bootstrap = bootstrap, eventLiveJson = live, fixtureList = listOf(f))
        val service = FplService(client, pricesSnapshotPath = null)
        assertEquals(5, service.matchDetail(5, 7, null).fixtureId)
        val ex = kotlin.runCatching { service.matchDetail(999, 7, null) }.exceptionOrNull()
        assertTrue(ex is FplApiException && ex.statusCode == 404)
    }
}
