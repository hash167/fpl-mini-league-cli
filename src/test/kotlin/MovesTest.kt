import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class MovesTest {
    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val bootstrap = parse(
        """{"events":[{"id":2,"is_current":true}],
        "teams":[{"id":1,"short_name":"ARS"},{"id":2,"short_name":"MCI"},{"id":3,"short_name":"LIV"}],
        "elements":[
          {"id":10,"first_name":"Bukayo","second_name":"Saka","web_name":"Saka","team":1,"element_type":3},
          {"id":11,"first_name":"Erling","second_name":"Haaland","web_name":"Haaland","team":2,"element_type":4},
          {"id":12,"first_name":"Mohamed","second_name":"Salah","web_name":"M.Salah","team":3,"element_type":3},
          {"id":13,"first_name":"Cole","second_name":"Palmer","web_name":"Palmer","team":1,"element_type":3},
          {"id":14,"first_name":"David","second_name":"Raya","web_name":"Raya","team":1,"element_type":1}
        ]}"""
    )
    private val players = parsePlayerInfo(bootstrap)

    @Test
    fun `parses entry transfers and lists them OUT to IN in order with names`() {
        val raw = Json.parseToJsonElement(
            """[
              {"element_in":11,"element_in_cost":145,"element_out":12,"element_out_cost":130,"entry":1,"event":2,"time":"2026-10-22T23:17:33Z"},
              {"element_in":10,"element_in_cost":100,"element_out":13,"element_out_cost":105,"entry":1,"event":2,"time":"2026-10-22T23:17:32Z"},
              {"element_in":12,"element_in_cost":130,"element_out":10,"element_out_cost":99,"entry":1,"event":1,"time":"2026-10-01T10:00:00Z"}
            ]"""
        )
        val rows = parseTransfers(raw).filter { it.event == 2 }
        val views = transferViews(rows, players)
        assertEquals(listOf("Palmer" to "Saka", "M.Salah" to "Haaland"), views.map { it.outName to it.inName })
        assertEquals("MCI", views[1].inTeam)
        assertEquals(14.5, views[1].inCost)
        assertEquals(13.0, views[1].outCost)
    }

    @Test
    fun `autosubs are official once FPL applied them and provisional otherwise`() {
        val official = listOf(AppliedSub(elementOut = 12, elementIn = 13))
        val applied = official + AppliedSub(elementOut = 11, elementIn = 10)
        val views = autoSubViews(applied, official, players)
        assertEquals(listOf("Palmer" to "M.Salah", "Saka" to "Haaland"), views.map { it.inName to it.outName })
        assertFalse(views[0].provisional)
        assertTrue(views[1].provisional)
    }

    private fun leagueClient(
        entryEvent: String,
        transfers: List<TransferRow> = emptyList(),
        transfersError: Exception? = null
    ) = FakeFplClient(
        bootstrap = bootstrap,
        standings = mapOf(
            7 to LeagueStandingsPage(
                results = listOf(StandingRow(1, 42, "Gunners", "Ada", officialTotal = 100, eventTotal = 20)),
                hasNext = false,
                totalEntries = 1
            )
        ),
        entryEvents = mapOf((42 to 2) to parse(entryEvent)),
        transfersByEntry = mapOf(42 to transfers),
        transfersError = transfersError
    )

    private fun eventJson(transfers: Int, cost: Int, chip: String?, subs: String = "[]") = """{
        "active_chip": ${chip?.let { "\"$it\"" } ?: "null"},
        "automatic_subs": $subs,
        "entry_history": {"event":2,"points":20,"total_points":100,"event_transfers":$transfers,"event_transfers_cost":$cost},
        "picks": [
          {"element":14,"position":1,"multiplier":1,"is_captain":false,"is_vice_captain":false},
          {"element":12,"position":2,"multiplier":2,"is_captain":true,"is_vice_captain":false},
          {"element":11,"position":3,"multiplier":1,"is_captain":false,"is_vice_captain":true},
          {"element":13,"position":12,"multiplier":0,"is_captain":false,"is_vice_captain":false}
        ]}"""

    @Test
    fun `league team carries transfer count, list, hit and chip`() {
        val client = leagueClient(
            eventJson(transfers = 2, cost = 4, chip = "3xc"),
            transfers = listOf(TransferRow(2, elementIn = 11, elementOut = 10, time = "a"), TransferRow(2, elementIn = 12, elementOut = 13, time = "b"))
        )
        val team = FplService(client, pricesSnapshotPath = null).liveLeague(7, 2).teams.single()
        assertEquals(2, team.eventTransfers)
        assertEquals(4, team.transferCost)
        assertEquals("TC", team.activeChip)
        assertEquals(listOf("Saka" to "Haaland", "Palmer" to "M.Salah"), team.transfers.map { it.outName to it.inName })
        assertTrue(team.transfersListed)
    }

    @Test
    fun `wildcard transfers carry no hit`() {
        val client = leagueClient(
            eventJson(transfers = 5, cost = 0, chip = "wildcard"),
            transfers = (1..5).map { TransferRow(2, elementIn = 11, elementOut = 10, time = "t$it") }
        )
        val team = FplService(client, pricesSnapshotPath = null).liveLeague(7, 2).teams.single()
        assertEquals(5, team.eventTransfers)
        assertEquals(0, team.transferCost)
        assertEquals("WC", team.activeChip)
        assertEquals(5, team.transfers.size)
    }

    @Test
    fun `no transfers means no extra FPL call`() {
        val client = leagueClient(eventJson(transfers = 0, cost = 0, chip = null))
        val team = FplService(client, pricesSnapshotPath = null).liveLeague(7, 2).teams.single()
        assertEquals(0, team.eventTransfers)
        assertTrue(team.transfers.isEmpty())
        assertTrue(client.transfersRequested.isEmpty())
    }

    @Test
    fun `transfer list failure keeps the count and flags the list unavailable`() {
        val client = leagueClient(eventJson(transfers = 1, cost = 0, chip = null), transfersError = IOException("timeout"))
        val team = FplService(client, pricesSnapshotPath = null).liveLeague(7, 2).teams.single()
        assertEquals(1, team.eventTransfers)
        assertTrue(team.transfers.isEmpty())
        assertFalse(team.transfersListed)
    }

    @Test
    fun `official automatic subs show as came on for, not provisional`() {
        val client = leagueClient(
            eventJson(transfers = 0, cost = 0, chip = null, subs = """[{"element_in":13,"element_out":11,"entry":42,"event":2}]""")
        )
        val team = FplService(client, pricesSnapshotPath = null).liveLeague(7, 2).teams.single()
        assertEquals(1, team.autoSubs.size)
        val sub = team.autoSubs.single()
        assertEquals("Palmer", sub.inName)
        assertEquals("Haaland", sub.outName)
        assertFalse(sub.provisional)
    }

    @Test
    fun `transfers are cached per entry and gameweek`() {
        var calls = 0
        val inner = object : FplClient by leagueClient(eventJson(0, 0, null)) {
            override fun entryTransfers(entryId: Int, eventId: Int): List<TransferRow> {
                calls++
                return listOf(TransferRow(eventId, 11, 10))
            }
        }
        val c = CachingFplClient(inner)
        c.entryTransfers(42, 2); c.entryTransfers(42, 2)
        assertEquals(1, calls)
        c.entryTransfers(42, 3)
        assertEquals(2, calls)
    }
}
