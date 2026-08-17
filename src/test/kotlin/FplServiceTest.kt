import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class FplServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `isMiniLeague requires fewer than 50 entries and no next page`() {
        assertTrue(isMiniLeague(1, hasNext = false))
        assertTrue(isMiniLeague(49, hasNext = false))
        assertFalse(isMiniLeague(50, hasNext = false))
        assertFalse(isMiniLeague(10, hasNext = true))
        assertFalse(isMiniLeague(0, hasNext = true))
    }

    @Test
    fun `live contribution multiplies raw points by captain and bench multipliers`() {
        assertEquals(16, liveContribution(8, 2))
        assertEquals(5, liveContribution(5, 1))
        assertEquals(0, liveContribution(12, 0))
        assertEquals(0, liveContribution(0, 2))
    }

    @Test
    fun `buildPlayerLiveRows applies live points and sums team total`() {
        val picks = listOf(
            PickRow(element = 10, position = 1, multiplier = 2, isCaptain = true, isViceCaptain = false),
            PickRow(element = 11, position = 2, multiplier = 1, isCaptain = false, isViceCaptain = true),
            PickRow(element = 12, position = 15, multiplier = 0, isCaptain = false, isViceCaptain = false),
            PickRow(element = 99, position = 3, multiplier = 1, isCaptain = false, isViceCaptain = false)
        )
        val livePoints = mapOf(10 to 8, 11 to 5, 12 to 3)
        val players = mapOf(
            10 to PlayerRef("Bukayo Saka", "ARS"),
            11 to PlayerRef("Erling Haaland", "MCI")
        )

        val rows = buildPlayerLiveRows(picks, livePoints, players)

        assertEquals(listOf(1, 2, 3, 15), rows.map { it.position })
        assertEquals(16, rows[0].contribution)
        assertEquals(true, rows[0].captain)
        assertEquals(5, rows[1].contribution)
        assertEquals(true, rows[1].viceCaptain)
        assertEquals(0, rows.first { it.element == 99 }.contribution)
        assertEquals("Unknown (99)", rows.first { it.element == 99 }.name)
        assertEquals(0, rows.first { it.element == 12 }.contribution)
        assertEquals(21, teamLivePoints(rows))
    }

    @Test
    fun `miniLeagues filters using standings JSON entry count and has_next`() {
        val client = FakeFplClient(
            bootstrap = parse(
                """{"events":[{"id":2,"is_current":true}]}"""
            ),
            classicLeagues = listOf(
                LeagueRef(1, "Mini Friends"),
                LeagueRef(2, "Overall"),
                LeagueRef(3, "Paged")
            ),
            standings = mapOf(
                1 to LeagueStandingsPage(
                    results = listOf(StandingRow(1, 100, "A", "Ann")),
                    hasNext = false,
                    totalEntries = 12
                ),
                2 to LeagueStandingsPage(
                    results = emptyList(),
                    hasNext = false,
                    totalEntries = 5000000
                ),
                3 to LeagueStandingsPage(
                    results = listOf(StandingRow(1, 100, "A", "Ann")),
                    hasNext = true,
                    totalEntries = 20
                )
            )
        )

        val result = FplService(client).miniLeagues(100)

        assertEquals(2, result.gameweek)
        assertEquals(listOf(1), result.leagues.map { it.id })
        assertEquals("Mini Friends", result.leagues.single().name)
        assertEquals(12, result.leagues.single().entryCount)
    }

    @Test
    fun `liveLeague and entry picks use mocked FPL live JSON math`() {
        val client = FakeFplClient(
            bootstrap = parse(
                """
                {
                  "events":[{"id":4,"is_current":true}],
                  "teams":[{"id":1,"short_name":"ARS"}],
                  "elements":[{"id":10,"first_name":"Bukayo","second_name":"Saka","team":1}]
                }
                """.trimIndent()
            ),
            standings = mapOf(
                7 to LeagueStandingsPage(
                    results = listOf(StandingRow(2, 42, "Gunners", "Ada")),
                    hasNext = false,
                    totalEntries = 8
                )
            ),
            livePoints = mapOf(10 to 7),
            picks = mapOf(
                (42 to 4) to listOf(
                    PickRow(10, 1, 2, isCaptain = true, isViceCaptain = false)
                )
            )
        )
        val service = FplService(client)

        val table = service.liveLeague(7, null)
        assertEquals(4, table.gameweek)
        assertEquals(14, table.teams.single().livePoints)
        assertEquals(14, table.teams.single().gwGross)
        assertEquals(14, table.teams.single().gwNet)
        assertEquals(14, table.teams.single().liveTotal)
        assertEquals(1, table.teams.single().liveRank)
        assertEquals(42, table.teams.single().entryId)

        val picks = service.entryPicksLive(42, 4)
        assertEquals(14, picks.livePoints)
        assertEquals(7, picks.players.single().rawPoints)
        assertEquals(2, picks.players.single().multiplier)
        assertEquals("Bukayo Saka", picks.players.single().name)
    }

    @Test
    fun `entryLive renders zeros when GW picks are not published yet`() {
        val client = FakeFplClient(
            bootstrap = parse(
                """{"events":[{"id":1,"is_next":true}],"teams":[],"elements":[]}"""
            ),
            entryJson = parse(
                """{"id":1,"player_first_name":"Chris","player_last_name":"Musson","name":"Solio Moose"}"""
            ),
            throwEntryEvent = true
        )
        val live = FplService(client).entryLive(1, null, true)
        assertEquals(1, live.gameweek)
        assertEquals("Chris Musson", live.managerName)
        assertEquals("Solio Moose", live.teamName)
        assertEquals(0, live.gwNet)
        assertEquals(0, live.liveTotal)
        assertEquals(false, live.live)
        assertEquals(emptyList(), live.players)
    }

    private fun parse(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject
}

class FakeFplClient(
    private val bootstrap: JsonObject,
    private val classicLeagues: List<LeagueRef> = emptyList(),
    private val standings: Map<Int, LeagueStandingsPage> = emptyMap(),
    private val livePoints: Map<Int, Int> = emptyMap(),
    private val picks: Map<Pair<Int, Int>, List<PickRow>> = emptyMap(),
    private val entryJson: JsonObject = JsonObject(emptyMap()),
    private val throwEntryEvent: Boolean = false
) : FplClient {
    override fun bootstrap(): JsonObject = bootstrap
    override fun userClassicLeagues(entryId: Int): List<LeagueRef> = classicLeagues
    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage =
        standings.getValue(leagueId)
    override fun eventLiveElementPoints(eventId: Int): Map<Int, Int> = livePoints
    override fun entry(entryId: Int): JsonObject = entryJson
    override fun entryEvent(entryId: Int, eventId: Int): JsonObject {
        if (throwEntryEvent) throw FplApiException("missing picks", 404)
        return JsonObject(emptyMap())
    }
    override fun entryPicks(entryId: Int, eventId: Int): List<PickRow> {
        if (throwEntryEvent) throw FplApiException("missing picks", 404)
        return picks[entryId to eventId] ?: emptyList()
    }
}
