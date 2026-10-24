import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerBreakdownTest {
    private val players = mapOf(
        1 to PlayerInfo(1, "Gabriel Magalhaes", "Gabriel", 1, "ARS", 2),
        2 to PlayerInfo(2, "Other Guy", "Other", 2, "EVE", 3),
        3 to PlayerInfo(3, "Saka", "Saka", 1, "ARS", 3)
    )
    private val teams = mapOf(1 to "ARS", 2 to "EVE")

    private fun live(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject
    private fun stat(id: String, value: Int, pts: Int) = """{"identifier":"$id","points":$pts,"value":$value,"points_modification":0}"""
    private fun fx(id: Int, started: Boolean, finished: Boolean, h: Int? = null, a: Int? = null) =
        FixtureInfo(id, 8, 1, 2, h, a, started, finished, if (finished) 90 else 60, null)

    @Test
    fun `double gameweek gives one block per fixture and totals match FPL`() {
        val eventLive = live("""{"elements":[{"id":1,"stats":{"total_points":15,"bonus":3,"bps":43},"explain":[
            {"fixture":10,"stats":[${stat("minutes", 90, 2)},${stat("goals_scored", 1, 6)},${stat("clean_sheets", 1, 4)},${stat("bonus", 3, 3)}]},
            {"fixture":11,"stats":[${stat("minutes", 30, 1)},${stat("yellow_cards", 1, -1)}]}]}]}""")
        val stats = parseLiveElementStats(eventLive)
        val b = buildPlayerBreakdown(1, 8, eventLive, listOf(fx(10, true, true, 1, 0), fx(11, true, false, 0, 0)), players, teams, stats)!!
        assertEquals(2, b.fixtures.size)
        assertEquals(listOf("Minutes played", "Goals", "Clean sheet", "Bonus"), b.fixtures[0].stats.map { it.label })
        assertEquals(15, b.fixtures[0].points)
        assertEquals("confirmed", b.fixtures[0].bonusStatus)
        assertEquals(0, b.fixtures[1].points)
        assertEquals(-1, b.fixtures[1].stats.single { it.identifier == "yellow_cards" }.points)
        assertEquals("EVE (H)", b.fixtures[0].opponent)
        assertEquals("1–0", b.fixtures[0].score)
        assertEquals(15, b.officialTotal)
        assertEquals(b.officialTotal, b.fixtures.sumOf { it.points })
        assertEquals(0, b.projectedBonus) // FPL already shows bonus -> no extra (same rule as squad views)
        assertEquals(15, b.liveTotal)
    }

    @Test
    fun `live fixture without FPL bonus projects bonus from BPS and marks it projected`() {
        val eventLive = live("""{"elements":[
            {"id":1,"stats":{"total_points":8,"bonus":0,"bps":40,"minutes":60},"explain":[{"fixture":11,"stats":[${stat("minutes", 60, 2)},${stat("goals_scored", 1, 6)}]}]},
            {"id":2,"stats":{"total_points":2,"bonus":0,"bps":20,"minutes":60},"explain":[{"fixture":11,"stats":[${stat("minutes", 60, 2)}]}]},
            {"id":3,"stats":{"total_points":2,"bonus":0,"bps":10,"minutes":60},"explain":[{"fixture":11,"stats":[${stat("minutes", 60, 2)}]}]}]}""")
        val b = buildPlayerBreakdown(1, 8, eventLive, listOf(fx(11, true, false, 1, 0)), players, teams, parseLiveElementStats(eventLive))!!
        val f = b.fixtures.single()
        assertEquals("projected", f.bonusStatus)
        assertEquals(3, f.bonus)
        assertEquals(40, f.bps)
        assertEquals(8, b.officialTotal)
        assertEquals(3, b.projectedBonus)
        assertEquals(11, b.liveTotal)
    }

    @Test
    fun `live FPL bonus during an unfinished match is provisional and unknown player is null`() {
        val eventLive = live("""{"elements":[{"id":1,"stats":{"total_points":11,"bonus":3,"bps":43},"explain":[{"fixture":11,"stats":[${stat("minutes", 70, 2)},${stat("goals_scored", 1, 6)},${stat("bonus", 3, 3)}]}]}]}""")
        val b = buildPlayerBreakdown(1, 8, eventLive, listOf(fx(11, true, false, 1, 0)), players, teams, parseLiveElementStats(eventLive))!!
        assertEquals("provisional", b.fixtures.single().bonusStatus)
        assertEquals(11, b.liveTotal)
        assertNull(buildPlayerBreakdown(99, 8, eventLive, emptyList(), players, teams, emptyMap()))
    }

    @Test
    fun `player whose fixture has not started shows the fixture with no stats`() {
        val eventLive = live("""{"elements":[{"id":3,"stats":{"total_points":0,"bonus":0,"bps":0},"explain":[]}]}""")
        val b = buildPlayerBreakdown(3, 8, eventLive, listOf(fx(12, false, false)), players, teams, parseLiveElementStats(eventLive))!!
        assertEquals(1, b.fixtures.size)
        assertEquals(false, b.fixtures.single().started)
        assertEquals(0, b.officialTotal)
    }
}
