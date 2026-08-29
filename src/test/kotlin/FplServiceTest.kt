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
                LeagueRef(2, "Overall", leagueType = "s"),
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
        assertEquals(setOf(1, 3), client.standingsRequested.toSet())
    }

    @Test
    fun `miniLeagues skips system leagues and standings errors`() {
        val client = FakeFplClient(
            bootstrap = parse(
                """{"events":[{"id":1,"is_current":true}]}"""
            ),
            classicLeagues = listOf(
                LeagueRef(14, "Liverpool", leagueType = "s"),
                LeagueRef(53998, "Fantasy Football Scout"),
                LeagueRef(136268, "Play Now Cry Later")
            ),
            standings = mapOf(
                136268 to LeagueStandingsPage(
                    results = listOf(StandingRow(1, 5498977, "Hashim's Team", "Hashim C")),
                    hasNext = false,
                    totalEntries = 10
                )
            ),
            standingsErrors = mapOf(
                53998 to FplApiException(
                    "FPL API request failed (500): https://fantasy.premierleague.com/api/leagues-classic/53998/standings/?page_standings=1",
                    500
                )
            )
        )

        val result = FplService(client).miniLeagues(5498977)

        assertEquals(listOf(136268), result.leagues.map { it.id })
        assertEquals("Play Now Cry Later", result.leagues.single().name)
        assertFalse(client.standingsRequested.contains(14))
        assertEquals(listOf(53998, 136268), client.standingsRequested)
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
        assertEquals("official", live.overallRankLabel)
        assertEquals(false, live.liveOverallEstimateAvailable)
        assertEquals(null, live.liveOverallRankEstimate)
    }

    private fun parse(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `parsePlayerInfo reads official now_cost and GW price change`() {
        val bootstrap = parse(
            """{"teams":[{"id":1,"short_name":"LIV"}],"elements":[{
              "id":328,"first_name":"Mohamed","second_name":"Salah","web_name":"Salah",
              "team":1,"element_type":3,"now_cost":130,"cost_change_event":1,"cost_change_start":5
            }]}"""
        )
        val info = parsePlayerInfo(bootstrap)[328]!!
        assertEquals(130, info.nowCost)
        assertEquals(1, info.costChangeEvent)
        assertEquals(5, info.costChangeStart)
        assertEquals(13.0, tenthsToMillions(info.nowCost))
        assertEquals(0.1, tenthsToMillions(info.costChangeEvent))
    }

    @Test
    fun `liveBoard ranks net transfers as price estimate without entry id`() {
        val bootstrap = parse(
            """{"events":[{"id":3,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"},{"id":2,"short_name":"MCI"}],
            "elements":[
              {"id":1,"web_name":"Saka","first_name":"Bukayo","second_name":"Saka","team":1,"element_type":3,
               "now_cost":100,"cost_change_event":1,"transfers_in_event":200000,"transfers_out_event":10000,"selected_by_percent":"45.1"},
              {"id":2,"web_name":"Haaland","first_name":"Erling","second_name":"Haaland","team":2,"element_type":4,
               "now_cost":145,"cost_change_event":0,"transfers_in_event":5000,"transfers_out_event":180000,"selected_by_percent":"60.0"},
              {"id":3,"web_name":"Bench","first_name":"A","second_name":"B","team":1,"element_type":2,
               "now_cost":45,"cost_change_event":0,"transfers_in_event":100,"transfers_out_event":90,"selected_by_percent":"1.0"}
            ]}"""
        )
        val live = parse(
            """{"elements":[
              {"id":1,"stats":{"minutes":90,"total_points":12,"bonus":0,"bps":1}},
              {"id":2,"stats":{"minutes":90,"total_points":2,"bonus":0,"bps":1}},
              {"id":3,"stats":{"minutes":0,"total_points":0,"bonus":0,"bps":0}}
            ]}"""
        )
        val board = FplService(FakeFplClient(bootstrap = bootstrap, eventLiveJson = live)).liveBoard()
        assertEquals(3, board.gameweek)
        assertEquals(false, board.live)
        assertEquals(3, board.players.size)
        val saka = board.players.first { it.id == 1 }
        assertEquals("Saka", saka.webName)
        assertEquals("ARS", saka.team)
        assertEquals("MID", saka.position)
        assertEquals(90, saka.minutes)
        assertEquals(12, saka.livePoints)
        assertEquals(100, saka.nowCost)
        assertEquals(190000, saka.netTransfers)
        assertEquals("likely_rise", saka.priceEstimate)
        assertTrue(saka.priceEstimateReason.contains("Official GW price already moved"))
        val haaland = board.players.first { it.id == 2 }
        assertEquals("likely_fall", haaland.priceEstimate)
        val bench = board.players.first { it.id == 3 }
        // Only two positive-net players exist, so both sit in the top slice.
        assertEquals("likely_rise", bench.priceEstimate)
    }

    @Test
    fun `applyPriceEstimates only flags a small top slice`() {
        val rows = (1..25).map { i ->
            LiveBoardPlayer(
                id = i, webName = "P$i", team = "T", position = "MID", minutes = 0, livePoints = 0,
                nowCost = 50, costChangeEvent = 0, transfersInEvent = 1000 - i, transfersOutEvent = 0,
                netTransfers = 1000 - i, selectedBy = "1.0", priceEstimate = "", priceEstimateReason = ""
            )
        } + LiveBoardPlayer(
            id = 99, webName = "Out", team = "T", position = "DEF", minutes = 0, livePoints = 0,
            nowCost = 40, costChangeEvent = -1, transfersInEvent = 0, transfersOutEvent = 5000,
            netTransfers = -5000, selectedBy = "2.0", priceEstimate = "", priceEstimateReason = ""
        )
        val labeled = applyPriceEstimates(rows)
        assertEquals(20, labeled.count { it.priceEstimate == "likely_rise" })
        assertEquals("likely_fall", labeled.first { it.id == 99 }.priceEstimate)
        assertTrue(labeled.first { it.id == 99 }.priceEstimateReason.contains("Official GW price already moved"))
        assertEquals("stable", labeled.first { it.id == 25 }.priceEstimate)
    }

    @Test
    fun `estimate price rises uses latest bootstrap top net-in slice`() {
        val bootstrap = parse(
            """{"events":[{"id":1,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"},{"id":2,"short_name":"MCI"}],
            "elements":[
              {"id":1,"web_name":"Saka","first_name":"Bukayo","second_name":"Saka","team":1,"element_type":3,
               "now_cost":100,"cost_change_event":1,"transfers_in_event":200000,"transfers_out_event":10000,"selected_by_percent":"45.1"},
              {"id":2,"web_name":"Haaland","first_name":"Erling","second_name":"Haaland","team":2,"element_type":4,
               "now_cost":145,"cost_change_event":0,"transfers_in_event":5000,"transfers_out_event":180000,"selected_by_percent":"60.0"},
              {"id":3,"web_name":"AlreadyUp","first_name":"A","second_name":"U","team":1,"element_type":2,
               "now_cost":55,"cost_change_event":1,"transfers_in_event":10,"transfers_out_event":5,"selected_by_percent":"2.0"}
            ]}"""
        )
        val svc = FplService(FakeFplClient(bootstrap = bootstrap), clock = { 1_700_000_000_000L })
        val est = svc.estimatePriceRises()
        assertEquals(1, est.gameweek)
        assertEquals("2023-11-14T22:13:20Z", est.computedAt)
        assertTrue(est.estimateNote.contains("Estimate before tonight"))
        assertEquals(listOf(1, 3), est.rises.map { it.id })
        assertEquals("Saka", est.rises[0].webName)
        assertEquals("£10.0m", est.rises[0].nowPounds)
        assertEquals(190000, est.rises[0].netTransfers)
        assertEquals("likely_rise", est.rises[0].priceEstimate)
        assertEquals("likely_rise", est.rises[1].priceEstimate)
        assertTrue(est.rises.none { it.webName == "Haaland" })
    }

    @Test
    fun `official prices first refresh stores snapshot without movers`() {
        val dir = java.nio.file.Files.createTempDirectory("fpl-prices")
        val path = dir.resolve("prices-snapshot.json").toString()
        val bootstrap = parse(
            """{"events":[{"id":3,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"}],
            "elements":[{"id":1,"web_name":"Saka","first_name":"B","second_name":"S","team":1,"element_type":3,"now_cost":100}]}"""
        )
        val svc = FplService(FakeFplClient(bootstrap = bootstrap), pricesSnapshotPath = path, clock = { 1_700_000_000_000L })
        val first = svc.refreshOfficialPrices()
        assertEquals(true, first.firstRun)
        assertEquals(0, first.rises.size)
        assertEquals(0, first.falls.size)
        assertEquals(1, first.unchangedCount)
        val latest = svc.officialPrices()
        assertEquals(first.snapshotAt, latest.snapshotAt)
        assertEquals(true, latest.firstRun)
    }

    @Test
    fun `official prices refresh diffs now_cost against previous snapshot`() {
        val dir = java.nio.file.Files.createTempDirectory("fpl-prices")
        val path = dir.resolve("prices-snapshot.json").toString()
        val cheap = parse(
            """{"events":[{"id":3,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"},{"id":2,"short_name":"MCI"}],
            "elements":[
              {"id":1,"web_name":"Saka","first_name":"B","second_name":"S","team":1,"element_type":3,"now_cost":100},
              {"id":2,"web_name":"Haaland","first_name":"E","second_name":"H","team":2,"element_type":4,"now_cost":145},
              {"id":3,"web_name":"White","first_name":"B","second_name":"W","team":1,"element_type":2,"now_cost":55}
            ]}"""
        )
        val dear = parse(
            """{"events":[{"id":3,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"},{"id":2,"short_name":"MCI"}],
            "elements":[
              {"id":1,"web_name":"Saka","first_name":"B","second_name":"S","team":1,"element_type":3,"now_cost":101},
              {"id":2,"web_name":"Haaland","first_name":"E","second_name":"H","team":2,"element_type":4,"now_cost":144},
              {"id":3,"web_name":"White","first_name":"B","second_name":"W","team":1,"element_type":2,"now_cost":55}
            ]}"""
        )
        val t = java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L)
        val first = FplService(FakeFplClient(bootstrap = cheap), pricesSnapshotPath = path, clock = { t.get() })
        first.refreshOfficialPrices()
        t.set(1_700_086_400_000L)
        val second = FplService(FakeFplClient(bootstrap = dear), pricesSnapshotPath = path, clock = { t.get() })
        val moved = second.refreshOfficialPrices()
        assertEquals(false, moved.firstRun)
        assertEquals(1, moved.rises.size)
        assertEquals(1, moved.falls.size)
        assertEquals(1, moved.unchangedCount)
        assertEquals(1, moved.rises[0].id)
        assertEquals(100, moved.rises[0].oldCost)
        assertEquals(101, moved.rises[0].newCost)
        assertEquals(1, moved.rises[0].delta)
        assertEquals("£10.0m", moved.rises[0].oldPounds)
        assertEquals("£10.1m", moved.rises[0].newPounds)
        assertEquals(2, moved.falls[0].id)
        assertEquals(-1, moved.falls[0].delta)
        val cached = second.officialPrices()
        assertEquals(1, cached.rises.size)
        assertEquals(moved.previousSnapshotAt, first.officialPrices().snapshotAt)
    }

    @Test
    fun `parseEntryHistoryCurrent reads previous GW overall and totals`() {
        val history = parse(
            """{"current":[
              {"event":1,"points":46,"total_points":46,"overall_rank":5572584,"event_transfers_cost":0},
              {"event":2,"points":31,"total_points":77,"overall_rank":2639389,"event_transfers_cost":0}
            ]}"""
        )
        val rows = parseEntryHistoryCurrent(history)
        assertEquals(5_572_584, startOfGwOverallRank(rows, 2))
        assertEquals(46, previousEventTotal(rows, 2))
        assertEquals(null, startOfGwOverallRank(rows, 1))
        assertEquals(0, previousEventTotal(rows, 1))
    }

    @Test
    fun `Hashim-like entryLive exposes start-of-GW overall not current official as arrow baseline`() {
        val history = parse(
            """{"current":[
              {"event":1,"points":46,"total_points":46,"overall_rank":5572584,"event_transfers_cost":0},
              {"event":2,"points":31,"total_points":77,"overall_rank":2639389,"event_transfers_cost":0}
            ]}"""
        )
        val event = parse(
            """{"active_chip":null,"automatic_subs":[],
              "entry_history":{"event":2,"points":31,"total_points":77,"overall_rank":2639389,
                "bank":0,"value":1001,"event_transfers":0,"event_transfers_cost":0},
              "picks":[{"element":10,"position":1,"multiplier":1,"is_captain":true,"is_vice_captain":false}]}"""
        )
        val client = FakeFplClient(
            bootstrap = parse(
                """{"events":[{"id":2,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"}],
                "elements":[{"id":10,"first_name":"Bukayo","second_name":"Saka","team":1,"element_type":3}]}"""
            ),
            livePoints = mapOf(10 to 8),
            entryEvents = mapOf((5_498_977 to 2) to event),
            historyByEntry = mapOf(5_498_977 to history)
        )
        val live = FplService(client).entryLive(5_498_977, 2, true)
        assertEquals(5_572_584, live.startOfGwOverallRank)
        assertEquals(2_639_389, live.overallRank)
        assertEquals("official", live.overallRankLabel)
        val movement = rankMovement(2_542_858L, live.startOfGwOverallRank?.toLong())
        assertEquals(3_029_726L, movement)
        assertTrue(movement!! > 2_000_000L)
        assertEquals(96_531L, rankMovement(2_542_858L, live.overallRank?.toLong()))
    }

    @Test
    fun `Erik-like entryPicksLive total is 56 not 48 after an 8-pt hit`() {
        val history = parse(
            """{"current":[
              {"event":1,"points":31,"total_points":31,"overall_rank":8411328,"event_transfers_cost":0},
              {"event":2,"points":33,"total_points":56,"overall_rank":7010202,"event_transfers":3,"event_transfers_cost":8}
            ]}"""
        )
        val event = parse(
            """{"active_chip":null,"automatic_subs":[],
              "entry_history":{"event":2,"points":33,"total_points":56,"overall_rank":7010202,
                "bank":0,"value":1003,"event_transfers":3,"event_transfers_cost":8},
              "picks":[
                {"element":10,"position":1,"multiplier":2,"is_captain":true,"is_vice_captain":false},
                {"element":11,"position":2,"multiplier":1,"is_captain":false,"is_vice_captain":true}
              ]}"""
        )
        val liveJson = parse(
            """{"elements":[
              {"id":10,"stats":{"minutes":90,"total_points":10,"bonus":0,"bps":20}},
              {"id":11,"stats":{"minutes":90,"total_points":13,"bonus":0,"bps":10}}
            ]}"""
        )
        val client = FakeFplClient(
            bootstrap = parse(
                """{"events":[{"id":2,"is_current":true}],
                "teams":[{"id":1,"short_name":"ARS"},{"id":2,"short_name":"MCI"}],
                "elements":[
                  {"id":10,"first_name":"Bukayo","second_name":"Saka","team":1,"element_type":3},
                  {"id":11,"first_name":"Erling","second_name":"Haaland","team":2,"element_type":4}
                ]}"""
            ),
            livePoints = mapOf(10 to 10, 11 to 13),
            eventLiveJson = liveJson,
            entryEvents = mapOf((5_346_642 to 2) to event),
            historyByEntry = mapOf(5_346_642 to history)
        )
        val picks = FplService(client).entryPicksLive(5_346_642, 2)
        assertEquals(33, picks.gwGross)
        assertEquals(8, picks.transferCost)
        assertEquals(25, picks.gwNet)
        assertEquals(56, picks.officialTotal)
        assertEquals(33, picks.officialGwPoints)
        assertEquals(56, picks.liveTotal)
        assertEquals(48, picks.officialTotal - picks.officialGwPoints + picks.gwNet)
        assertEquals(8_411_328, picks.startOfGwOverallRank)
    }

    @Test
    fun `liveLeague exposes lastRank for mini-league place delta`() {
        val history = parse(
            """{"current":[
              {"event":1,"points":46,"total_points":46,"overall_rank":5572584,"event_transfers_cost":0}
            ]}"""
        )
        val client = FakeFplClient(
            bootstrap = parse(
                """{"events":[{"id":2,"is_current":true}],"teams":[{"id":1,"short_name":"ARS"}],
                "elements":[{"id":10,"first_name":"Bukayo","second_name":"Saka","team":1,"element_type":3}]}"""
            ),
            standings = mapOf(
                7 to LeagueStandingsPage(
                    results = listOf(StandingRow(2, 42, "Gunners", "Ada", lastRank = 5)),
                    hasNext = false,
                    totalEntries = 8
                )
            ),
            livePoints = mapOf(10 to 7),
            picks = mapOf((42 to 2) to listOf(PickRow(10, 1, 2, isCaptain = true, isViceCaptain = false))),
            historyByEntry = mapOf(42 to history)
        )
        val table = FplService(client).liveLeague(7, 2)
        val team = table.teams.single()
        assertEquals(5, team.lastRank)
        assertEquals(1, team.liveRank)
        assertEquals(5_572_584, team.startOfGwOverallRank)
        assertEquals("official", team.overallRankLabel)
        assertEquals(4L, rankMovement(team.liveRank.toLong(), team.lastRank?.toLong()))
    }
}

class FakeFplClient(
    private val bootstrap: JsonObject,
    private val classicLeagues: List<LeagueRef> = emptyList(),
    private val standings: Map<Int, LeagueStandingsPage> = emptyMap(),
    private val livePoints: Map<Int, Int> = emptyMap(),
    private val picks: Map<Pair<Int, Int>, List<PickRow>> = emptyMap(),
    private val entryJson: JsonObject = JsonObject(emptyMap()),
    private val throwEntryEvent: Boolean = false,
    private val standingsErrors: Map<Int, Exception> = emptyMap(),
    private val eventLiveJson: JsonObject = JsonObject(emptyMap()),
    private val fixtureList: List<JsonObject> = emptyList(),
    private val historyByEntry: Map<Int, JsonObject> = emptyMap(),
    private val entryEvents: Map<Pair<Int, Int>, JsonObject> = emptyMap()
) : FplClient {
    val standingsRequested = mutableListOf<Int>()
    override fun bootstrap(): JsonObject = bootstrap
    override fun userClassicLeagues(entryId: Int): List<LeagueRef> = classicLeagues
    override fun leagueStandingsPage(leagueId: Int, page: Int): LeagueStandingsPage {
        standingsRequested += leagueId
        standingsErrors[leagueId]?.let { throw it }
        return standings[leagueId] ?: throw FplApiException("no standings for $leagueId", 500)
    }
    override fun eventLiveElementPoints(eventId: Int): Map<Int, Int> = livePoints
    override fun entry(entryId: Int): JsonObject = entryJson
    override fun entryHistory(entryId: Int): JsonObject = historyByEntry[entryId] ?: JsonObject(emptyMap())
    override fun entryEvent(entryId: Int, eventId: Int): JsonObject {
        if (throwEntryEvent) throw FplApiException("missing picks", 404)
        return entryEvents[entryId to eventId] ?: JsonObject(emptyMap())
    }
    override fun entryPicks(entryId: Int, eventId: Int): List<PickRow> {
        if (throwEntryEvent) throw FplApiException("missing picks", 404)
        val fromEvent = entryEvents[entryId to eventId]
        if (fromEvent != null && !fromEvent.isEmpty()) return parsePicks(fromEvent)
        return picks[entryId to eventId] ?: emptyList()
    }
    override fun eventLive(eventId: Int): JsonObject = eventLiveJson
    override fun fixtures(eventId: Int): List<JsonObject> = fixtureList
}
